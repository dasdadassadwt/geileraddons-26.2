package geiler.addons.client.spin

import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** The saved wheel in `config/geileraddons/spin-wheel.json`. */
object SpinWheelStore {
    class Data {
        @JvmField var style: String = "Pastel"
        @JvmField var entries: MutableList<SpinEntry> = ArrayList()
    }

    private var cached: Data? = null

    private fun path(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("spin-wheel.json")

    /** Client thread. Saved values are repaired: Gson can leave fields null or out of range. */
    fun load(): Data = cached ?: (ClientJsonFile.read(path(), Data::class.java) ?: Data()).also { data ->
        @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
        data.entries = (data.entries ?: ArrayList()).asSequence()
            .filter { it != null && it.name != null }
            .take(SpinWheelRules.MAX_ENTRIES)
            .onEach { it.name = it.name.take(SpinWheelRules.MAX_NAME) }
            .toMutableList()
        if (data.style != "Click GUI") data.style = "Pastel"
        SpinWheelRules.normalize(data.entries)
        cached = data
    }

    fun save(data: Data) {
        ClientJsonFile.writeAsync(path(), ClientJsonFile.GSON.toJson(data))
    }
}
