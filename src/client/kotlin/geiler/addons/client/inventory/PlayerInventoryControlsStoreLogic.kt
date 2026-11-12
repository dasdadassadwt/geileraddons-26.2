package geiler.addons.client.inventory

import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One path-bound Inventory Controls store, used by the runtime and isolated persistence checks. */
internal class PlayerInventoryControlsStoreSession(configDirectory: Path) {
    private val directory: Path = configDirectory
        .resolve("geileraddons")
        .resolve("inventory-controls")
    private val file: Path = directory.resolve("controls.json")
    private val temp: Path = directory.resolve("controls.json.tmp")
    private var persistenceBlocked = false
    private val pending = AtomicReference<PlayerInventoryControlsData?>()
    private val writer by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "GeilerAddons inventory controls writer").apply { isDaemon = true }
        }
    }

    /** Queues the newest state for a background write so a lock toggle never touches the disk on the game thread. */
    fun saveLater(data: PlayerInventoryControlsData?) {
        if (data == null) return
        if (pending.getAndSet(data) == null) writer.execute { pending.getAndSet(null)?.let(::save) }
        if (!shutdownHookAdded) {
            shutdownHookAdded = true
            Runtime.getRuntime().addShutdownHook(Thread { pending.getAndSet(null)?.let(::save) })
        }
    }

    @Volatile
    private var shutdownHookAdded = false

    /** Waits until queued writes finish; used by flushes and checks. */
    fun awaitIdle(timeoutMillis: Long): Boolean = try {
        writer.submit { }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: Exception) {
        false
    }

    fun load(): PlayerInventoryControlsData {
        val result = readFrom(file)
        persistenceBlocked = !result.success()
        return if (result.success()) result.data()!! else PlayerInventoryControlsData.defaults()
    }

    fun readForReload(): PlayerInventoryControlsStore.LoadResult {
        val result = readFrom(file)
        persistenceBlocked = !result.success()
        return result
    }

    fun flushForProfileImport(data: PlayerInventoryControlsData?): Boolean {
        // Do not overwrite an invalid on-disk member with fallback defaults before the recovery snapshot.
        return persistenceBlocked || save(data)
    }

    private fun readFrom(path: Path): PlayerInventoryControlsStore.LoadResult {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return PlayerInventoryControlsStore.LoadResult(true, PlayerInventoryControlsData.defaults())
        }
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(path) > PlayerInventoryControlsCodec.MAX_JSON_CHARS * 4L
            ) {
                return PlayerInventoryControlsStore.LoadResult(false, null)
            }
            val decoded = PlayerInventoryControlsCodec.decode(Files.readString(path, StandardCharsets.UTF_8))
            val data = decoded.orElse(null)
            return if (data == null) {
                PlayerInventoryControlsStore.LoadResult(false, null)
            } else {
                PlayerInventoryControlsStore.LoadResult(true, data)
            }
        } catch (_: IOException) {
            return PlayerInventoryControlsStore.LoadResult(false, null)
        } catch (_: RuntimeException) {
            return PlayerInventoryControlsStore.LoadResult(false, null)
        }
    }

    @Synchronized
    fun save(data: PlayerInventoryControlsData?): Boolean {
        if (data == null || persistenceBlocked || !readFrom(file).success()) {
            persistenceBlocked = true
            return false
        }
        try {
            Files.createDirectories(directory)
            val bytes = PlayerInventoryControlsCodec.encode(data).toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > PlayerInventoryControlsCodec.MAX_JSON_CHARS * 4L) return false
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
                // Preserve the original write failure and leave cleanup best-effort, as before.
            }
            return false
        }
    }
}

/** Atomic local persistence, independent from the shared ModConfig registry. */
object PlayerInventoryControlsStoreLogic {
    private val session = PlayerInventoryControlsStoreSession(FabricLoader.getInstance().configDir)

    @JvmStatic
    fun load(): PlayerInventoryControlsData = session.load()

    @JvmStatic
    @JvmName("readForReload")
    internal fun readForReload(): PlayerInventoryControlsStore.LoadResult = session.readForReload()

    @JvmStatic
    fun flushForProfileImport(data: PlayerInventoryControlsData?): Boolean =
        session.flushForProfileImport(data)

    @JvmStatic
    fun save(data: PlayerInventoryControlsData?): Boolean = session.save(data)

    @JvmStatic
    fun saveLater(data: PlayerInventoryControlsData?) = session.saveLater(data)

    @JvmStatic
    fun awaitIdle(timeoutMillis: Long): Boolean = session.awaitIdle(timeoutMillis)
}
