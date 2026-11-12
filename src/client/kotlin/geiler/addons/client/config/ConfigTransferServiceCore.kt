package geiler.addons.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.dungeon.SecretWaypointStore
import geiler.addons.client.inventory.PlayerInventoryControls
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.BlockEspModule
import geiler.addons.client.module.impl.DungeonBossEspModule
import geiler.addons.client.module.impl.DungeonClassEspModule
import geiler.addons.client.module.impl.DungeonMobEspModule
import geiler.addons.client.module.impl.InventoryButtonsModule
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.MobHighlightModule
import geiler.addons.client.scoreboard.ScoreboardBackend
import geiler.addons.client.scoreboard.ScoreboardLayoutCodec
import net.minecraft.client.Minecraft
import net.fabricmc.loader.api.FabricLoader
import java.awt.Desktop
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Kotlin implementation for the stable Java-facing config-transfer API. */
object ConfigTransferServiceCore {
    private val ROOT: Path = FabricLoader.getInstance().configDir.resolve("geileraddons")
    private val EXPORTS: Path = ROOT.resolve("exports")
    private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val BUSY = AtomicBoolean()

    private data class RuntimeStoreSnapshot(
        val secretWaypoints: SecretWaypointStore.RuntimeSnapshot,
        val inventoryControls: PlayerInventoryControls.RuntimeSnapshot,
    )

    private data class ImportOutcome(
        val result: ConfigTransferService.Result,
        val installationAttempted: Boolean,
        val recoveryRestored: Boolean,
    )

    private data class Validated(val profile: ConfigTransferService.Profile, val files: Map<String, ByteArray>)
    private data class InstallResult(val installed: Boolean, val recoveryRestored: Boolean)

    @JvmStatic
    fun isBusy(): Boolean = BUSY.get()

    @JvmStatic
    fun configDirectory(): Path = ROOT

    @JvmStatic
    fun exportDirectory(): Path = EXPORTS

