package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.gui.BlockPickerScreen
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.SettingGroup
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.EnumMap
import java.util.HashMap
import java.util.Locale
import java.util.Objects

/** Projects selected door glass into the local client world while preserving server block states. */
class DungeonDoorBlockReplacementModule private constructor(settings: Settings) : Module(
    "Glass Doors",
    "Shows selected glass for dungeon door blocks in the local client world.",
    Category.F7,
    false,
    settings.start,
    settings.wither,
    settings.blood,
    // Retain this persisted legacy setting key, but it is no longer shown or read as a selector.
    settings.useMortStartDetection,
    settings.startReplacement,
    settings.witherReplacement,
    settings.bloodReplacement,
    ModuleAction(
        "Show Replacement Status",
        "Show detected doors, eligible sources, local glass overlays, writes, restores, and failures.",
        Runnable { INSTANCE.reportReplacementStatus() },
    ),
) {
    private val start = settings.start
    private val wither = settings.wither
    private val blood = settings.blood
    private val startReplacement = settings.startReplacement
    private val witherReplacement = settings.witherReplacement
    private val bloodReplacement = settings.bloodReplacement

    @Volatile
    private var statusSummary = ""

    @Volatile
    private var statusStageSignature = ""

    private var startSearchSummary = "START SEARCH unavailable"
    private var diagnosticTick = 0L
    private var lastLoggedSummary: String? = null
    private var lastLoggedLayout: String? = null
    private var lastLoggedStageSignature: String? = null
    private var lastAutomaticBatchTick = Long.MIN_VALUE
    private var pendingChangeSnapshots = 0
    private var identityLogged = false
    private var diagnosticLevel: ClientLevel? = null
    private var dungeonSessionActive = false
    private var lastMortSpawnDiagnosticTick = Long.MIN_VALUE
    private var mortSpawnDiagnosticSummary = "not-sampled"
    private var lastMortSpawnReference: DungeonDoorTracker.SpawnReference? = null

    override fun groups(): List<SettingGroup> = listOf(
        SettingGroup("Door Types", start, wither, blood),
        SettingGroup.folded(
            "Replacement Blocks",
            replacementPicker("Start Door", startReplacement),
            replacementPicker("Wither Door", witherReplacement),
            replacementPicker("Blood Door", bloodReplacement),
        ),
    )

    private fun replacementPicker(doorLabel: String, setting: ChoiceSetting): ModuleAction =
        ModuleAction(
            doorLabel + " Replacement: " + displayBlockId(setting.value()),
            "Choose a registered block for this door type.",
            Runnable { openPicker(setting) },
        )

    private fun displayBlockId(value: String?): String? =
        if (value == null || value.contains(":")) value else "minecraft:" + value

    /** Keep the former module key so existing door choices and enabled state remain saved. */
    override fun configName(): String = "Dungeon Door Block Glass"

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    /** Called on the client thread after the shared DungeonDoorTracker update. */
    fun tick() {
        val client = Minecraft.getInstance()
        val enabled = isEnabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), enabled)
        val level = client.level
        diagnosticTick = level?.gameTime ?: diagnosticTick
        if (diagnosticLevel !== level) {
            if (dungeonSessionActive && diagnosticLevel != null) {
                DungeonDoorBlockOverlayController.restoreAndClear(diagnosticLevel)
                GeilerAddonsLog.write(
                    Category.F7,
                    name(),
                    diagnosticTick,
                    "DUNGEON SESSION ENDED reason=client-world-changed; restored owned projections and cleared retained masks",
                )
            }
            dungeonSessionActive = false
            if (pendingChangeSnapshots > 0) {
                DungeonDoorBlockOverlayController.recordCoalescedDetailSuppressions(
                    level,
                    pendingChangeSnapshots.toLong(),
                )
            }
            diagnosticLevel = level
            lastLoggedSummary = null
            lastLoggedLayout = null
            lastLoggedStageSignature = null
            lastAutomaticBatchTick = Long.MIN_VALUE
            lastMortSpawnDiagnosticTick = Long.MIN_VALUE
            mortSpawnDiagnosticSummary = "not-sampled"
            lastMortSpawnReference = null
            pendingChangeSnapshots = 0
        }
        if (level == null) {
            DungeonDoorBlockOverlayController.restoreAndClear(null)
            dungeonSessionActive = false
            refreshStatus(emptyMap(), false, null)
            logAutomaticStatus(false, "", null)
            return
        }

        val inDungeon = DungeonContextTracker.inDungeon()
        if (!inDungeon && dungeonSessionActive) {
            DungeonDoorBlockOverlayController.restoreAndClear(level)
            GeilerAddonsLog.write(
                Category.F7,
                name(),
                diagnosticTick,
                "DUNGEON SESSION ENDED reason=context-exited; restored owned projections and cleared retained masks",
            )
            dungeonSessionActive = false
        } else if (inDungeon) {
            dungeonSessionActive = true
        }

        val doors: List<DungeonDoorTracker.Door> =
            if (inDungeon) DungeonDoorTracker.doors() else emptyList()
        val detected = detectedDoorCounts(doors)
        val active = enabled && inDungeon
        val desired = HashMap<Long, DungeonDoorBlockOverlayController.Projection>()
        val eligible = HashMap<Long, DungeonDoorBlockReplacementRules.DoorKind>()
        if (active && start.value()) {
            collectStartTargets(level, doors, desired, eligible)
        } else {
            startSearchSummary = startSearchSummary(level)
        }
        startSearchSummary = withStartDiagnostics(startSearchSummary, level)
        if (active) {
            for (door in doors) {
                val kind = doorKind(door.type()) ?: continue
                if (
                    kind != DungeonDoorBlockReplacementRules.DoorKind.WITHER &&
                    kind != DungeonDoorBlockReplacementRules.DoorKind.BLOOD
                ) {
                    continue
                }
                val typeEnabled = if (kind == DungeonDoorBlockReplacementRules.DoorKind.WITHER) {
                    wither.value()
                } else {
                    blood.value()
                }
                if (!typeEnabled) continue
                val glass = replacementState(kind)
                if (glass == null) continue
                val retained = DungeonDoorBlockOverlayController.retainedPositions(level, kind)
                for (position in door.sourcePositions()) {
                    if (!level.hasChunkAt(position)) continue
                    val source = DungeonDoorBlockOverlayController.authoritativeState(level, position)
                        ?: continue
                    val matchingSource =
                        if (kind == DungeonDoorBlockReplacementRules.DoorKind.WITHER) {
                            source.`is`(Blocks.COAL_BLOCK)
                        } else {
                            source.`is`(Blocks.DYED_TERRACOTTA.pick(DyeColor.RED))
                        }
                    var selected = retained.contains(position.asLong())
                    if (matchingSource) {
                        // Java's boolean |= evaluates the right side even when selected is already true.
                        selected = selected or DungeonDoorBlockOverlayController.rememberDoorSource(
                            level,
                            kind,
                            position,
                        )
                    }
                    // A confirmed selection survives Barrier and other non-air updates. Authoritative
                    // Air retires it, and a stale shared door record cannot select it again.
                    if (selected && !source.isAir()) eligible[position.asLong()] = kind
                }
            }

            if (wither.value()) {
                addRetainedTargets(
                    level,
                    desired,
                    DungeonDoorBlockReplacementRules.DoorKind.WITHER,
                    replacementState(DungeonDoorBlockReplacementRules.DoorKind.WITHER),
                )
            }
            if (blood.value()) {
                addRetainedTargets(
                    level,
                    desired,
                    DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
                    replacementState(DungeonDoorBlockReplacementRules.DoorKind.BLOOD),
                )
            }
        }

        DungeonDoorBlockOverlayController.reconcile(level, desired)
        val stats = DungeonDoorBlockOverlayController.stats(level)
        refreshStatus(detected, active, stats, eligible)
        logAutomaticStatus(enabled, targetLayoutSummary(doors), level)
    }

    private fun collectStartTargets(
        level: ClientLevel,
        doors: List<DungeonDoorTracker.Door>,
        desired: MutableMap<Long, DungeonDoorBlockOverlayController.Projection>,
        eligible: MutableMap<Long, DungeonDoorBlockReplacementRules.DoorKind>,
    ) {
        val glass = replacementState(DungeonDoorBlockReplacementRules.DoorKind.START)
        val retained = DungeonDoorBlockOverlayController.retainedPositions(
            level,
            DungeonDoorBlockReplacementRules.DoorKind.START,
        )
        val sourceCounts = java.util.TreeMap<String, Int>()
        var startRecords = 0
        var loaded = 0
        var selected = 0
        var selectedAir = 0
        var selectedNonAir = 0
        val recordEvidence = ArrayList<String>()
        for (door in doors) {
            if (door.type() != DungeonDoorTracker.Type.START) continue
            startRecords++
            for (position in door.sourcePositions()) {
                if (!level.hasChunkAt(position)) continue
                loaded++
                val packed = position.asLong()
                val source = DungeonDoorBlockOverlayController.authoritativeState(level, position)
                    ?: continue
                val sourceName = sourceId(source)
                sourceCounts.merge(sourceName, 1) { previous, _ -> previous + 1 }
                val sourceMaterial = DungeonDoorBlockReplacementRules.isStartSelectionSource(surface(source))
                var selectedMask = retained.contains(packed)
                if (!selectedMask && sourceMaterial) {
                    selectedMask = DungeonDoorBlockOverlayController.rememberStartSource(level, position, source)
                }
                if (!selectedMask) continue
                selected++
                if (source.isAir()) {
                    selectedAir++
                    continue
                }
                selectedNonAir++
                eligible[packed] = DungeonDoorBlockReplacementRules.DoorKind.START
            }
            if (recordEvidence.size < 4) {
                recordEvidence.add(
                    "cells=" + door.firstCell() + "-" + door.secondCell() +
                        " bounds=" + door.x() + "," + door.y() + "," + door.z() + "/" +
                        door.width() + "x" + door.height() + "x" + door.depth() +
                        " sources=" + door.sourcePositions().map { it.toShortString() },
                )
            }
        }
        if (glass != null) {
            addRetainedTargets(level, desired, DungeonDoorBlockReplacementRules.DoorKind.START, glass)
        }
        startSearchSummary =
            "SPAWN START active=true records=" + startRecords + " loadedSources=" + loaded +
                " selectedMasks=" + selected + " selectedNonair=" + selectedNonAir + " selectedAir=" + selectedAir +
                " materials=" + sourceCounts + " recordsEvidence=" + recordEvidence +
                "; " + DungeonDoorTracker.startDiagnosticsSummary()
    }

    private fun startSearchSummary(level: ClientLevel?): String {
        val retained = if (level == null) {
            0
        } else {
            DungeonDoorBlockOverlayController.retainedPositions(
                level,
                DungeonDoorBlockReplacementRules.DoorKind.START,
            ).size
        }
        return "SPAWN START active=false configured=" + start.value() + " retainedMasks=" + retained +
            " initialSources=[minecraft:chiseled_stone_bricks,minecraft:infested_chiseled_stone_bricks]" +
            "; " + DungeonDoorTracker.startDiagnosticsSummary()
    }

    private fun withStartDiagnostics(selectorSummary: String, level: ClientLevel?): String =
        selectorSummary + "; " + mortSpawnDiagnostics(level)

    /** Diagnostics-only loaded-entity query centered on the first loaded dungeon player position. */
    private fun mortSpawnDiagnostics(level: ClientLevel?): String {
        if (!isEnabled()) {
            lastMortSpawnReference = null
            lastMortSpawnDiagnosticTick = Long.MIN_VALUE
            mortSpawnDiagnosticSummary = "MortSpawnQuery=disabled"
            return mortSpawnDiagnosticSummary
        }
        if (!DungeonContextTracker.inDungeon()) {
            lastMortSpawnReference = null
            lastMortSpawnDiagnosticTick = Long.MIN_VALUE
            mortSpawnDiagnosticSummary = "MortSpawnQuery=not-ready(dungeon=false)"
            return mortSpawnDiagnosticSummary
        }
        val spawn = DungeonDoorTracker.firstLoadedPlayer()
            ?: return "MortSpawnQuery=waiting-for-first-loaded-player-position"
        if (level == null) return "MortSpawnQuery=unavailable(no-client-level)"
        val tick = level.gameTime
        if (!Objects.equals(spawn, lastMortSpawnReference)) {
            lastMortSpawnReference = spawn
            lastMortSpawnDiagnosticTick = Long.MIN_VALUE
            mortSpawnDiagnosticSummary = "not-sampled"
        }
        if (
            lastMortSpawnDiagnosticTick == Long.MIN_VALUE ||
            tick < lastMortSpawnDiagnosticTick ||
            tick - lastMortSpawnDiagnosticTick >= 20
        ) {
            val origin = Vec3(spawn.x, spawn.y, spawn.z)
            val bounds = AABB.ofSize(origin, 40.0, 12.0, 40.0)
            val entities = level.getEntities(
                EntityTypeTest.forClass(Entity::class.java),
                bounds,
            ) { entity -> entity.isAlive() && !entity.isRemoved() }
            val mortEvidence = ArrayList<String>()
            var mortCount = 0
            for (entity in entities) {
                val customName = entity.getCustomName()?.getString() ?: ""
                val displayName = entity.getName().getString()
                val customNameMatches = DungeonDoorBlockReplacementRules.isMortIdentity(customName)
                val displayNameMatches = DungeonDoorBlockReplacementRules.isMortIdentity(displayName)
                if (!customNameMatches && !displayNameMatches) continue
                mortCount++
                if (mortEvidence.size >= 4) continue
                val entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                val dx = entity.getX() - spawn.x
                val dy = entity.getY() - spawn.y
                val dz = entity.getZ() - spawn.z
                val identitySource = if (customNameMatches && displayNameMatches) {
                    "custom+display"
                } else if (customNameMatches) {
                    "custom"
                } else {
                    "display"
                }
                mortEvidence.add(
                    (entityType?.toString() ?: "unknown") + "#" + entity.getId() +
                        " identity=Mort/matched-by-" + identitySource +
                        " pos=" + entity.blockPosition().toShortString() +
                        formatMortSpawnOffset(spawn.yaw, entity.getYRot(), dx, dy, dz),
                )
            }
            val minX = kotlin.math.floor(origin.x - 20.0).toInt()
            val maxX = kotlin.math.ceil(origin.x + 20.0).toInt()
            val minY = kotlin.math.floor(origin.y - 6.0).toInt()
            val maxY = kotlin.math.ceil(origin.y + 6.0).toInt()
            val minZ = kotlin.math.floor(origin.z - 20.0).toInt()
            val maxZ = kotlin.math.ceil(origin.z + 20.0).toInt()
            mortSpawnDiagnosticSummary =
                "MortSpawnQuery=loaded-entities-only origin=" +
                    (spawn.blockPosition?.toShortString() ?: "unknown") +
                    " bounds=x[" + minX + ".." + maxX + "],y[" + minY + ".." + maxY +
                    "],z[" + minZ + ".." + maxZ + "] size=40x12x40" +
                    String.format(Locale.ROOT, " spawnYaw=%.1f", spawn.yaw) +
                    " foundEntities=" + entities.size + " exactMortMatches=" + mortCount +
                    " evidence=" + mortEvidence +
                    (if (mortCount > mortEvidence.size) {
                        " additionalMortMatches=" + (mortCount - mortEvidence.size)
                    } else {
                        ""
                    }) +
                    " comparisonHint=diagnostics-only(expected spawnForward≈14–15,dy≈-2 relative to captured spawn yaw/position; not a selector)"
            lastMortSpawnDiagnosticTick = tick
        }
        return mortSpawnDiagnosticSummary
    }

    override fun onEnable() {
        val client = Minecraft.getInstance()
        client.level?.let { diagnosticTick = it.gameTime }
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        GeilerAddonsLog.write(Category.F7, name(), diagnosticTick, "MODULE ENABLED")
        lastLoggedSummary = null
        lastLoggedLayout = null
        lastLoggedStageSignature = null
        lastAutomaticBatchTick = Long.MIN_VALUE
        pendingChangeSnapshots = 0
        identityLogged = false
    }

    override fun onDisable() {
        val client = Minecraft.getInstance()
        val restore = Runnable {
            if (!isEnabled()) {
                var droppedSnapshots = 0L
                val level = client.level
                if (level != null) {
                    diagnosticTick = level.gameTime
                    if (pendingChangeSnapshots > 0) {
                        if (
                            lastAutomaticBatchTick == Long.MIN_VALUE ||
                            diagnosticTick - lastAutomaticBatchTick >= CHANGE_BATCH_COOLDOWN_TICKS
                        ) {
                            emitChangeBatch(level, lastLoggedLayout, true)
                        } else {
                            droppedSnapshots = pendingChangeSnapshots.toLong()
                            DungeonDoorBlockOverlayController.recordCoalescedDetailSuppressions(
                                level,
                                droppedSnapshots,
                            )
                            pendingChangeSnapshots = 0
                        }
                    }
                    DungeonDoorBlockOverlayController.reconcile(level, emptyMap())
                }
                GeilerAddonsLog.write(
                    Category.F7,
                    name(),
                    diagnosticTick,
                    "MODULE DISABLED; restoring owned local projections; coalesced snapshots suppressed=" +
                        droppedSnapshots + "; detail logs suppressed=" +
                        DungeonDoorBlockOverlayController.suppressedDetailLogCount(level),
                )
                GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
            }
        }
        if (client.isSameThread()) {
            restore.run()
        } else {
            client.execute(restore)
        }
        lastLoggedSummary = null
    }

    private fun addRetainedTargets(
        level: ClientLevel,
        desired: MutableMap<Long, DungeonDoorBlockOverlayController.Projection>,
        kind: DungeonDoorBlockReplacementRules.DoorKind,
        glass: BlockState?,
    ) {
        if (glass == null) return
        for (packed in DungeonDoorBlockOverlayController.retainedPositions(level, kind)) {
            desired[packed] = DungeonDoorBlockOverlayController.Projection(kind, glass)
        }
    }

    private fun refreshStatus(
        detected: Map<DungeonDoorBlockReplacementRules.DoorKind, Int>?,
        active: Boolean,
        level: ClientLevel?,
    ) {
        refreshStatus(detected, active, DungeonDoorBlockOverlayController.stats(level), emptyMap())
    }

    private fun refreshStatus(
        detected: Map<DungeonDoorBlockReplacementRules.DoorKind, Int>?,
        active: Boolean,
        stats: Map<DungeonDoorBlockReplacementRules.DoorKind, DungeonDoorBlockOverlayController.TypeStats>?,
        eligible: Map<Long, DungeonDoorBlockReplacementRules.DoorKind>?,
    ) {
        val safeDetected = detected ?: emptyMap()
        val safeStats = stats ?: emptyMap()
        val eligibleCounts = EnumMap<DungeonDoorBlockReplacementRules.DoorKind, Int>(
            DungeonDoorBlockReplacementRules.DoorKind::class.java,
        )
        val stages = EnumMap<
            DungeonDoorBlockReplacementRules.DoorKind,
            DungeonDoorBlockReplacementRules.DoorStageCounts,
        >(DungeonDoorBlockReplacementRules.DoorKind::class.java)
        for (kind in REPLACEMENT_KINDS) eligibleCounts[kind] = 0
        eligible?.values?.forEach { kind -> eligibleCounts.merge(kind, 1) { previous, _ -> previous + 1 } }
        val normalizedStats = EnumMap<
            DungeonDoorBlockReplacementRules.DoorKind,
            DungeonDoorBlockOverlayController.TypeStats,
        >(DungeonDoorBlockReplacementRules.DoorKind::class.java)
        for (kind in REPLACEMENT_KINDS) {
            val current = safeStats.getOrDefault(
                kind,
                DungeonDoorBlockOverlayController.TypeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            )
            val detectedCount = safeDetected.getOrDefault(kind, 0)
            stages[kind] = DungeonDoorBlockReplacementRules.DoorStageCounts(
                detectedCount,
                eligibleCounts[kind] ?: 0,
                current.active(),
            )
            normalizedStats[kind] = current
        }
        statusSummary = formatStatus(stages, normalizedStats)
        statusStageSignature = formatStageSignature(stages, normalizedStats)
    }

    private fun formatStatus(
        stages: Map<
            DungeonDoorBlockReplacementRules.DoorKind,
            DungeonDoorBlockReplacementRules.DoorStageCounts,
        >,
        stats: Map<DungeonDoorBlockReplacementRules.DoorKind, DungeonDoorBlockOverlayController.TypeStats>,
    ): String {
        val result = StringBuilder()
        for (kind in REPLACEMENT_KINDS) {
            if (result.isNotEmpty()) result.append("; ")
            val stage = stages[kind]!!
            val state = stats[kind]!!
            result.append(kind.name)
                .append(" doors=").append(stage.detectedDoors)
                .append(", eligible=").append(stage.eligibleSourceBlocks)
                .append(", published=").append(stage.publishedReplacements)
                .append(", loaded=").append(state.loaded()).append('/').append(state.targets())
                .append(", source air=").append(state.sourceAir())
                .append(", source nonair=").append(state.sourceNonAir())
                .append(", active=").append(state.active())
                .append(", writes=").append(state.writes())
                .append(", restores=").append(state.restores())
                .append(", failures=").append(state.failures())
                .append(", miningReads=").append(state.miningReads())
                .append(", miningSourceReturns=").append(state.miningSourceReturns())
                .append(", miningFallbacks=").append(state.miningFallbacks())
                .append(", miningOwnershipMismatches=").append(state.miningOwnershipMismatches())
        }
        if (result.isNotEmpty()) result.append("; ")
        result.append(startSearchSummary)
        return result.toString()
    }

    private fun formatStageSignature(
        stages: Map<
            DungeonDoorBlockReplacementRules.DoorKind,
            DungeonDoorBlockReplacementRules.DoorStageCounts,
        >,
        stats: Map<DungeonDoorBlockReplacementRules.DoorKind, DungeonDoorBlockOverlayController.TypeStats>,
    ): String {
        val result = StringBuilder()
        for (kind in REPLACEMENT_KINDS) {
            if (result.isNotEmpty()) result.append(';')
            val stage = stages[kind]!!
            val state = stats[kind]!!
            result.append(kind).append(':')
                .append(stage.detectedDoors).append(',')
                .append(stage.eligibleSourceBlocks).append(',')
                .append(stage.publishedReplacements)
                .append(',').append(state.loaded()).append('/').append(state.targets())
                .append(',').append(state.sourceAir()).append(',').append(state.sourceNonAir())
                .append(',').append(state.active()).append(',').append(state.miningReads())
                .append(',').append(state.miningSourceReturns()).append(',').append(state.miningFallbacks())
                .append(',').append(state.miningOwnershipMismatches())
        }
        result.append(";startSearch=").append(startSearchSummary)
        return result.toString()
    }

    private fun logAutomaticStatus(enabled: Boolean, layout: String, level: ClientLevel?) {
        if (!enabled) return
        if (!identityLogged) {
            val identityTick = diagnosticTick
            val rendererMods = "sodium=" + modVersion("sodium") +
                " nvidium=" + modVersion("nvidium") + " iris=" + modVersion("iris")
            val identityWorker = Thread(
                {
                    GeilerAddonsLog.write(
                        Category.F7,
                        name(),
                        identityTick,
                        "RUNTIME artifact=" + artifactIdentity() + " " + rendererMods,
                    )
                },
                "Glass Doors artifact identity",
            )
            identityWorker.isDaemon = true
            identityWorker.start()
            identityLogged = true
        }
        val stageSignature =
            "dungeon=" + DungeonContextTracker.inDungeon() + " enabled=" + isEnabled() +
                " " + statusStageSignature
        if (stageSignature != lastLoggedStageSignature || layout != lastLoggedLayout) {
            pendingChangeSnapshots++
            lastLoggedStageSignature = stageSignature
            lastLoggedLayout = layout
        }
        if (
            pendingChangeSnapshots > 0 &&
            (lastAutomaticBatchTick == Long.MIN_VALUE ||
                diagnosticTick - lastAutomaticBatchTick >= CHANGE_BATCH_COOLDOWN_TICKS)
        ) {
            emitChangeBatch(level, layout, false)
        } else if (
            pendingChangeSnapshots == 0 &&
            diagnosticTick % 100 == 0L &&
            (lastAutomaticBatchTick == Long.MIN_VALUE ||
                diagnosticTick - lastAutomaticBatchTick >= CHANGE_BATCH_COOLDOWN_TICKS)
        ) {
            val status = statusText(level)
            GeilerAddonsLog.write(Category.F7, name(), diagnosticTick, status)
            lastLoggedSummary = status
            lastAutomaticBatchTick = diagnosticTick
        }
    }

    private fun emitChangeBatch(level: ClientLevel?, layout: String?, forced: Boolean) {
        val snapshots = pendingChangeSnapshots
        if (snapshots <= 0) return
        if (snapshots > 1 && level != null) {
            DungeonDoorBlockOverlayController.recordCoalescedDetailSuppressions(level, snapshots - 1L)
        }
        val status = statusText(level)
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            diagnosticTick,
            "CHANGE BATCH coalescedSnapshots=" + snapshots + " forced=" + forced + " " +
                (layout ?: "TARGET LAYOUT unavailable") + "; " + status,
        )
        lastLoggedSummary = status
        lastAutomaticBatchTick = diagnosticTick
        pendingChangeSnapshots = 0
    }

    private fun statusText(level: ClientLevel?): String =
        "STATUS dungeon=" + DungeonContextTracker.inDungeon() + " enabled=" + isEnabled() + " " +
            statusSummary + "; detail logs suppressed=" +
            DungeonDoorBlockOverlayController.suppressedDetailLogCount(level)

    private fun targetLayoutSummary(doors: List<DungeonDoorTracker.Door>): String {
        val entries = ArrayList<String>()
        entries.add(
            startSearchSummary +
                " initialSources=[minecraft:chiseled_stone_bricks,minecraft:infested_chiseled_stone_bricks]",
        )
        for (door in doors) {
            val kind = doorKind(door.type())
            if (
                kind != DungeonDoorBlockReplacementRules.DoorKind.START &&
                kind != DungeonDoorBlockReplacementRules.DoorKind.WITHER &&
                kind != DungeonDoorBlockReplacementRules.DoorKind.BLOOD
            ) {
                continue
            }
            entries.add(
                kind.toString() + " cells=" + door.firstCell() + "-" + door.secondCell() +
                    " identity=" + door.identity() +
                    " bounds=x[" + door.x() + ".." + (door.x() + door.width() - 1) +
                    "],y[" + door.y() + ".." + (door.y() + door.height() - 1) +
                    "],z[" + door.z() + ".." + (door.z() + door.depth() - 1) + "]" +
                    " sourcePositions=" + door.sourcePositions().map { it.toShortString() } +
                    (if (door.sourceDiagnostics().isEmpty()) "" else " sourceVolume=" + door.sourceDiagnostics()),
            )
        }
        entries.sort()
        return "TARGET LAYOUT dungeon=" + DungeonContextTracker.inDungeon() +
            " detected=" + (entries.size - 1) + " " + entries.joinToString("; ")
    }

    private fun reportReplacementStatus() {
        val client = Minecraft.getInstance()
        val gui = client.gui ?: return
        gui.hud.getChat().addClientSystemMessage(
            Component.literal("[GeilerAddons] Glass Doors: " + statusText(client.level)),
        )
    }

    private fun openPicker(setting: ChoiceSetting) {
        val client = Minecraft.getInstance()
        client.gui.setScreen(
            BlockPickerScreen(client.gui.screen(), { id ->
                setting.setValue(id)
                ModConfig.markDirty()
            }),
        )
    }

    private fun replacementState(
        kind: DungeonDoorBlockReplacementRules.DoorKind,
    ): BlockState? {
        val setting = when (kind) {
            DungeonDoorBlockReplacementRules.DoorKind.START -> startReplacement
            DungeonDoorBlockReplacementRules.DoorKind.WITHER -> witherReplacement
            DungeonDoorBlockReplacementRules.DoorKind.BLOOD -> bloodReplacement
            DungeonDoorBlockReplacementRules.DoorKind.NORMAL,
            DungeonDoorBlockReplacementRules.DoorKind.GLASS -> null
        }
        val fallback = DungeonDoorBlockReplacementRules.defaultReplacement(kind)
        if (setting == null || fallback == null) return null
        var block = resolveBlock(setting.value())
        if (block == null) block = resolveBlock(fallback.blockPath())
        return block?.defaultBlockState()
    }

    private fun configuredGlass(
        kind: DungeonDoorBlockReplacementRules.DoorKind,
    ): DungeonDoorBlockReplacementRules.Glass? {
        val setting = when (kind) {
            DungeonDoorBlockReplacementRules.DoorKind.START -> startReplacement
            DungeonDoorBlockReplacementRules.DoorKind.WITHER -> witherReplacement
            DungeonDoorBlockReplacementRules.DoorKind.BLOOD -> bloodReplacement
            DungeonDoorBlockReplacementRules.DoorKind.NORMAL,
            DungeonDoorBlockReplacementRules.DoorKind.GLASS -> null
        }
        val fallback = DungeonDoorBlockReplacementRules.defaultReplacement(kind)
        return if (setting == null) {
            fallback
        } else {
            DungeonDoorBlockReplacementRules.Glass.fromBlockPath(setting.value(), fallback)
        }
    }

    private class Settings {
        val start = BooleanSetting("Start Door Glass", true)
        val wither = BooleanSetting("Wither Door Glass", true)
        val blood = BooleanSetting("Blood Door Glass", true)
        val useMortStartDetection = BooleanSetting("Use Mort-Based Start Detection", false)
        val startReplacement = replacementChoice(
            "Start Door Replacement",
            DungeonDoorBlockReplacementRules.defaultReplacement(
                DungeonDoorBlockReplacementRules.DoorKind.START,
            )!!.blockPath(),
        )
        val witherReplacement = replacementChoice(
            "Wither Door Replacement",
            DungeonDoorBlockReplacementRules.defaultReplacement(
                DungeonDoorBlockReplacementRules.DoorKind.WITHER,
            )!!.blockPath(),
        )
        val bloodReplacement = replacementChoice(
            "Blood Door Replacement",
            DungeonDoorBlockReplacementRules.defaultReplacement(
                DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
            )!!.blockPath(),
        )

        companion object {
            private val REGISTERED_BLOCK_CHOICES = registeredBlockChoices()

            private fun replacementChoice(name: String, defaultValue: String): ChoiceSetting =
                ChoiceSetting(name, defaultValue, *REGISTERED_BLOCK_CHOICES)

            private fun registeredBlockChoices(): Array<String> {
                val choices = ArrayList<String>()
                for (blockId in BlockPickerScreen.registeredBlockIds()) {
                    choices.add(blockId)
                    // Preserve legacy values saved before the picker stored namespaced registry IDs.
                    if (blockId.startsWith("minecraft:")) {
                        choices.add(blockId.substring("minecraft:".length))
                    }
                }
                return choices.toTypedArray()
            }
        }
    }

    companion object {
        private const val CHANGE_BATCH_COOLDOWN_TICKS = 20

        private val REPLACEMENT_KINDS = listOf(
            DungeonDoorBlockReplacementRules.DoorKind.START,
            DungeonDoorBlockReplacementRules.DoorKind.WITHER,
            DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
        )

        private val TERRACOTTA_BLOCKS = setOf(
            Blocks.TERRACOTTA,
            Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.MAGENTA),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.LIME),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.PINK),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.GRAY),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_GRAY),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.CYAN),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.PURPLE),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BROWN),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.GREEN),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.RED),
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BLACK),
        )

        @JvmField
        val INSTANCE = DungeonDoorBlockReplacementModule(Settings())

        /** Records a bounded command-driven Start-anchor event in this module's existing diagnostic file. */
        @JvmStatic
        fun recordStartDoorCaptureDiagnostic(level: ClientLevel?, details: String?) {
            if (details == null) return
            val module = INSTANCE
            val temporarilyEnabled = !module.isEnabled()
            if (temporarilyEnabled) {
                GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), true)
            }
            val tick = level?.gameTime ?: module.diagnosticTick
            val bounded = if (details.length <= 4096) details else details.substring(0, 4093) + "..."
            GeilerAddonsLog.write(Category.F7, module.name(), tick, "STARTDOOR CAPTURE " + bounded)
            if (temporarilyEnabled) {
                GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), false)
            }
        }

        private fun detectedDoorCounts(
            doors: List<DungeonDoorTracker.Door>,
        ): Map<DungeonDoorBlockReplacementRules.DoorKind, Int> {
            val counts = EnumMap<DungeonDoorBlockReplacementRules.DoorKind, Int>(
                DungeonDoorBlockReplacementRules.DoorKind::class.java,
            )
            for (kind in REPLACEMENT_KINDS) counts[kind] = 0
            for (door in doors) {
                val kind = when (door.type()) {
                    DungeonDoorTracker.Type.START -> DungeonDoorBlockReplacementRules.DoorKind.START
                    DungeonDoorTracker.Type.WITHER -> DungeonDoorBlockReplacementRules.DoorKind.WITHER
                    DungeonDoorTracker.Type.BLOOD -> DungeonDoorBlockReplacementRules.DoorKind.BLOOD
                    DungeonDoorTracker.Type.NORMAL -> DungeonDoorBlockReplacementRules.DoorKind.NORMAL
                    DungeonDoorTracker.Type.GLASS -> DungeonDoorBlockReplacementRules.DoorKind.GLASS
                    null -> null
                }
                if (kind != null && counts.containsKey(kind)) {
                    counts.merge(kind, 1) { previous, _ -> previous + 1 }
                }
            }
            return counts.toMap()
        }

        private fun artifactIdentity(): String {
            val version = modVersion("geileraddons")
            return try {
                val source = DungeonDoorBlockReplacementModule::class.java.protectionDomain.codeSource
                    ?: return version + " origin=unknown artifact=unknown sha256=unavailable"
                val path = Path.of(source.location.toURI())
                val artifact = path.fileName?.toString() ?: "unknown"
                if (Files.isDirectory(path)) {
                    return version + " origin=development-directory artifact=" + artifact +
                        " sha256=unavailable(classes directory)"
                }
                if (!Files.isRegularFile(path)) {
                    return version + " origin=unknown artifact=" + artifact +
                        " sha256=unavailable(not a regular file)"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (count >= 0) {
                        digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                version + " origin=jar artifact=" + artifact + " sha256=" +
                    java.util.HexFormat.of().formatHex(digest.digest())
            } catch (error: Exception) {
                version + " origin=unknown artifact=unavailable sha256=unavailable(" +
                    error.javaClass.simpleName + ")"
            }
        }

        private fun modVersion(id: String): String =
            FabricLoader.getInstance().getModContainer(id)
                .map { it.metadata.version.friendlyString }
                .orElse("absent")

        private fun formatMortSpawnOffset(
            spawnYaw: Float,
            mortYaw: Float,
            dx: Double,
            dy: Double,
            dz: Double,
        ): String {
            val yawRadians = Math.toRadians(spawnYaw.toDouble())
            val forwardX = -Math.sin(yawRadians)
            val forwardZ = Math.cos(yawRadians)
            val forward = dx * forwardX + dz * forwardZ
            val lateral = dx * -forwardZ + dz * forwardX
            val horizontalDistance = Math.hypot(dx, dz)
            val distance = Math.sqrt(dx * dx + dy * dy + dz * dz)
            return String.format(
                Locale.ROOT,
                " yaw=%.1f offset=(%.2f,%.2f,%.2f) spawnForward=%.2f spawnLateral=%.2f horizontalDistance=%.2f distance3d=%.2f",
                mortYaw,
                dx,
                dy,
                dz,
                forward,
                lateral,
                horizontalDistance,
                distance,
            )
        }

        private fun resolveBlock(value: String?): Block? {
            if (value == null || value.isBlank()) return null
            val idValue = if (value.contains(":")) value else "minecraft:" + value
            val id = Identifier.tryParse(idValue) ?: return null
            val block = BuiltInRegistries.BLOCK.getValue(id)
            return if (
                block == Blocks.AIR ||
                block == Blocks.CAVE_AIR ||
                block == Blocks.VOID_AIR
            ) {
                null
            } else {
                block
            }
        }

        private fun surface(state: BlockState?): DungeonDoorBlockReplacementRules.Surface {
            if (state == null) return DungeonDoorBlockReplacementRules.Surface.OTHER
            if (state.`is`(Blocks.COAL_BLOCK)) return DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK
            if (state.`is`(Blocks.DYED_TERRACOTTA.pick(DyeColor.RED))) {
                return DungeonDoorBlockReplacementRules.Surface.RED_TERRACOTTA
            }
            if (state.`is`(Blocks.CHISELED_STONE_BRICKS)) {
                return DungeonDoorBlockReplacementRules.Surface.CHISELED_STONE_BRICKS
            }
            if (state.`is`(Blocks.INFESTED_CHISELED_STONE_BRICKS)) {
                return DungeonDoorBlockReplacementRules.Surface.INFESTED_CHISELED_STONE_BRICKS
            }
            return if (TERRACOTTA_BLOCKS.contains(state.getBlock())) {
                DungeonDoorBlockReplacementRules.Surface.TERRACOTTA
            } else {
                DungeonDoorBlockReplacementRules.Surface.OTHER
            }
        }

        private fun sourceId(state: BlockState): String {
            val id = BuiltInRegistries.BLOCK.getKey(state.getBlock())
            return id?.toString() ?: "unknown"
        }

        private fun doorKind(
            type: DungeonDoorTracker.Type?,
        ): DungeonDoorBlockReplacementRules.DoorKind? = when (type) {
            null -> null
            DungeonDoorTracker.Type.START -> DungeonDoorBlockReplacementRules.DoorKind.START
            DungeonDoorTracker.Type.WITHER -> DungeonDoorBlockReplacementRules.DoorKind.WITHER
            DungeonDoorTracker.Type.BLOOD -> DungeonDoorBlockReplacementRules.DoorKind.BLOOD
            DungeonDoorTracker.Type.NORMAL -> DungeonDoorBlockReplacementRules.DoorKind.NORMAL
            DungeonDoorTracker.Type.GLASS -> DungeonDoorBlockReplacementRules.DoorKind.GLASS
        }
    }
}
