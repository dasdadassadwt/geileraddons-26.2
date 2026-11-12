package geiler.addons.client.dungeon

import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Kotlin implementation behind the stable Java and record API used by persistence callers. */
object ClientJsonFileLogic {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons dungeon data writer").apply { isDaemon = true }
    }

    private var pendingFailures = FailureWindow()

    @JvmStatic
    @Throws(IOException::class)
    fun openBundledResource(anchor: Class<*>?, resource: String?): InputStream {
        if (anchor == null || resource == null || resource.isBlank()) {
            throw IOException("Missing bundled resource location")
        }
        anchor.getResourceAsStream(resource)?.let { return it }
        val modPath = if (resource.startsWith("/")) resource.substring(1) else resource
        val packaged = FabricLoader.getInstance().getModContainer(GeilerAddons.MOD_ID)
            .flatMap { container -> container.findPath(modPath) }
            .orElseThrow { IOException("Bundled resource is missing: $modPath") }
        return Files.newInputStream(packaged)
    }

    @JvmStatic
    fun <T> read(path: Path?, type: Class<T>?): T? = readResult<T>(path, type).value

    @JvmStatic
    fun <T> readResult(path: Path?, type: Class<T>?): ClientJsonFile.ReadResult<T> {
        if (path == null || type == null) {
            return ClientJsonFile.ReadResult<T>(false, null, "missing path or type")
        }
        try {
            if (!Files.exists(path)) return ClientJsonFile.ReadResult<T>(false, null, null)
            if (!Files.isRegularFile(path)) {
                return ClientJsonFile.ReadResult<T>(true, null, "path is not a regular file")
            }
            val size = Files.size(path)
            if (size <= 0 || size > ClientJsonFile.MAX_FILE_BYTES) {
                return ClientJsonFile.ReadResult<T>(true, null, "file size is outside the allowed range")
            }
            val json = Files.readString(path, StandardCharsets.UTF_8)
            val value = ClientJsonFile.GSON.fromJson(json, type)
            return if (value == null) {
                ClientJsonFile.ReadResult<T>(true, null, "JSON did not contain a value")
            } else {
                ClientJsonFile.ReadResult<T>(true, value, null)
            }
        } catch (error: IOException) {
            return readFailure(path, error)
        } catch (error: RuntimeException) {
            return readFailure(path, error)
        }
    }

    @JvmStatic
    fun writeAsync(path: Path?, json: String?): Future<Boolean> {
        if (path == null || json == null) {
            pendingFailures.failed = true
            return CompletableFuture.completedFuture(false)
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > ClientJsonFile.MAX_FILE_BYTES) {
            GeilerAddons.LOGGER.error(
                "Refusing to write oversized client JSON file {} ({} bytes)",
                path,
                bytes.size,
            )
            pendingFailures.failed = true
            return CompletableFuture.completedFuture(false)
        }
        return writeBatchAsync(listOf(ClientJsonFile.Write(path, bytes)))
    }

    /** Writes all data files first and the index last on one serialized worker. */
    @JvmStatic
    fun writeBatchAsync(writes: List<ClientJsonFile.Write?>?): Future<Boolean> {
        if (writes.isNullOrEmpty()) {
            pendingFailures.failed = true
            return CompletableFuture.completedFuture(false)
        }

        val snapshot = ArrayList<ClientJsonFile.Write>(writes.size)
        for (write in writes) {
            if (write == null || write.path == null || write.bytes == null ||
                write.bytes.size > ClientJsonFile.MAX_FILE_BYTES
            ) {
                GeilerAddons.LOGGER.error("Refusing an invalid or oversized client JSON write batch")
                pendingFailures.failed = true
                return CompletableFuture.completedFuture(false)
            }
            snapshot.add(ClientJsonFile.Write(write.path, write.bytes.clone()))
        }

        val batchFailures = pendingFailures
        return writer.submit<Boolean> {
            try {
                for (write in snapshot) {
                    var temporary: Path? = null
                    try {
                        val parent = write.path.parent
                            ?: throw IOException("target has no parent directory")
                        Files.createDirectories(parent)
                        val tempFile = Files.createTempFile(parent, "geiler-data-", ".tmp")
                        temporary = tempFile
                        Files.write(tempFile, write.bytes)
                        try {
                            Files.move(
                                tempFile,
                                write.path,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(tempFile, write.path, StandardCopyOption.REPLACE_EXISTING)
                        }
                        temporary = null
                    } catch (error: IOException) {
                        batchFailures.failed = true
                        GeilerAddons.LOGGER.error("Could not write client JSON file {}", write.path, error)
                        return@submit false
                    } finally {
                        val temp = temporary
                        if (temp != null) {
                            try {
                                Files.deleteIfExists(temp)
                            } catch (error: IOException) {
                                GeilerAddons.LOGGER.debug("Could not remove temporary file {}", temp, error)
                            }
                        }
                    }
                }
                true
            } catch (error: RuntimeException) {
                batchFailures.failed = true
                throw error
            } catch (error: Error) {
                batchFailures.failed = true
                throw error
            }
        }
    }

    @JvmStatic
    fun flushAndReport(): Boolean {
        val barrier: Future<Boolean>
        synchronized(ClientJsonFile::class.java) {
            val drained = pendingFailures
            pendingFailures = FailureWindow()
            barrier = writer.submit<Boolean> { !drained.failed }
        }
        return try {
            if (barrier.get() == false) {
                GeilerAddons.LOGGER.error("A client JSON save did not complete")
                false
            } else {
                true
            }
        } catch (error: Exception) {
            GeilerAddons.LOGGER.error("Could not wait for client JSON save", error)
            false
        }
    }

    @JvmStatic
    fun flush() {
        flushAndReport()
    }

    private fun <T> readFailure(path: Path, error: Exception): ClientJsonFile.ReadResult<T> {
        GeilerAddons.LOGGER.warn("Could not read client JSON file {}", path, error)
        return ClientJsonFile.ReadResult(
            true,
            null,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private class FailureWindow {
        @Volatile
        var failed: Boolean = false
    }
}
