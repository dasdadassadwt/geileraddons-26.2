package geiler.addons.client.scoreboard

import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Optional

/** Atomic persistence kept separate from the shared module configuration registry. */
object ScoreboardLayoutStore {
    private val MAX_LAYOUT_BYTES = ScoreboardLayoutCodec.MAX_JSON_CHARS * 4
    private val DIRECTORY: Path = FabricLoader.getInstance().configDir
        .resolve("geileraddons")
        .resolve("scoreboard")
    private val FILE: Path = DIRECTORY.resolve("layout.json")
    private val TEMP: Path = DIRECTORY.resolve("layout.json.tmp")

    @JvmStatic
    fun load(): ScoreboardLayout = loadForReload().orElseGet { ScoreboardLayout() }

    /**
     * Reads a layout for an in-place profile reload. A missing file means a profile without a layout
     * and selects bundled defaults; an unreadable or malformed existing file is rejected so callers
     * can keep the current in-memory layout.
     */
    @JvmStatic
    fun loadForReload(): Optional<ScoreboardLayout> = loadForReload(FILE)

    /** Reads one layout file; the path overload keeps persistence checks isolated from the active profile. */
    @JvmStatic
    fun loadForReload(file: Path): Optional<ScoreboardLayout> {
        val target = file.toAbsolutePath().normalize()
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.of(ScoreboardLayout())
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty()
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > MAX_LAYOUT_BYTES) {
                return Optional.empty()
            }
            val bytes = Files.newInputStream(target).use { input -> input.readNBytes(MAX_LAYOUT_BYTES + 1) }
            if (bytes.size > MAX_LAYOUT_BYTES) return Optional.empty()
            val json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            return ScoreboardLayoutCodec.decode(json)
        } catch (_: IOException) {
            return Optional.empty()
        }
    }

    @JvmStatic
    fun save(layout: ScoreboardLayout?): Boolean = ScoreboardLayoutCodec.encode(layout).let(::saveJson)

    /** Persists a layout to an isolated path using the same atomic write as the active profile store. */
    @JvmStatic
    fun save(layout: ScoreboardLayout?, file: Path): Boolean = saveJson(ScoreboardLayoutCodec.encode(layout), file)

    /** Writes an already-serialized client-thread snapshot without touching its mutable model. */
    @JvmStatic
    fun saveJson(json: String?): Boolean = saveJson(json, DIRECTORY, FILE, TEMP)

    /** Writes serialized layout JSON to an isolated path using the active store's bounds and move policy. */
    @JvmStatic
    fun saveJson(json: String?, file: Path): Boolean {
        val target = file.toAbsolutePath().normalize()
        val directory = target.parent ?: return false
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        return saveJson(json, directory, target, temp)
    }

    private fun saveJson(json: String?, directory: Path, file: Path, temp: Path): Boolean {
        if (json == null || json.length > ScoreboardLayoutCodec.MAX_JSON_CHARS) return false
        try {
            Files.createDirectories(directory)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > ScoreboardLayoutCodec.MAX_JSON_CHARS * 4L) return false
            Files.write(
                temp,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (_: IOException) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
            }
            return false
        }
    }
}
