package geiler.addons.client.macros.etw

import com.google.gson.JsonObject

/** A point or vector in doubles; written to JSON exactly as held, with no rounding. */
data class Xyz(val x: Double, val y: Double, val z: Double) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("x", x)
        json.addProperty("y", y)
        json.addProperty("z", z)
        return json
    }

    operator fun minus(other: Xyz): Xyz = Xyz(x - other.x, y - other.y, z - other.z)
}

data class IntXyz(val x: Int, val y: Int, val z: Int) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("x", x)
        json.addProperty("y", y)
        json.addProperty("z", z)
        return json
    }
}

/** Mode A is a sneaking right click (block targeting), mode B a normal one (coordinate and rotation analysis). */
enum class EtwMode { A, B }

/** The two server packets that can move or turn the player. */
enum class PacketKind(val label: String) {
    POSITION("ClientboundPlayerPositionPacket"),
    ROTATION("ClientboundPlayerRotationPacket"),
}

/** The player as the client holds it at one instant; angles in degrees as Minecraft stores them. */
class PlayerState(
    val position: Xyz,
    val eyePosition: Xyz,
    val yaw: Double,
    val pitch: Double,
    val deltaMovement: Xyz?,
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.add("position", position.toJson())
        json.add("eyePosition", eyePosition.toJson())
        json.addProperty("yaw", yaw)
        json.addProperty("pitch", pitch)
        if (deltaMovement != null) json.add("deltaMovement", deltaMovement.toJson())
        return json
    }
}

/** What the server did to the player: one packet, the player before and after the client applied it. */
class PacketObservation(
    val kind: PacketKind,
    val tick: Long,
    val nanoTime: Long,
    val epochMs: Long,
    val raw: JsonObject,
    val before: PlayerState,
    val after: PlayerState,
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("packetType", kind.label)
        json.addProperty("tick", tick)
        json.addProperty("nanoTime", nanoTime)
        json.addProperty("epochMs", epochMs)
        json.add("raw", raw.deepCopy())
        json.add("before", before.toJson())
        json.add("after", after.toJson())
        return json
    }
}

/** The click-time numbers the differences are measured from. Never changed after the click. */
class ClickNumbers(
    val position: Xyz,
    val eyePosition: Xyz,
    val yaw: Double,
    val pitch: Double,
    val hitLocation: Xyz?,
    val targetBlock: IntXyz?,
    val tick: Long,
    val nanoTime: Long,
)

/**
 * One recorded right click, captured in full at the instant of the click. [click], [targeting] and [sphere] are
 * finished JSON; the recorder keeps its own copy, so nothing that happens later can change them.
 */
class EtwSample(
    val mode: EtwMode,
    val click: JsonObject,
    val targeting: JsonObject?,
    val sphere: JsonObject?,
    val numbers: ClickNumbers,
    val unavailable: List<String>,
)