    @JvmStatic
    fun openFolder(path: Path?) {
        try {
            if (path != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile())
            else GeilerAddons.LOGGER.warn("No desktop file browser is available for {}", path)
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not open configuration folder {}", path, error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not open configuration folder {}", path, error)
        }
    }

    @JvmStatic
    fun saveCurrent(callback: Consumer<ConfigTransferService.Result>?) {
        if (!beginTransfer(callback)) return
        try {
            prepareFlushOnClient()
        } catch (error: Throwable) {
            BUSY.set(false)
            callback!!.accept(ConfigTransferService.Result(false, message(error), null, null))
            return
        }
        CompletableFuture.supplyAsync { flushAll() }.whenComplete(
            BiConsumer<Boolean?, Throwable?> { saved, failure ->
                Minecraft.getInstance().execute {
                    BUSY.set(false)
                    val result = when {
                        failure != null -> ConfigTransferService.Result(false, "Configuration save failed: ${message(failure)}", null, null)
                        java.lang.Boolean.TRUE == saved -> ConfigTransferService.Result(
                            true,
                            "Configuration and split collections saved.",
                            ROOT,
                            null,
                        )
                        else -> ConfigTransferService.Result(
                            false,
                            "A configuration or dungeon data save failed; no success was reported.",
                            null,
                            null,
                        )
                    }
                    callback!!.accept(result)
                }
            },
        )
    }

    @JvmStatic
    fun exportProfile(requestedName: String?, callback: Consumer<ConfigTransferService.Result>?) {
        if (!beginTransfer(callback)) return
        try {
            prepareFlushOnClient()
        } catch (error: Throwable) {
            BUSY.set(false)
            callback!!.accept(ConfigTransferService.Result(false, message(error), null, null))
            return
        }
        val name = safeProfileName(requestedName)
        CompletableFuture.supplyAsync {
            try {
                if (!flushAll()) throw IOException("Could not flush the current configuration.")
                ConfigTransferService.Result(true, "Configuration exported.", writeArchive(uniqueArchiveName(name)).path(), null)
            } catch (error: Exception) {
                ConfigTransferService.Result(false, message(error), null, null)
            }
        }.whenComplete(BiConsumer<ConfigTransferService.Result?, Throwable?> { result, failure ->
            Minecraft.getInstance().execute {
                BUSY.set(false)
                callback!!.accept(
                    if (failure == null) result!!
                    else ConfigTransferService.Result(false, message(failure), null, null),
                )
            }
        })
    }

    @JvmStatic
    fun listProfiles(callback: Consumer<List<ConfigTransferService.Profile>>?) {
        CompletableFuture.supplyAsync {
            val result = ArrayList<ConfigTransferService.Profile>()
            try {
                if (!Files.isDirectory(EXPORTS, LinkOption.NOFOLLOW_LINKS)) return@supplyAsync emptyList()
                Files.list(EXPORTS).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".gacfg.zip") }
                        .sorted(Comparator.comparing<Path, String> { it.fileName.toString() })
                        .forEach { path ->
                            try {
                                result.add(readArchive(path).profile)
                            } catch (_: Exception) {
                                // Invalid profiles remain on disk for the user to inspect or remove.
                            }
                        }
                }
            } catch (error: IOException) {
                GeilerAddons.LOGGER.warn("Could not list config exports", error)
            }
            Collections.unmodifiableList(ArrayList(result))
        }.whenComplete(BiConsumer<List<ConfigTransferService.Profile>?, Throwable?> { profiles, failure ->
            Minecraft.getInstance().execute {
                callback!!.accept(if (failure == null && profiles != null) profiles else emptyList())
            }
        })
    }

    @JvmStatic
    fun preview(path: Path?, callback: Consumer<ConfigTransferService.PreviewResult>?) {
        CompletableFuture.supplyAsync {
            try {
                ConfigTransferService.PreviewResult(true, "Profile is valid.", readArchive(path).profile)
            } catch (error: Exception) {
                ConfigTransferService.PreviewResult(false, message(error), null)
            }
        }.whenComplete(BiConsumer<ConfigTransferService.PreviewResult?, Throwable?> { result, failure ->
            Minecraft.getInstance().execute {
                callback!!.accept(
                    if (failure == null && result != null) result
                    else ConfigTransferService.PreviewResult(
                        false,
                        if (failure == null) "Profile preview failed." else message(failure),
                        null,
                    ),
                )
            }
        })
    }

    @JvmStatic
    fun importProfile(path: Path?, callback: Consumer<ConfigTransferService.Result>?) {
        if (!beginTransfer(callback)) return
        val runtimeSnapshot: RuntimeStoreSnapshot
        try {
            prepareFlushOnClient(profileImport = true)
            runtimeSnapshot = snapshotRuntimeStores()
        } catch (error: Throwable) {
            BUSY.set(false)
            callback!!.accept(ConfigTransferService.Result(false, message(error), null, null))
            return
        }
        beginRuntimeStoreTransaction()
        val recoveryArchive = AtomicReference<Path?>()
        val installationAttempted = AtomicBoolean()
        CompletableFuture.supplyAsync {
            try {
                if (!flushAll()) throw IOException("Could not flush current data before import.")
                val incoming = readArchive(path)
                val backup = writeArchive(uniqueArchiveName("recovery-before-import")).path()
                recoveryArchive.set(backup)
                val recovery = readArchive(backup)
                installationAttempted.set(true)
                val installed = installWithRollback(incoming.files, recovery.files)
                if (!installed.installed) {
                    val status = if (installed.recoveryRestored) {
                        "Import failed; the recovery files were restored."
                    } else {
                        "Import failed and recovery could not be completed."
                    }
                    ImportOutcome(
                        ConfigTransferService.Result(false, status, null, backup),
                        true,
                        installed.recoveryRestored,
                    )
                } else {
                    ImportOutcome(ConfigTransferService.Result(true, "Profile files installed.", path, backup), true, false)
                }
            } catch (error: Exception) {
                ImportOutcome(
                    ConfigTransferService.Result(false, message(error), null, recoveryArchive.get()),
                    installationAttempted.get(),
                    false,
                )
            }
        }.whenComplete(BiConsumer<ImportOutcome?, Throwable?> { result, failure ->
            Minecraft.getInstance().execute {
                val outcome = if (failure != null || result == null) {
                    ImportOutcome(
                        ConfigTransferService.Result(
                            false,
                            if (failure == null) "Import failed unexpectedly." else message(failure),
                            null,
                            recoveryArchive.get(),
                        ),
                        false,
                        false,
                    )
                } else {
                    result
                }
                var completed = outcome.result
                if (!completed.success()) {
                    if (ConfigProfileInstaller.shouldReloadRestoredFiles(
                            outcome.installationAttempted,
                            outcome.recoveryRestored,
                        )
                    ) {
                        try {
                            reloadInstalledProfile()
                            completed = ConfigTransferService.Result(
                                false,
                                completed.message() + " Runtime caches were reloaded from the restored files.",
                                null,
                                completed.recoveryPath(),
                            )
                        } catch (reloadFailure: Throwable) {
                            restoreRuntimeStores(runtimeSnapshot)
                            completed = ConfigTransferService.Result(
                                false,
                                completed.message() + " Runtime cache reload failed; previous Secret Waypoints and Inventory Controls caches were retained. " + message(reloadFailure),
                                null,
                                completed.recoveryPath(),
                            )
                        }
                    } else if (outcome.installationAttempted) {
                        restoreRuntimeStores(runtimeSnapshot)
                        completed = ConfigTransferService.Result(
                            false,
                            completed.message() + " Runtime caches were retained; disk recovery is incomplete, so the recovery archive remains available.",
                            null,
                            completed.recoveryPath(),
                        )
                    }
                    endRuntimeStoreTransaction()
                    BUSY.set(false)
                    callback!!.accept(completed)
                    return@execute
                }
                val applied: ConfigTransferService.Result
                try {
                    reloadInstalledProfile()
                    ModConfig.markDirty()
                    ModConfig.save()
                    applied = ConfigTransferService.Result(
                        true,
                        "Configuration imported and applied.",
                        completed.path(),
                        completed.recoveryPath(),
                    )
                } catch (error: Throwable) {
                    rollbackAndReload(
                        completed.recoveryPath(),
                        callback,
                        "Imported profile could not be applied: ${message(error)}",
                        runtimeSnapshot,
                    )
                    return@execute
                }
                endRuntimeStoreTransaction()
                BUSY.set(false)
                callback!!.accept(applied)
            }
        })
    }

    @JvmStatic
    fun reset(selection: ConfigTransferService.ResetSelection?, callback: Consumer<ConfigTransferService.Result>?) {
        if (selection == null || selection.areas().isEmpty()) {
            callback!!.accept(ConfigTransferService.Result(false, "Select at least one configuration area to reset.", null, null))
            return
        }
        if (!beginTransfer(callback)) return
        val runtimeSnapshot: RuntimeStoreSnapshot
        try {
            prepareFlushOnClient()
            runtimeSnapshot = snapshotRuntimeStores()
        } catch (error: Throwable) {
            BUSY.set(false)
            callback!!.accept(ConfigTransferService.Result(false, message(error), null, null))
            return
        }
        val chosen = selection
        beginRuntimeStoreTransaction()
        CompletableFuture.runAsync {
            if (!flushAll()) throw CompletionException(IOException("Could not flush current data before reset."))
        }.whenComplete(BiConsumer<Void?, Throwable?> { _, flushFailure ->
            Minecraft.getInstance().execute {
                if (flushFailure != null) {
                    endRuntimeStoreTransaction()
                    BUSY.set(false)
                    callback!!.accept(ConfigTransferService.Result(false, message(flushFailure), null, null))
                    return@execute
                }
                CompletableFuture.supplyAsync {
                    try {
                        writeArchive(uniqueArchiveName("recovery-before-reset"))
                    } catch (error: Exception) {
                        throw CompletionException(error)
                    }
                }.whenComplete(BiConsumer<ConfigTransferService.Profile?, Throwable?> { backup, backupFailure ->
                    Minecraft.getInstance().execute {
                        if (backupFailure != null || backup == null) {
                            endRuntimeStoreTransaction()
                            BUSY.set(false)
                            callback!!.accept(
                                ConfigTransferService.Result(
                                    false,
                                    if (backupFailure == null) "Could not create a recovery profile." else message(backupFailure),
                                    null,
                                    null,
                                ),
                            )
                            return@execute
                        }
                        try {
                            applyReset(chosen)
                            prepareFlushOnClient()
                            CompletableFuture.runAsync {
                                try {
                                    if (!flushAll()) throw IOException("The reset could not be saved.")
                                    if (chosen.includes(ConfigTransferService.ResetArea.INVENTORY_BUTTONS)) deleteIconDirectory()
                                } catch (error: IOException) {
                                    throw CompletionException(error)
                                }
                            }.whenComplete(BiConsumer<Void?, Throwable?> { _, saveFailure ->
                                Minecraft.getInstance().execute {
                                    if (saveFailure == null) {
                                        endRuntimeStoreTransaction()
                                        BUSY.set(false)
                                        callback!!.accept(
                                            ConfigTransferService.Result(
                                                true,
                                                "Selected configuration reset. A recovery profile was created first.",
                                                null,
                                                backup.path(),
                                            ),
                                        )
                                    } else {
                                        rollbackAndReload(
                                            backup.path(),
                                            callback,
                                            "Reset save failed: ${message(saveFailure)}",
                                            runtimeSnapshot,
                                        )
                                    }
                                }
                            })
                        } catch (error: Throwable) {
                            rollbackAndReload(
                                backup.path(),
                                callback,
                                "Reset could not be completed: ${message(error)}",
                                runtimeSnapshot,
                            )
                        }
                    }
                })
            }
        })
    }

    private fun applyReset(selection: ConfigTransferService.ResetSelection) {
        val mobModuleNames = setOf(
            DungeonMobEspModule.INSTANCE.configName(),
            DungeonBossEspModule.INSTANCE.configName(),
            DungeonClassEspModule.INSTANCE.configName(),
            MobHighlightModule.INSTANCE.configName(),
            BlockEspModule.INSTANCE.configName(),
        )
        val resetPlan = ConfigResetPlan.create(
            selection.includes(ConfigTransferService.ResetArea.MODULES),
            selection.includes(ConfigTransferService.ResetArea.MACROS),
            selection.includes(ConfigTransferService.ResetArea.MOB_ESP),
            selection.includes(ConfigTransferService.ResetArea.SCOREBOARD),
            MacrosModule.INSTANCE.configName(),
            mobModuleNames,
        )
        if (resetPlan.resetAllModules()) {
            ModConfig.resetModuleDefaultsExcept(resetPlan.excludedModules())
            resetClickGuiState()
        } else if (resetPlan.namedModules().isNotEmpty()) {
            ModConfig.resetNamedModuleDefaults(resetPlan.namedModules())
        }
        if (selection.includes(ConfigTransferService.ResetArea.MACROS)) {
            MacroRunner.cancel("macro configuration reset")
            MacrosModule.INSTANCE.restore(emptyList())
            MacrosModule.INSTANCE.restoreFunctions(emptyList())
            MacrosModule.INSTANCE.globalVariables().restore(emptyList())
            MacrosModule.INSTANCE.folders().restore(emptyList())
        }
        if (selection.includes(ConfigTransferService.ResetArea.MOB_ESP)) {
            MobHighlightModule.INSTANCE.restore(emptyList())
            MobHighlightModule.INSTANCE.folders().restore(emptyList())
            BlockEspModule.INSTANCE.restore(emptyList())
            BlockEspModule.INSTANCE.folders().restore(emptyList())
        }
        if (selection.includes(ConfigTransferService.ResetArea.DUNGEON_GUIDES) && !DungeonGuideStore.resetToFactoryDefaults()) {
            throw IOException("Dungeon Guide data is blocked or could not be saved safely.")
        }
        if (selection.includes(ConfigTransferService.ResetArea.INVENTORY_BUTTONS)) {
            InventoryButtonsModule.INSTANCE.restore(emptyList())
            geiler.addons.client.gui.InventoryButtonOverlay.clearIconTextureCache()
        }
        if (selection.includes(ConfigTransferService.ResetArea.TIKI_DATA)) {
            geiler.addons.client.config.TikiCoords.seedDefaults()
            geiler.addons.client.config.TikiFingerprints.replaceAll(emptyMap())
        }
        if (selection.includes(ConfigTransferService.ResetArea.HUD_AND_TREE)) {
            geiler.addons.client.hud.HudManager.resetPositions()
            for (type in geiler.addons.client.tree.TreeType.values()) {
                geiler.addons.client.module.impl.TreeTrackerModule.INSTANCE.tracker().stats(type).restore(0, 0, 0, 0)
            }
        }
        if (resetPlan.resetScoreboard() && !ScoreboardBackend.resetToDefaults()) {
            throw IOException("Scoreboard layout defaults could not be applied on the client thread.")
        }
        ModConfig.saveAfterReset()
    }

    private fun resetClickGuiState() {
        ClickGuiState.setCategory(Category.values()[0])
        ClickGuiState.setOpenModule(null)
        ClickGuiState.setExpandedColor(null)
        ClickGuiState.setSettingsScroll(0)
        ClickGuiState.setCategoryScroll(0)
        for (category in Category.values()) ClickGuiState.setGridScroll(category, 0)
        ClickGuiState.setFavoriteCategoryIds(emptyList())
        ClickGuiState.setFavoriteModuleIds(emptyList())
    }

    private fun flushAll(): Boolean {
        return try {
            val configSaved = ModConfig.flushPrepared()
            val guidesSaved = DungeonGuideStore.flushAndReport()
            val scoreboardSaved = ScoreboardBackend.flushPrepared()
            val clientJsonSaved = ClientJsonFile.flushAndReport()
            configSaved && guidesSaved && scoreboardSaved && clientJsonSaved
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.error("Could not flush configuration before transfer", error)
            false
        }
    }

    private fun beginTransfer(callback: Consumer<ConfigTransferService.Result>?): Boolean {
        if (!BUSY.compareAndSet(false, true)) {
            callback!!.accept(
                ConfigTransferService.Result(
                    false,
                    "Another configuration operation is already in progress.",
                    null,
                    null,
                ),
            )
            return false
        }
        return true
    }

    private fun prepareFlushOnClient() = prepareFlushOnClient(profileImport = false)

    private fun prepareFlushOnClient(profileImport: Boolean) {
        ModConfig.save()
        DungeonGuideStore.prepareFlush()
        if (!ScoreboardBackend.prepareFlush()) {
            throw IllegalStateException("Could not snapshot the scoreboard layout on the client thread.")
        }
        val inventorySaved = if (profileImport) {
            PlayerInventoryControls.INSTANCE.flushForProfileImport()
        } else {
            PlayerInventoryControls.INSTANCE.flush()
        }
        if (!inventorySaved) throw IllegalStateException("Could not save Inventory Controls before the profile operation.")
    }

    private fun snapshotRuntimeStores() = RuntimeStoreSnapshot(
        SecretWaypointStore.snapshotRuntimeState(),
        PlayerInventoryControls.INSTANCE.snapshotRuntimeState(),
    )

    private fun restoreRuntimeStores(snapshot: RuntimeStoreSnapshot?) {
        if (snapshot == null) return
        SecretWaypointStore.restoreRuntimeState(snapshot.secretWaypoints)
        PlayerInventoryControls.INSTANCE.restoreRuntimeState(snapshot.inventoryControls)
    }

    private fun beginRuntimeStoreTransaction() {
        SecretWaypointStore.beginProfileTransaction()
        PlayerInventoryControls.INSTANCE.beginProfileTransaction()
    }

    private fun endRuntimeStoreTransaction() {
        SecretWaypointStore.endProfileTransaction()
        PlayerInventoryControls.INSTANCE.endProfileTransaction()
    }

    @Throws(IOException::class)
    private fun reloadInstalledProfile() {
        geiler.addons.client.gui.InventoryButtonOverlay.clearIconTextureCache()
        ModConfig.resetRuntimeToFactoryDefaults()
        ModConfig.load()
        geiler.addons.client.module.impl.VisualModule.INSTANCE.refreshTheme()
        if (!DungeonGuideStore.reloadFromDisk()) throw IOException("Dungeon Guide profile data could not be loaded.")
        if (!ScoreboardBackend.reloadLayout()) throw IOException("Scoreboard layout profile data could not be loaded.")
        if (!SecretWaypointStore.reloadFromDisk()) throw IOException("Secret Waypoints profile data could not be loaded.")
        if (!PlayerInventoryControls.INSTANCE.reloadFromDisk()) throw IOException("Inventory Controls profile data could not be loaded.")
    }

    private fun rollbackAndReload(
        backup: Path?,
        callback: Consumer<ConfigTransferService.Result>?,
        failure: String,
        runtimeSnapshot: RuntimeStoreSnapshot?,
    ) {
        if (backup == null) {
            restoreRuntimeStores(runtimeSnapshot)
            endRuntimeStoreTransaction()
            BUSY.set(false)
            callback!!.accept(
                ConfigTransferService.Result(
                    false,
                    "$failure Recovery archive is unavailable; runtime caches were retained.",
                    null,
                    null,
                ),
            )
            return
        }
        CompletableFuture.supplyAsync {
            try {
                installWithRollback(readArchive(backup).files, emptyMap()).installed
            } catch (_: Exception) {
                false
            }
        }.whenComplete(BiConsumer<Boolean?, Throwable?> { diskRestored, restoreFailure ->
            Minecraft.getInstance().execute {
                val authoritativeDiskRestored = restoreFailure == null && java.lang.Boolean.TRUE == diskRestored
                var runtimeReloaded = false
                var reloadFailure: String? = null
                if (authoritativeDiskRestored) {
                    try {
                        reloadInstalledProfile()
                        runtimeReloaded = true
                    } catch (error: Throwable) {
                        reloadFailure = message(error)
                    }
                }
                if (!authoritativeDiskRestored || !runtimeReloaded) restoreRuntimeStores(runtimeSnapshot)
                endRuntimeStoreTransaction()
                BUSY.set(false)
                val recoveryStatus = if (authoritativeDiskRestored) {
                    if (runtimeReloaded) {
                        " Recovery files were restored and runtime caches were reloaded."
                    } else {
                        " Recovery files were restored, but runtime cache reload failed ($reloadFailure); previous Secret Waypoints and Inventory Controls caches were retained."
                    }
                } else {
                    " Recovery failed; runtime caches were returned to their pre-transaction state, but disk may be incomplete. Recovery archive remains at $backup"
                }
                callback!!.accept(ConfigTransferService.Result(false, failure + recoveryStatus, null, backup))
            }
        })
    }

    @Throws(IOException::class)
    private fun writeArchive(baseName: String): ConfigTransferService.Profile {
        val files = collectCurrentFiles()
        if (!files.containsKey("config.json")) throw IOException("The active config.json snapshot is missing.")
        val archive = ConfigProfileCodec.encode(files)
        val checked = ConfigProfileCodec.decode(archive)
        ensureSafeRoot()
        val rootGuard = ConfigPathGuard.checkedDirectory(ROOT)
        Files.createDirectories(EXPORTS)
        val exportsGuard = ConfigPathGuard.checkedDirectory(EXPORTS)
        ConfigPathGuard.assertContained(rootGuard, EXPORTS)
        var target = EXPORTS.resolve("$baseName.gacfg.zip").normalize()
        if (target.parent != EXPORTS.normalize()) throw IOException("Unsafe export path.")
        val temporary = Files.createTempFile(EXPORTS, "profile-", ".tmp")
        try {
            ConfigPathGuard.assertContained(exportsGuard, temporary)
            Files.write(temporary, archive)
            if (Files.size(temporary) > MAX_ARCHIVE_BYTES) throw IOException("Profile archive exceeds the 32 MB limit.")
            var suffix = 2
            while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                target = EXPORTS.resolve("$baseName-${suffix++}.gacfg.zip")
            }
            ConfigPathGuard.rejectReparseChain(target)
            moveAtomic(temporary, target)
            ConfigPathGuard.assertContained(exportsGuard, target)
            return ConfigTransferService.Profile(
                target,
                target.fileName.toString(),
                files.size,
                checked.totalBytes(),
                checked.createdAt(),
            )
        } finally {
            // Do not follow a path that may have been swapped to a junction during archive writing.
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    ConfigPathGuard.assertContained(exportsGuard, temporary)
                    Files.deleteIfExists(temporary)
                } catch (unsafeCleanup: IOException) {
                    GeilerAddons.LOGGER.warn(
                        "Leaving unsafe config-profile temp file in place rather than deleting outside the exports folder",
                        unsafeCleanup,
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun collectCurrentFiles(root: Path = ROOT): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val configRoot = root.toAbsolutePath().normalize()
        val exports = configRoot.resolve("exports")
        ensureSafeRoot(configRoot)
        if (!Files.exists(configRoot, LinkOption.NOFOLLOW_LINKS)) return files
        val rootGuard = ConfigPathGuard.checkedDirectory(configRoot)
        Files.walkFileTree(
            configRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ConfigPathGuard.assertSafe(dir, attrs)
                    ConfigPathGuard.assertContained(rootGuard, dir)
                    if (dir != configRoot && dir.startsWith(exports)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ConfigPathGuard.assertSafe(file, attrs)
                    ConfigPathGuard.assertContained(rootGuard, file)
                    if (!attrs.isRegularFile) throw IOException("Only regular config files can be exported.")
                    val relative = configRoot.relativize(file).toString().replace('\\', '/')
                    if (relative.startsWith("exports/")) return FileVisitResult.CONTINUE
                    if (ConfigProfileCodec.isLocalOnlyPath(relative)) return FileVisitResult.CONTINUE
                    if (!safePersistentPath(relative)) throw IOException("Unrecognized persistent config file: $relative")
                    val size = attrs.size()
                    if (size < 0 || size > MAX_ENTRY_BYTES) throw IOException("Config entry exceeds the 8 MB limit: $relative")
                    val bytes = Files.readAllBytes(file)
                    if (bytes.size.toLong() != size) throw IOException("Config entry changed while it was being read: $relative")
                    files[relative] = bytes
                    if (files.size > MAX_ENTRIES) throw IOException("Too many config files to export.")
                    var total = 0L
                    for (value in files.values) total += value.size
                    if (total > MAX_TOTAL_BYTES) throw IOException("Config files exceed the 64 MB expanded-size limit.")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files
    }

    @Throws(IOException::class)
    private fun readArchive(path: Path?): Validated {
        val safe = validateProfilePath(path)
        val zipSize = Files.size(safe)
        if (zipSize <= 0 || zipSize > MAX_ARCHIVE_BYTES) throw IOException("Archive size is outside the 32 MB limit.")
        val archive = Files.readAllBytes(safe)
        val payload = ConfigProfileCodec.decode(archive)
        validateConfigSchema(payload.files())
        return Validated(
            ConfigTransferService.Profile(
                safe,
                safe.fileName.toString(),
                payload.files().size,
                payload.totalBytes(),
                payload.createdAt(),
            ),
            payload.files(),
        )
    }

    @Throws(IOException::class)
    private fun validateConfigSchema(files: Map<String, ByteArray>) {
        val scoreboardLayout = files["scoreboard/layout.json"]
        if (scoreboardLayout != null) {
            val json = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(scoreboardLayout))
                    .toString()
            } catch (malformedUtf8: CharacterCodingException) {
                throw IOException("Scoreboard layout is not valid UTF-8.", malformedUtf8)
            }
            if (ScoreboardLayoutCodec.decode(json).isEmpty) {
                throw IOException("Scoreboard layout JSON is invalid or exceeds its size limit.")
            }
        }
        val config = parseObject(files["config.json"], "config.json")
        requirePrimitiveMap(config, "enabled", PrimitiveKind.BOOLEAN)
        requirePrimitiveMap(config, "toggles", PrimitiveKind.BOOLEAN)
        requirePrimitiveMap(config, "choices", PrimitiveKind.STRING)
        requirePrimitiveMap(config, "texts", PrimitiveKind.STRING)
        requirePrimitiveMap(config, "numbers", PrimitiveKind.NUMBER)
        requireColorMap(config, "colors")
        val keybinds = requiredObject(config, "keybinds", 4096)
        for (item in keybinds.entrySet()) {
            if (!item.value.isJsonObject) throw IOException("Config keybind is not an object.")
            val bind = item.value.asJsonObject
            if (!stringPrimitive(bind.get("key")) || !numberPrimitive(bind.get("modifiers"))) {
                throw IOException("Config keybind has invalid key or modifier fields.")
            }
        }
        validateOptionalConfigData(config)
        val indexBytes = files["macro-index.json"]
        if (indexBytes != null) {
            val index = parseObject(indexBytes, "macro-index.json")
            if (intValue(index, "version") != 1 || !index.has("macros") || !index.get("macros").isJsonArray ||
                !index.has("functions") || !index.get("functions").isJsonArray ||
                index.getAsJsonArray("macros").size() > 2000 || index.getAsJsonArray("functions").size() > 64
            ) {
                throw IOException("Macro index schema is invalid.")
            }
            validateMacroRefs(files, index.getAsJsonArray("macros"), "macros/")
            validateMacroRefs(files, index.getAsJsonArray("functions"), "functions/")
        }
        for (name in files.keys) {
            if ((name.startsWith("macros/") || name.startsWith("functions/")) && indexBytes == null) {
                throw IOException("Split macro data is present without its index.")
            }
        }
        validateGuideData(files)
    }

    @Throws(IOException::class)
    private fun validateOptionalConfigData(config: JsonObject) {
        optionalArray(config, "tikiCoords", 4096)
        optionalArray(config, "mobHighlights", 256)
        optionalArray(config, "blockEspEntries", 256)
        optionalArray(config, "macroFolders", 4096)
        optionalArray(config, "mobHighlightFolders", 4096)
        optionalArray(config, "blockEspFolders", 4096)
        optionalArray(config, "inventoryButtons", 256)
        optionalArray(config, "macros", 2000)
        optionalArray(config, "macroFunctions", 64)
        optionalArray(config, "macroVariables", 512)
        optionalMap(config, "tikiFingerprints", 8192, PrimitiveKind.STRING)
        optionalMap(config, "uiGridScrollByCategory", 64, PrimitiveKind.NUMBER)
        optionalMap(config, "uiGridScrolls", 64, PrimitiveKind.NUMBER)
        optionalString(config, "uiCategory")
        optionalString(config, "uiOpenModule")
        optionalString(config, "uiExpandedColor")
        optionalNumber(config, "uiSettingsScroll")
        optionalNumber(config, "uiCategoryScroll")
        for (key in listOf("checkForUpdates", "hypixelModApi")) {
            if (config.has(key) && !config.get(key).isJsonNull && !booleanPrimitive(config.get(key))) {
                throw IOException("Config $key flag is invalid.")
            }
        }
        for (key in listOf("uiFavoriteCategories", "uiFavoriteModules")) {
            val values = optionalArray(config, key, 4096)
            if (values != null) {
                for (value in values) if (!stringPrimitive(value)) throw IOException("Config favorite id is not text.")
            }
        }
        for (key in listOf(
                "tikiCoords",
                "macroFolders",
                "mobHighlightFolders",
                "blockEspFolders",
                "inventoryButtons",
                "mobHighlights",
                "blockEspEntries",
                "macros",
                "macroFunctions",
                "macroVariables",
            )
        ) {
            val values = optionalArray(config, key, Int.MAX_VALUE) ?: continue
            for (value in values) {
                if (!value.isJsonObject && !value.isJsonArray && !value.isJsonPrimitive) {
                    throw IOException("Config $key entry has an invalid JSON type.")
                }
            }
        }
        val coords = optionalArray(config, "tikiCoords", 4096)
        if (coords != null) {
            for (coord in coords) {
                if (!coord.isJsonArray || coord.asJsonArray.size() != 3 ||
                    coord.asJsonArray.asList().any { !numberPrimitive(it) }
                ) throw IOException("Tiki coordinate is invalid.")
            }
        }
        val hud = optionalObject(config, "hudPositions", 256)
        if (hud != null) {
            for (item in hud.entrySet()) {
                if (!item.value.isJsonArray || item.value.asJsonArray.size() != 2 ||
                    item.value.asJsonArray.asList().any { !numberPrimitive(it) }
                ) throw IOException("HUD position is invalid.")
            }
        }
        val trees = optionalObject(config, "treeGifts", 64)
        if (trees != null) {
            for (item in trees.entrySet()) {
                if (!item.value.isJsonArray || item.value.asJsonArray.size() != 4 ||
                    item.value.asJsonArray.asList().any { !numberPrimitive(it) }
                ) throw IOException("Tree Tracker totals are invalid.")
            }
        }
    }

    @Throws(IOException::class)
    private fun validateGuideData(files: Map<String, ByteArray>) {
        val indexBytes = files["dungeon-guides/index.json"] ?: return
        val index = parseObject(indexBytes, "Dungeon Guide index")
        val version = intValue(index, "version")
        val routeValues = index.get("routes")
        if ((version != 1 && version != 2) || !index.has("routes") || !routeValues.isJsonArray ||
            index.getAsJsonArray("routes").size() > ClientJsonFile.MAX_ENTRIES
        ) throw IOException("Dungeon Guide index schema is invalid.")
        val routeFiles = HashSet<String>()
        for (routeElement in index.getAsJsonArray("routes")) {
            if (!routeElement.isJsonObject) throw IOException("Dungeon Guide route entry is invalid.")
            val route = routeElement.asJsonObject
            val floor = stringValue(route, "floor")
            val phase = stringValue(route, "phase")
            val relativePath = stringValue(route, "file")
            if (floor == null || !floor.matches(Regex("(?:Entrance|F[1-7]|M[1-7])")) ||
                phase == null || !phase.matches(Regex("[A-Z][A-Z0-9_]{0,31}")) ||
                relativePath == null
            ) throw IOException("Dungeon Guide route reference is invalid.")
            val pathSegments = relativePath.split('/')
            if (relativePath.length > 256 || pathSegments.size != 3 ||
                pathSegments[0] != floor || pathSegments[1] != phase
            ) throw IOException("Dungeon Guide route reference is invalid.")
            val name = pathSegments[2]
            if (!name.matches(Regex("[a-z0-9._-]{1,48}-[0-9a-z]+\\.json"))) {
                throw IOException("Dungeon Guide route reference is invalid.")
            }
            val member = "dungeon-guides/$relativePath"
            if (!routeFiles.add(member) || !files.containsKey(member)) {
                throw IOException("Dungeon Guide route file is duplicated or missing.")
            }
            val routeFile = parseObject(files[member], member)
            val nodes = routeFile.get("nodes")
            if (intValue(routeFile, "version") != 1 || !routeFile.has("nodes") || !nodes.isJsonArray ||
                routeFile.getAsJsonArray("nodes").size() > ClientJsonFile.MAX_ENTRIES
            ) throw IOException("Dungeon Guide route file schema is invalid.")
            for (node in routeFile.getAsJsonArray("nodes")) {
                if (!node.isJsonObject || !stringPrimitive(node.asJsonObject.get("id")) ||
                    !stringPrimitive(node.asJsonObject.get("floor")) ||
                    !stringPrimitive(node.asJsonObject.get("phase")) ||
                    !stringPrimitive(node.asJsonObject.get("routeName"))
                ) throw IOException("Dungeon Guide node schema is invalid.")
            }
        }
    }

    private enum class PrimitiveKind { BOOLEAN, NUMBER, STRING }

    @Throws(IOException::class)
    private fun requirePrimitiveMap(root: JsonObject, key: String, kind: PrimitiveKind) {
        val obj = requiredObject(root, key, 4096)
        for (item in obj.entrySet()) if (!primitive(item.value, kind)) {
            throw IOException("Config map $key contains a value of the wrong type.")
        }
    }

    @Throws(IOException::class)
    private fun requiredObject(root: JsonObject, key: String, limit: Int): JsonObject {
        if (!root.has(key) || !root.get(key).isJsonObject || root.getAsJsonObject(key).size() > limit) {
            throw IOException("Config has an invalid $key map.")
        }
        return root.getAsJsonObject(key)
    }

    @Throws(IOException::class)
    private fun optionalObject(root: JsonObject, key: String, limit: Int): JsonObject? {
        if (!root.has(key) || root.get(key).isJsonNull) return null
        if (!root.get(key).isJsonObject || root.getAsJsonObject(key).size() > limit) {
            throw IOException("Config has an invalid $key map.")
        }
        return root.getAsJsonObject(key)
    }

    @Throws(IOException::class)
    private fun optionalArray(root: JsonObject, key: String, limit: Int): JsonArray? {
        if (!root.has(key) || root.get(key).isJsonNull) return null
        if (!root.get(key).isJsonArray || root.getAsJsonArray(key).size() > limit) {
            throw IOException("Config has an invalid $key list.")
        }
        return root.getAsJsonArray(key)
    }

    @Throws(IOException::class)
    private fun optionalMap(root: JsonObject, key: String, limit: Int, kind: PrimitiveKind) {
        val obj = optionalObject(root, key, limit) ?: return
        for (value in obj.asMap().values) if (!primitive(value, kind)) {
            throw IOException("Config map $key has a value of the wrong type.")
        }
    }

    @Throws(IOException::class)
    private fun optionalString(root: JsonObject, key: String) {
        if (root.has(key) && !root.get(key).isJsonNull && !stringPrimitive(root.get(key))) {
            throw IOException("Config $key must be text.")
        }
    }

    @Throws(IOException::class)
    private fun optionalNumber(root: JsonObject, key: String) {
        if (root.has(key) && !root.get(key).isJsonNull && !numberPrimitive(root.get(key))) {
            throw IOException("Config $key must be numeric.")
        }
    }

    private fun primitive(value: JsonElement?, kind: PrimitiveKind): Boolean = when (kind) {
        PrimitiveKind.BOOLEAN -> booleanPrimitive(value)
        PrimitiveKind.NUMBER -> numberPrimitive(value)
        PrimitiveKind.STRING -> stringPrimitive(value)
    }

    private fun booleanPrimitive(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean

    private fun numberPrimitive(value: JsonElement?): Boolean {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return value.asDouble.isFinite()
    }

    private fun stringPrimitive(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString

    @Throws(IOException::class)
    private fun requireColorMap(root: JsonObject, key: String) {
        val colors = requiredObject(root, key, 4096)
        for (value in colors.asMap().values) {
            if (!value.isJsonArray || value.asJsonArray.size() != 4) {
                throw IOException("Config color entry must contain RGBA channels.")
            }
            for (channel in value.asJsonArray) {
                if (!numberPrimitive(channel) || channel.asInt < 0 || channel.asInt > 255) {
                    throw IOException("Config color channel is invalid.")
                }
            }
        }
    }

    private fun stringValue(obj: JsonObject, key: String): String? =
        if (stringPrimitive(obj.get(key))) obj.get(key).asString else null

    @Throws(IOException::class)
    private fun validateMacroRefs(files: Map<String, ByteArray>, names: JsonArray, prefix: String) {
        val unique = HashSet<String>()
        for (element in names) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw IOException("Macro index has a non-text file reference.")
            }
            val name = element.asString
            if (!name.matches(Regex("[A-Za-z0-9._-]{1,128}\\.json")) || !unique.add(name) ||
                !files.containsKey(prefix + name)
            ) throw IOException("Macro index references a missing or unsafe file.")
            val data = parseObject(files[prefix + name], prefix + name)
            if (prefix == "macros/") {
                if (!numberPrimitive(data.get("id")) || !stringPrimitive(data.get("name")) ||
                    !data.has("steps") || !data.get("steps").isJsonArray || data.getAsJsonArray("steps").size() > 4096
                ) throw IOException("Macro record schema is invalid.")
            } else if (!stringPrimitive(data.get("id")) || !stringPrimitive(data.get("name")) ||
                !data.has("parameters") || !data.get("parameters").isJsonArray || data.getAsJsonArray("parameters").size() > 32 ||
                !data.has("steps") || !data.get("steps").isJsonArray || data.getAsJsonArray("steps").size() > 4096
            ) {
                throw IOException("Reusable function record schema is invalid.")
            }
        }
    }

    @Throws(IOException::class)
    private fun parseObject(bytes: ByteArray?, name: String): JsonObject {
        val checkedBytes = bytes ?: throw IOException("JSON member is empty or too large.")
        ConfigProfileCodec.validateJsonBounds(checkedBytes)
        val parsed = try {
            JsonParser.parseString(String(checkedBytes, StandardCharsets.UTF_8))
        } catch (error: RuntimeException) {
            throw IOException("$name is invalid JSON.", error)
        } catch (error: StackOverflowError) {
            throw IOException("$name is invalid JSON.", error)
        }
        if (!parsed.isJsonObject) throw IOException("$name must contain a JSON object.")
        return parsed.asJsonObject
    }

    private fun installWithRollback(incoming: Map<String, ByteArray>, backup: Map<String, ByteArray>): InstallResult {
        try {
            ensureSafeRoot()
        } catch (error: IOException) {
            GeilerAddons.LOGGER.error("Config transfer root is not safe", error)
            return InstallResult(false, false)
        }
        val result = ConfigProfileInstaller.install(
            ROOT,
            incoming,
            backup,
            java.util.function.Predicate { ConfigProfileCodec.safePath(it) },
            ConfigProfileInstaller.PayloadValidator { files ->
                val validatedFiles = LinkedHashMap<String, ByteArray>()
                for ((path, bytes) in files) {
                    if (bytes == null) throw IOException("Replacement contains a missing file.")
                    validatedFiles[path] = bytes
                }
                validateConfigSchema(validatedFiles)
            },
            null,
        )
        return InstallResult(result.installed, result.recoveryRestored)
    }

    @Throws(IOException::class)
    private fun deleteIconDirectory() {
        val icons = InventoryButtonsModule.INSTANCE.iconDirectory().toAbsolutePath().normalize()
        val configRoot = ROOT.toAbsolutePath().normalize()
        if (!icons.startsWith(configRoot) || icons != configRoot.resolve("inventory-button-icons")) {
            throw IOException("Inventory icon directory failed its safety check.")
        }
        ensureSafeRoot()
        if (Files.exists(icons, LinkOption.NOFOLLOW_LINKS)) {
            val configGuard = ConfigPathGuard.checkedDirectory(configRoot)
            ConfigPathGuard.assertContained(configGuard, icons)
        }
        deleteTree(icons)
    }

    @Throws(IOException::class)
    private fun deleteTree(root: Path?) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        ConfigPathGuard.rejectReparseChain(root)
        val rootGuard = ConfigPathGuard.checkedDirectory(root)
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ConfigPathGuard.assertSafe(file, attrs)
                    ConfigPathGuard.assertContained(rootGuard, file)
                    if (!attrs.isRegularFile) throw IOException("Refusing to remove a non-regular file.")
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ConfigPathGuard.assertSafe(dir, attrs)
                    ConfigPathGuard.assertContained(rootGuard, dir)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    ConfigPathGuard.assertContained(rootGuard, dir)
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    @Throws(IOException::class)
    private fun validateProfilePath(path: Path?): Path {
        if (path == null) throw IOException("No profile was selected.")
        val absolute = path.toAbsolutePath().normalize()
        val exportRoot = EXPORTS.toAbsolutePath().normalize()
        ensureSafeRoot()
        val rootGuard = ConfigPathGuard.checkedDirectory(ROOT)
        val exportsGuard = ConfigPathGuard.checkedDirectory(exportRoot)
        ConfigPathGuard.assertContained(rootGuard, exportRoot)
        if (!absolute.startsWith(exportRoot) || absolute.parent != exportRoot) {
            throw IOException("Profiles can be imported only from the GeilerAddons exports folder.")
        }
        ConfigPathGuard.assertContained(exportsGuard, absolute)
        if (!ConfigPathGuard.readExisting(absolute).isRegularFile) throw IOException("Profile is not a regular file.")
        return absolute
    }

    @Throws(IOException::class)
    private fun ensureSafeRoot(root: Path = ROOT) {
        val configDir = FabricLoader.getInstance().configDir.toAbsolutePath().normalize()
        val checkedRoot = root.toAbsolutePath().normalize()
        val exports = checkedRoot.resolve("exports")
        val configGuard = ConfigPathGuard.checkedDirectory(configDir)
        ConfigPathGuard.rejectReparseChain(checkedRoot)
        if (Files.exists(checkedRoot, LinkOption.NOFOLLOW_LINKS)) {
            ConfigPathGuard.checkedDirectory(checkedRoot)
            ConfigPathGuard.assertContained(configGuard, checkedRoot)
        } else {
            return
        }
        ConfigPathGuard.rejectReparseChain(exports)
        if (Files.exists(exports, LinkOption.NOFOLLOW_LINKS)) {
            val rootGuard = ConfigPathGuard.checkedDirectory(checkedRoot)
            ConfigPathGuard.assertContained(rootGuard, exports)
            if (!ConfigPathGuard.readExisting(exports).isDirectory) throw IOException("The exports path is not a directory.")
        }
    }

    /** Export follows the codec allowlist and excludes stores that belong to this client only. */
    private fun safePersistentPath(path: String): Boolean = ConfigProfileCodec.isTransferablePath(path)

    private fun safeProfileName(requested: String?): String {
        var value = (requested ?: "").trim { it <= ' ' }.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        while (value.startsWith(".")) value = value.substring(1)
        if (isJavaBlank(value)) value = "geileraddons-${LocalDateTime.now().format(STAMP)}"
        return if (value.length > 64) value.substring(0, 64) else value
    }

    private fun isJavaBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    private fun uniqueArchiveName(requested: String): String = safeProfileName(requested)

    private fun intValue(obj: JsonObject, key: String): Int = try {
        obj.get(key).asInt
    } catch (_: RuntimeException) {
        -1
    }

    @Throws(IOException::class)
    private fun moveAtomic(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun message(error: Throwable): String {
        var cause = error
        while (cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
