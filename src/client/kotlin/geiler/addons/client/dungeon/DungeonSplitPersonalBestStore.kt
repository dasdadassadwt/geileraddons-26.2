package geiler.addons.client.dungeon

import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.odin.DungeonSplitTracker
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

/** Path-bound client-local store; ClientJsonFile serializes writes and replaces files atomically. */
internal class DungeonSplitPersonalBestStoreSession(private val path: Path) {
    private var book = DungeonSplitPersonalBestBook.empty()
    private var initialized = false

    /** Loads once after the client config has been restored; invalid files are left untouched. */
    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        if (!Files.exists(path)) return
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > DungeonSplitPersonalBestBook.MAX_JSON_BYTES) {
                GeilerAddons.LOGGER.warn(
                    "Dungeon split PB file is not a regular file or exceeds its size limit; leaving it untouched",
                )
                return
            }
            val json = Files.readString(path, StandardCharsets.UTF_8)
            val decoded = DungeonSplitPersonalBestBook.decode(json)
            if (!decoded.valid) {
                GeilerAddons.LOGGER.warn(
                    "Dungeon split PB file is invalid ({}); leaving it untouched",
                    decoded.error,
                )
                return
            }
            book = decoded.book ?: return
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not load dungeon split PB data; leaving the file untouched", error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not load dungeon split PB data; leaving the file untouched", error)
        }
    }

    /** Records one closed split and queues an immutable JSON snapshot for atomic replacement. */
    @Synchronized
    fun record(
        floor: DungeonFloor?,
        split: DungeonSplitTracker.Split?,
        updatedAtMillis: Long,
    ): DungeonSplitPersonalBestBook.Update {
        initialize()
        val update = book.record(floor, split, updatedAtMillis)
        book = update.book ?: throw NullPointerException("Dungeon split PB update has no book")
        ClientJsonFile.writeAsync(path, book.encode())
        return update
    }

    fun find(floor: DungeonFloor?, splitId: String?): DungeonSplitPersonalBestBook.Entry? =
        book.find(floor, splitId)
}

/** Stable Java-facing singleton backed by the runtime config path. */
object DungeonSplitPersonalBestStore {
    private val path: Path = FabricLoader.getInstance().configDir
        .resolve("geileraddons").resolve("dungeon-split-personal-bests.json")
    private val session = DungeonSplitPersonalBestStoreSession(path)

    @JvmStatic
    fun initialize() = session.initialize()

    @JvmStatic
    fun record(
        floor: DungeonFloor?,
        split: DungeonSplitTracker.Split?,
        updatedAtMillis: Long,
    ): DungeonSplitPersonalBestBook.Update = session.record(floor, split, updatedAtMillis)

    @JvmStatic
    fun find(floor: DungeonFloor?, splitId: String?): DungeonSplitPersonalBestBook.Entry? =
        session.find(floor, splitId)
}
