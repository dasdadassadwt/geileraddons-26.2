package geiler.addons.client.scoreboard

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.server.packs.repository.PackRepository
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Supplier

/** Local font catalog. User fonts are copied only into this profile's config and resource-pack folders. */
object ScoreboardFontService {
    @JvmRecord
    data class FontChoice(val id: String, val label: String, val imported: Boolean)

    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private const val PACK_FOLDER = "geileraddons-scoreboard-fonts"
    private const val PACK_ID = "file/$PACK_FOLDER"
    private const val PACK_OWNER = "GeilerAddons generated scoreboard font pack v1"
    private const val FONT_TREE_OWNER = "GeilerAddons generated scoreboard font namespace v1"
    private const val NAMESPACE = "geileraddons_user_fonts"
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private val USER_FONT_DIRECTORY: Path = FabricLoader.getInstance().configDir
        .resolve("geileraddons")
        .resolve("scoreboard")
        .resolve("fonts")
    private val IO: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons-scoreboard-fonts").apply { isDaemon = true }
    }

    /** Built-in choices are supplied by the mod; imported fonts come from this client profile. */
    @JvmStatic
    fun choices(): List<FontChoice> {
        val choices = ArrayList<FontChoice>()
        choices.add(FontChoice("minecraft:default", "Minecraft Default", false))
        choices.add(FontChoice("minecraft:uniform", "Minecraft Uniform", false))
        choices.add(FontChoice("geileraddons:scoreboard/noto_sans", "Noto Sans", false))
        choices.add(FontChoice("geileraddons:scoreboard/atkinson_hyperlegible_next", "Atkinson Hyperlegible Next", false))
        choices.add(FontChoice("geileraddons:scoreboard/ibm_plex_sans", "IBM Plex Sans", false))
        choices.add(FontChoice("geileraddons:scoreboard/roboto_slab", "Roboto Slab", false))
        choices.add(FontChoice("geileraddons:scoreboard/roboto_mono", "Roboto Mono", false))
        try {
            if (!Files.isDirectory(USER_FONT_DIRECTORY, LinkOption.NOFOLLOW_LINKS)) return java.util.List.copyOf(choices)
            Files.list(USER_FONT_DIRECTORY).use { paths ->
                paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                    .filter { path -> isSupportedFontFile(path) }
                    .sorted(Comparator.comparing<Path, String> { path ->
                        path.fileName.toString().lowercase(Locale.ROOT)
                    })
                    .forEach { path ->
                        val id = fileId(path)
                        choices.add(FontChoice("$NAMESPACE:scoreboard/$id", "Imported: ${stripExtension(path.fileName.toString())}", true))
                    }
            }
        } catch (_: IOException) {
        }
        return java.util.List.copyOf(choices)
    }

    /** Rebuilds the generated pack at startup when this profile already has imported fonts. */
    @JvmStatic
    fun initialize() {
        val imports = choices().filter { it.imported }
        val minecraft = Minecraft.getInstance()
        val resourcePackRoot = minecraft.resourcePackDirectory.resolve(PACK_FOLDER)
        if (imports.isEmpty() && !Files.exists(resourcePackRoot, LinkOption.NOFOLLOW_LINKS)) return
        IO.execute {
            try {
                writePackFiles(resourcePackRoot)
                minecraft.execute { refreshPack(minecraft, imports.isNotEmpty()) }
            } catch (_: IOException) {
                // Keep vanilla and bundled font choices available if a generated pack cannot be rebuilt.
            }
        }
    }

    /** Imports one local TTF/OTF file, then enables the profile-local resource pack. */
    @JvmStatic
    fun importFont(source: Path?): CompletableFuture<String> {
        val minecraft = Minecraft.getInstance()
        val resourcePackRoot = minecraft.resourcePackDirectory.resolve(PACK_FOLDER)
        return CompletableFuture.supplyAsync(
            Supplier {
                try {
                    val stored = copyIntoProfile(source)
                    writePackFiles(resourcePackRoot)
                    "Imported ${stripExtension(stored.fileName.toString())}"
                } catch (exception: IOException) {
                    throw java.util.concurrent.CompletionException(exception)
                }
            },
            IO,
        ).thenCompose { message ->
            val result = CompletableFuture<String>()
            minecraft.execute {
                refreshPack(minecraft, true).whenComplete { _, error ->
                    if (error != null) result.completeExceptionally(error)
                    else result.complete("$message. Select it in the Font button.")
                }
            }
            result
        }
    }

    private fun refreshPack(minecraft: Minecraft, enable: Boolean): CompletableFuture<Void> {
        try {
            val repository: PackRepository = minecraft.resourcePackRepository
            repository.reload()
            if (enable) {
                if (!repository.isAvailable(PACK_ID)) {
                    return CompletableFuture.failedFuture(IOException("The generated font resource pack is not available."))
                }
                if (!repository.selectedIds.contains(PACK_ID) && !repository.addPack(PACK_ID)) {
                    return CompletableFuture.failedFuture(IOException("The generated font resource pack could not be enabled."))
                }
                return minecraft.reloadResourcePacks()
            }
            if (repository.selectedIds.contains(PACK_ID) && repository.removePack(PACK_ID)) {
                return minecraft.reloadResourcePacks()
            }
            return CompletableFuture.completedFuture(null)
        } catch (exception: RuntimeException) {
            return CompletableFuture.failedFuture(exception)
        }
    }

    @Throws(IOException::class)
    private fun copyIntoProfile(source: Path?): Path {
        if (source == null) throw IOException("Enter the full path to a .ttf or .otf font file.")
        val absolute = source.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The selected path is not a regular font file.")
        }
        if (!isSupportedFontFile(absolute)) throw IOException("Only .ttf and .otf font files are supported.")
        val size = Files.size(absolute)
        if (size < 1 || size > MAX_FONT_BYTES) throw IOException("Font files must be at most 20 MiB.")
        ensureUserFontDirectory()
        val base = fileId(absolute)
        val extension = extension(absolute)
        var candidate = base
        var destination = USER_FONT_DIRECTORY.resolve(candidate + extension)
        var suffix = 2
        while (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || fontIdInUse(candidate)) {
            val suffixText = "_${suffix++}"
            candidate = base.substring(0, maxOf(1, minOf(base.length, 48 - suffixText.length))) + suffixText
            destination = USER_FONT_DIRECTORY.resolve(candidate + extension)
        }
        Files.copy(absolute, destination, StandardCopyOption.COPY_ATTRIBUTES)
        return destination
    }

    @Throws(IOException::class)
    private fun fontIdInUse(candidate: String): Boolean {
        if (!Files.isDirectory(USER_FONT_DIRECTORY, LinkOption.NOFOLLOW_LINKS)) return false
        Files.list(USER_FONT_DIRECTORY).use { fonts ->
            return fonts.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .filter { path -> isSupportedFontFile(path) }
                .anyMatch { path -> fileId(path) == candidate }
        }
    }

    @Throws(IOException::class)
    private fun writePackFiles(packRoot: Path) {
        val normalizedPackRoot = packRoot.toAbsolutePath().normalize()
        val expectedParent = normalizedPackRoot.parent
        if (!normalizedPackRoot.startsWith(expectedParent) || normalizedPackRoot == expectedParent) {
            throw IOException("Generated font pack path is outside the resource-pack folder.")
        }
        ensureUserFontDirectory()
        if (Files.exists(normalizedPackRoot, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isDirectory(normalizedPackRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(normalizedPackRoot.resolve(".geileraddons-owner"), LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IOException("A resource pack already uses the GeilerAddons font pack name; rename it before importing.")
        }
        Files.createDirectories(normalizedPackRoot)
        val marker = normalizedPackRoot.resolve(".geileraddons-owner")
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(marker, PACK_OWNER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else if (PACK_OWNER != Files.readString(marker, StandardCharsets.UTF_8)) {
            throw IOException("The generated font pack owner marker does not match.")
        }
        val realPackRoot = normalizedPackRoot.toRealPath()
        val assetsRoot = realPackRoot.resolve("assets")
        createOwnedDirectory(assetsRoot, realPackRoot)
        val namespaceRoot = assetsRoot.resolve(NAMESPACE)
        createOwnedDirectory(namespaceRoot, realPackRoot)
        val fontResourceRoot = namespaceRoot.resolve("font")
        createOwnedDirectory(fontResourceRoot, realPackRoot)
        val imports = fontResourceRoot.resolve("imports")
        val scoreboard = fontResourceRoot.resolve("scoreboard")
        val fontTreeMarker = fontResourceRoot.resolve(".geileraddons-generated-fonts")
        if (Files.exists(fontTreeMarker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(fontTreeMarker, LinkOption.NOFOLLOW_LINKS)
                || FONT_TREE_OWNER != Files.readString(fontTreeMarker, StandardCharsets.UTF_8)
            ) {
                throw IOException("The generated font namespace owner marker does not match.")
            }
        } else {
            if (Files.exists(imports, LinkOption.NOFOLLOW_LINKS) || Files.exists(scoreboard, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("The font namespace already has content but no GeilerAddons owner marker.")
            }
            Files.writeString(
                fontTreeMarker,
                FONT_TREE_OWNER,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }
        createOwnedDirectory(imports, realPackRoot)
        createOwnedDirectory(scoreboard, realPackRoot)
        deleteTree(imports, realPackRoot)
        deleteTree(scoreboard, realPackRoot)
        Files.createDirectories(imports)
        Files.createDirectories(scoreboard)

        Files.list(USER_FONT_DIRECTORY).use { paths ->
            for (font in paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .filter { path -> isSupportedFontFile(path) }.toList()
            ) {
                val size = Files.size(font)
                if (size < 1 || size > MAX_FONT_BYTES) continue
                val id = fileId(font)
                val ext = extension(font)
                val fontDestination = imports.resolve(id + ext).normalize()
                if (!fontDestination.startsWith(realPackRoot)) throw IOException("Invalid generated font path.")
                Files.copy(font, fontDestination, StandardCopyOption.REPLACE_EXISTING)
                writeFontDefinition(scoreboard.resolve("$id.json"), "$NAMESPACE:font/imports/$id$ext")
            }
        }
        writePackMetadata(realPackRoot.resolve("pack.mcmeta"))
    }

    @Throws(IOException::class)
    private fun deleteTree(targetRoot: Path, realPackRoot: Path) {
        val normalized = targetRoot.toAbsolutePath().normalize()
        if (!normalized.startsWith(realPackRoot) || normalized == realPackRoot) {
            throw IOException("Refusing to remove data outside the generated font namespace.")
        }
        Files.walk(normalized).use { paths ->
            for (path in paths.sorted(Comparator.reverseOrder<Path>()).toList()) {
                val candidate = path.toAbsolutePath().normalize()
                if (!candidate.startsWith(normalized)) throw IOException("Generated font path escaped its namespace.")
                Files.deleteIfExists(candidate)
            }
        }
    }

    @Throws(IOException::class)
    private fun createOwnedDirectory(directory: Path, realPackRoot: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        if (!normalized.startsWith(realPackRoot) || normalized == realPackRoot) {
            throw IOException("Generated font folder escaped its managed pack.")
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw IOException("A generated font folder is not a normal directory.")
            }
            val realPath = normalized.toRealPath()
            if (!realPath.startsWith(realPackRoot) || realPath == realPackRoot) {
                throw IOException("Generated font folder resolves outside its managed pack.")
            }
        } else {
            Files.createDirectory(normalized)
        }
    }

    @Throws(IOException::class)
    private fun ensureUserFontDirectory() {
        val configDirectory = FabricLoader.getInstance().configDir.toAbsolutePath().normalize()
        val configRoot = configDirectory.toRealPath()
        val fontDirectory = USER_FONT_DIRECTORY.toAbsolutePath().normalize()
        if (!fontDirectory.startsWith(configDirectory) || fontDirectory == configDirectory) {
            throw IOException("The profile font folder is outside this Minecraft profile.")
        }
        var current = configDirectory
        for (segment in configDirectory.relativize(fontDirectory)) {
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("The profile font folder must contain only normal directories.")
                }
            } else {
                Files.createDirectory(current)
            }
            if (!current.toRealPath().startsWith(configRoot)) {
                throw IOException("The profile font folder resolves outside this Minecraft profile.")
            }
        }
    }

    @Throws(IOException::class)
    private fun writeFontDefinition(target: Path, fileId: String) {
        val provider = JsonObject()
        provider.addProperty("type", "ttf")
        provider.addProperty("file", fileId)
        provider.addProperty("size", 11.0f)
        provider.addProperty("oversample", 2.0f)
        val shift = JsonArray()
        shift.add(0)
        shift.add(0)
        val providers = JsonArray()
        providers.add(provider)
        val fallback = JsonObject()
        fallback.addProperty("type", "reference")
        fallback.addProperty("id", "minecraft:default")
        providers.add(fallback)
        val definition = JsonObject()
        definition.add("providers", providers)
        Files.writeString(
            target,
            GSON.toJson(definition),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    @Throws(IOException::class)
    private fun writePackMetadata(target: Path) {
        val format: PackFormat = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES)
        val pack = JsonObject()
        pack.addProperty("description", "GeilerAddons scoreboard fonts imported for this client profile.")
        pack.add(
            "pack_format",
            PackFormat.TOP_CODEC.encodeStart(JsonOps.INSTANCE, format)
                .result()
                .orElseGet { com.google.gson.JsonPrimitive(format.major()) },
        )
        val root = JsonObject()
        root.add("pack", pack)
        Files.writeString(
            target,
            GSON.toJson(root),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun isSupportedFontFile(path: Path): Boolean {
        val extension = extension(path)
        return extension == ".ttf" || extension == ".otf"
    }

    private fun extension(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot).lowercase(Locale.ROOT)
    }

    private fun fileId(path: Path): String {
        var base = stripExtension(path.fileName.toString()).lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .replace(Regex("^_+|_+$"), "")
        if (base.isBlank()) base = "imported_font"
        return if (base.length > 48) base.substring(0, 48) else base
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
