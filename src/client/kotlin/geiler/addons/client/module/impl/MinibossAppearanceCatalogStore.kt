package geiler.addons.client.module.impl

import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Reads a small user-curated signature catalog from this Minecraft instance's config folder. */
object MinibossAppearanceCatalogStore {
    private const val MAX_STATUS_CHARS = 240

    @JvmStatic
    fun load(): LoadResult {
        return try {
            val file = FabricLoader.getInstance().configDir
                .resolve("geileraddons/miniboss-appearance-signatures.json")
            loadFile(file)
        } catch (error: RuntimeException) {
            result(MinibossAppearanceCatalog.empty(), "catalog could not be read: ${safeError(error)}")
        }
    }

    /** Path-bound seam for isolated checks; it runs the same creation, bounds, decode, and parse path. */
    @JvmStatic
    @JvmName("loadFromPathForChecks")
    internal fun loadFromPathForChecks(file: Path): LoadResult = loadFile(file)

    private fun loadFile(file: Path): LoadResult {
        try {
            if (!Files.exists(file)) {
                val parent = file.parent
                    ?: return result(MinibossAppearanceCatalog.empty(), "catalog path has no parent")
                Files.createDirectories(parent)
                try {
                    Files.writeString(
                        file, MinibossAppearanceCatalog.emptyTemplate(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    )
                } catch (_: FileAlreadyExistsException) {
                    // Another initialization path created the same empty template.
                }
            }
            if (!Files.isRegularFile(file)) {
                return result(MinibossAppearanceCatalog.empty(), "catalog path is not a regular file")
            }
            val fileSize = Files.size(file)
            if (fileSize <= 0 || fileSize > MinibossAppearanceCatalog.MAX_BYTES) {
                return result(MinibossAppearanceCatalog.empty(), "catalog size is outside the 64 KiB limit")
            }
            val bytes = Files.newInputStream(file).use { input ->
                input.readNBytes(MinibossAppearanceCatalog.MAX_BYTES + 1)
            }
            if (bytes.isEmpty() || bytes.size > MinibossAppearanceCatalog.MAX_BYTES) {
                return result(MinibossAppearanceCatalog.empty(), "catalog size is outside the 64 KiB limit")
            }
            // Keep the previous Java reader's replacement behavior for malformed UTF-8.
            val json = String(bytes, StandardCharsets.UTF_8)
            val parsed = MinibossAppearanceCatalog.parse(json)
            if (!parsed.valid) {
                return result(MinibossAppearanceCatalog.empty(), "invalid catalog: ${parsed.error}")
            }
            val status = if (parsed.unreviewedCount == 0) ""
                else "ignored ${parsed.unreviewedCount} unreviewed appearance entries"
            return result(parsed.catalog, status)
        } catch (error: IOException) {
            return result(MinibossAppearanceCatalog.empty(), "catalog could not be read: ${safeError(error)}")
        } catch (error: RuntimeException) {
            return result(MinibossAppearanceCatalog.empty(), "catalog could not be read: ${safeError(error)}")
        }
    }

    private fun result(catalog: MinibossAppearanceCatalog, status: String): LoadResult =
        LoadResult(catalog, boundedStatus(status))

    private fun boundedStatus(status: String): String {
        val bounded = StringBuilder(minOf(status.length, MAX_STATUS_CHARS))
        for (character in status) {
            if (bounded.length >= MAX_STATUS_CHARS) break
            bounded.append(if (Character.isISOControl(character.code)) ' ' else character)
        }
        return bounded.toString().trim()
    }

    private fun safeError(error: Exception): String = error.message ?: error.javaClass.simpleName

    @JvmRecord
    data class LoadResult(val catalog: MinibossAppearanceCatalog, val status: String)
}
