package geiler.addons.client.dungeon.helpers

import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.Future

private const val VERSION = 1
private const val MAX_ENTRIES = 8
private const val MAX_SECONDS = 86_400.0f

/** Stores only the small, local puzzle best-time map using the serialized client JSON writer. */
object DungeonPuzzlePersonalBestStore {
    private val PATH: Path = FabricLoader.getInstance().configDir
        .resolve("geileraddons")
        .resolve("dungeon-puzzle-personal-bests.json")
    private val session = DungeonPuzzlePersonalBestStoreSession(PATH)

    @JvmStatic
    fun load(): Map<String, Float> = session.load()

    @JvmStatic
    fun save(snapshot: Map<String?, Float?>?) {
        session.save(snapshot)
    }
}

/** Path-bound implementation so persistence checks exercise the production codec in isolation. */
internal class DungeonPuzzlePersonalBestStoreSession(private val path: Path) {
    private var loaded = false
    private var bestTimes: Map<String, Float> = emptyMap()

    @Synchronized
    fun load(): Map<String, Float> {
        if (loaded) return bestTimes
        loaded = true
        val result = ClientJsonFile.readResult(path, JsonObject::class.java)
        if (!result.exists) return bestTimes
        val root = result.value
        if (!result.valid() || root == null || !root.has("version") ||
            !root.get("version").isJsonPrimitive() || !root.get("version").getAsJsonPrimitive().isNumber() ||
            root.get("version").getAsInt() != VERSION || !root.has("bestSeconds") ||
            !root.get("bestSeconds").isJsonObject()
        ) {
            GeilerAddons.LOGGER.warn("Dungeon puzzle PB file is invalid; leaving it untouched")
            return bestTimes
        }
        val values = root.getAsJsonObject("bestSeconds")
        if (values.size() > MAX_ENTRIES) {
            GeilerAddons.LOGGER.warn("Dungeon puzzle PB file has too many entries; leaving it untouched")
            return bestTimes
        }
        val decoded = LinkedHashMap<String, Float>()
        for ((name, element) in values.entrySet()) {
            if (!isKnownPuzzle(name) || element == null || !element.isJsonPrimitive() ||
                !element.getAsJsonPrimitive().isNumber()
            ) {
                GeilerAddons.LOGGER.warn("Dungeon puzzle PB file has an invalid entry; leaving it untouched")
                return bestTimes
            }
            val seconds = element.getAsFloat()
            if (!seconds.isFinite() || seconds < 0.0f || seconds > MAX_SECONDS) {
                GeilerAddons.LOGGER.warn("Dungeon puzzle PB file has an out-of-range time; leaving it untouched")
                return bestTimes
            }
            val key = normalizePuzzleName(name)
            decoded.merge(key, seconds) { existing, incoming -> java.lang.Math.min(existing, incoming) }
        }
        bestTimes = java.util.Map.copyOf(decoded)
        return bestTimes
    }

    /** Returns the queued writer result for checks; runtime callers keep the original void API. */
    @Synchronized
    fun save(snapshot: Map<String?, Float?>?): Future<Boolean>? {
        if (snapshot == null || snapshot.size > MAX_ENTRIES) return null
        val sorted = TreeMap<String, Float>()
        for ((name, seconds) in snapshot) {
            val puzzleName = name ?: return null
            if (!isKnownPuzzle(puzzleName) || seconds == null || !seconds.isFinite() ||
                seconds < 0.0f || seconds > MAX_SECONDS
            ) {
                return null
            }
            val key = normalizePuzzleName(puzzleName)
            sorted.merge(key, seconds) { existing, incoming -> java.lang.Math.min(existing, incoming) }
        }
        val root = JsonObject()
        root.addProperty("version", VERSION)
        root.add("bestSeconds", ClientJsonFile.GSON.toJsonTree(sorted))
        bestTimes = java.util.Map.copyOf(sorted)
        loaded = true
        return ClientJsonFile.writeAsync(path, ClientJsonFile.GSON.toJson(root))
    }

    private fun isKnownPuzzle(name: String?): Boolean =
        when (name) {
            "Water Board", "Teleport Maze", "Ice Fill", "Higher Or Lower", "Higher Blaze", "Lower Blaze",
            "Creeper Beams", "Three Weirdos", "Quiz", "Boulder" -> true
            else -> false
        }

    private fun normalizePuzzleName(name: String): String =
        when (name) {
            "Higher Blaze", "Lower Blaze" -> "Higher Or Lower"
            else -> name
        }
}
