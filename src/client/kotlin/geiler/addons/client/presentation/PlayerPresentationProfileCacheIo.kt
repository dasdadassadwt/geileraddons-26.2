package geiler.addons.client.presentation

import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.ClientJsonFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/** Bounded cache and classpath source I/O for the hosted player presentation profile. */
object PlayerPresentationProfileCacheIo {
    /** Reads a valid cached profile, then the bundled editable resource, then the empty profile. */
    @JvmStatic
    fun loadLocal(cachePath: Path, resourceAnchor: Class<*>, bundledResource: String): PlayerPresentationProfiles {
        val cached = readCache(cachePath)
        if (cached != null) return PlayerPresentationProfileFallback.local(cached, null)

        val bundled = try {
            val input = resourceAnchor.getResourceAsStream(bundledResource)
            if (input == null) {
                null
            } else {
                input.use {
                    val bytes = it.readNBytes(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1)
                    if (bytes.size > PlayerPresentationProfileSource.MAX_BODY_BYTES) {
                        throw IOException("Bundled player profile exceeds the size limit")
                    }
                    PlayerPresentationProfiles.parseUtf8(bytes)
                }
            }
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not load bundled player presentation profile", error)
            null
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not load bundled player presentation profile", error)
            null
        }
        return PlayerPresentationProfileFallback.local(null, bundled)
    }

    /** Persists the already-validated hosted JSON through the shared atomic, asynchronous JSON writer. */
    @JvmStatic
    fun writeCache(path: Path, json: String): Future<Boolean> {
        val byteCount = json.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount == 0 || byteCount > PlayerPresentationProfileSource.MAX_BODY_BYTES) {
            GeilerAddons.LOGGER.error(
                "Refusing to write invalid-sized player presentation cache {} ({} bytes)",
                path,
                byteCount,
            )
            return CompletableFuture.completedFuture(false)
        }
        return ClientJsonFile.writeAsync(path, json)
    }

    private fun readCache(path: Path): PlayerPresentationProfiles? {
        try {
            if (!Files.isRegularFile(path)) return null
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > PlayerPresentationProfileSource.MAX_BODY_BYTES) return null
                return PlayerPresentationProfiles.parseUtf8(bytes)
            }
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Ignoring invalid cached player presentation profile", error)
            return null
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Ignoring invalid cached player presentation profile", error)
            return null
        }
    }
}
