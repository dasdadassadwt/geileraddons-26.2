package geiler.addons.client.dungeon.f7

import com.google.gson.JsonObject
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** Best times by name (Odin's PersonalBest). Pure map logic; [PersonalBestFile] adds persistence. */
class PersonalBests(initial: Map<String, Float>) {
    class Outcome(val newBest: Boolean, val old: Float)

    private val best = LinkedHashMap(initial)

    fun get(name: String): Float? = best[name]

    fun snapshot(): Map<String, Float> = LinkedHashMap(best)

    fun clear() = best.clear()

    /** Records [seconds]; a time below the old best (9999 when none) replaces it. */
    fun record(name: String, seconds: Float): Outcome {
        val old = best[name] ?: NONE
        if (old > seconds) {
            best[name] = seconds
            return Outcome(true, old)
        }
        return Outcome(false, old)
    }

    companion object {
        const val NONE = 9999f
    }
}

/** A [PersonalBests] saved as `config/geileraddons/<file>`; [defaults] seed it when no valid file exists. */
class PersonalBestFile(fileName: String, private val defaults: Map<String, Float>) {
    private val path: Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve(fileName)
    private var loaded: PersonalBests? = null

    @Synchronized
    fun bests(): PersonalBests = loaded ?: load().also { loaded = it }

    private fun load(): PersonalBests {
        val result = ClientJsonFile.readResult(path, JsonObject::class.java)
        val values = result.value?.getAsJsonObject("bestSeconds")
        if (!result.exists || !result.valid() || values == null) return PersonalBests(defaults)
        val map = LinkedHashMap<String, Float>()
        for ((name, element) in values.entrySet()) {
            if (map.size >= MAX_ENTRIES || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) continue
            val seconds = element.asFloat
            if (seconds.isFinite() && seconds >= 0f) map[name] = seconds
        }
        return PersonalBests(map)
    }

    /** Records a time, saves on improvement, and returns the outcome. */
    @Synchronized
    fun record(name: String, seconds: Float): PersonalBests.Outcome {
        val outcome = bests().record(name, seconds)
        if (outcome.newBest) save()
        return outcome
    }

    /** Forgets every best, saved ones included. */
    @Synchronized
    fun reset() {
        bests().clear()
        save()
    }

    private fun save() {
        val root = JsonObject()
        root.addProperty("version", 1)
        val values = JsonObject()
        for ((name, seconds) in bests().snapshot()) values.addProperty(name, seconds)
        root.add("bestSeconds", values)
        ClientJsonFile.writeAsync(path, root.toString())
    }

    private companion object {
        const val MAX_ENTRIES = 16
    }
}
