package geiler.addons.client.farming

import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** Durable lifetime totals for the independent pest and Slayer trackers. */
object PersistentHourlyStatsStore {
    private const val PESTS_FILE = "pest-hourly-stats.json"
    private const val SLAYER_FILE = "slayer-hourly-stats.json"

    @JvmStatic
    fun loadPests(): Snapshot = load(PESTS_FILE)

    @JvmStatic
    fun loadSlayer(): Snapshot = load(SLAYER_FILE)

    @JvmStatic
    fun savePests(kills: Int, activeNanos: Long) = save(PESTS_FILE, kills, activeNanos)

    @JvmStatic
    fun saveSlayer(kills: Int, activeNanos: Long) = save(SLAYER_FILE, kills, activeNanos)

    private fun load(fileName: String): Snapshot {
        val root = root() ?: return Snapshot()
        val loaded: Snapshot? = ClientJsonFile.read(root.resolve(fileName), Snapshot::class.java)
        if (loaded == null) return Snapshot()
        loaded.kills = maxOf(0, loaded.kills)
        loaded.activeNanos = maxOf(0L, loaded.activeNanos)
        return loaded
    }

    private fun save(fileName: String, kills: Int, activeNanos: Long) {
        val root = root() ?: return
        val snapshot = Snapshot()
        snapshot.kills = maxOf(0, kills)
        snapshot.activeNanos = maxOf(0L, activeNanos)
        ClientJsonFile.writeAsync(root.resolve(fileName), ClientJsonFile.GSON.toJson(snapshot))
    }

    private fun root(): Path? = try {
        val configDir: Path? = FabricLoader.getInstance().configDir
        configDir?.resolve("geileraddons")
    } catch (_: NullPointerException) {
        // A bare offline harness can touch module singletons before Fabric installs its config path.
        null
    }

    /** Public fields and a no-argument constructor keep the JSON schema simple and bounded. */
    class Snapshot {
        @JvmField
        var kills: Int = 0

        @JvmField
        var activeNanos: Long = 0L
    }
}
