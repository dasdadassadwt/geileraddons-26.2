package geiler.addons.client.sim

import com.google.gson.JsonObject
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.nio.file.Path

/** The saved simulator spot per singleplayer world, and one for your island: `config/geileraddons/sim-positions.json`. */
object DeviceSimStore {
    class Spot(val pos: BlockPos, val facing: Direction)

    private const val MAX_SPOTS = 64

    private val path: Path by lazy {
        FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("sim-positions.json")
    }

    private val spots: JsonObject by lazy {
        val result = ClientJsonFile.readResult(path, JsonObject::class.java)
        result.value?.takeIf { result.valid() } ?: JsonObject()
    }

    fun get(key: String): Spot? {
        val entry = spots.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return try {
            val facing = Direction.byName(entry.get("facing").asString)
            if (facing == null || facing.axis.isVertical) null
            else Spot(BlockPos(entry.get("x").asInt, entry.get("y").asInt, entry.get("z").asInt), facing)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun put(key: String, spot: Spot) {
        if (!spots.has(key) && spots.size() >= MAX_SPOTS) spots.remove(spots.keySet().first())
        val entry = JsonObject()
        entry.addProperty("x", spot.pos.x)
        entry.addProperty("y", spot.pos.y)
        entry.addProperty("z", spot.pos.z)
        entry.addProperty("facing", spot.facing.serializedName)
        spots.add(key, entry)
        ClientJsonFile.writeAsync(path, spots.toString())
    }
}
