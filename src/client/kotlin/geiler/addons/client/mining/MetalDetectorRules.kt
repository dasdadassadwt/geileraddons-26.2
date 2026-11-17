package geiler.addons.client.mining

import com.google.gson.JsonParser
import java.util.Locale

/**
 * The Mines of Divan metal detector solver, ported from SkyHanni's MetalDetectorSolver (LGPL-2.1) with its
 * `MetalDetectorChests` data (SkyHanni-REPO, MIT). The detector's action bar gives the distance to the treasure; the
 * chest is one of 39 fixed spots around a base point, and the spots at exactly that distance are the candidates.
 */
object MetalDetectorRules {
    class Pos(val x: Int, val y: Int, val z: Int) {
        override fun equals(other: Any?): Boolean = other is Pos && other.x == x && other.y == y && other.z == z
        override fun hashCode(): Int = 31 * (31 * x + y) + z
        override fun toString(): String = "$x,$y,$z"
        fun plus(dx: Int, dy: Int, dz: Int): Pos = Pos(x + dx, y + dy, z + dz)
    }

    enum class Block { QUARTZ_STAIRS, BARRIER, OTHER }

    private val DISTANCE = Regex("TREASURE:\\s*([\\d,]+(?:\\.\\d+)?)m")
    private val FOUND = Regex("^You found .* with your Metal Detector!$")

    /** The treasure distance from an action bar line, or null when the line has none. */
    @JvmStatic
    fun parseDistance(plain: String): Double? =
        DISTANCE.find(plain)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    @JvmStatic
    fun isTreasureFound(plain: String): Boolean = FOUND.matches(plain.trim())

    /** The `x:y:z` offsets from the data file; a malformed entry is dropped. */
    @JvmStatic
    fun parseOffsets(json: String): List<Pos> {
        val out = ArrayList<Pos>()
        val array = JsonParser.parseString(json).asJsonObject.getAsJsonArray("locations") ?: return out
        for (element in array) {
            val parts = element.asString.split(":")
            if (parts.size != 3) continue
            val x = parts[0].toIntOrNull() ?: continue
            val y = parts[1].toIntOrNull() ?: continue
            val z = parts[2].toIntOrNull() ?: continue
            out.add(Pos(x, y, z))
        }
        return out
    }

    /** One decimal place, like the action bar prints it. */
    @JvmStatic
    fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    /**
     * Spots whose centre-top is [distance] from the player, as SkyHanni matches them: each offset is subtracted from the
     * base point, the spot is lifted one block, and its distance to the player's feet is rounded to a tenth.
     * [ignore] is the spot just reached, which stays out until the player walks away.
     */
    @JvmStatic
    fun candidates(base: Pos, offsets: List<Pos>, ignore: Pos?, distance: Double, px: Double, py: Double, pz: Double): List<Pos> {
        val out = ArrayList<Pos>()
        for (offset in offsets) {
            val spot = Pos(base.x - offset.x, base.y - offset.y, base.z - offset.z)
            if (spot == ignore) return out
            val dx = spot.x - px
            val dy = spot.y + 1 - py
            val dz = spot.z - pz
            if (round1(Math.sqrt(dx * dx + dy * dy + dz * dz)) == distance) out.add(spot)
        }
        return out
    }

    /**
     * Finds the base point: a quartz stair with a barrier 13 blocks above it, then the barrier with the highest x, y, z
     * reachable by stepping +x, +y, +z. Searches 100 x 61 x 100 blocks around the player, top to bottom.
     */
    @JvmStatic
    fun findBase(px: Int, py: Int, pz: Int, blockAt: (Int, Int, Int) -> Block): Pos? {
        for (i in -50 until 50) {
            for (j in 30 downTo -30) {
                for (k in -50 until 50) {
                    val x = px + i
                    val y = py + j
                    val z = pz + k
                    if (blockAt(x, y, z) != Block.QUARTZ_STAIRS || blockAt(x, y + 13, z) != Block.BARRIER) continue
                    return highestBarrier(Pos(x, y + 13, z), blockAt)
                }
            }
        }
        return null
    }

    private fun highestBarrier(start: Pos, blockAt: (Int, Int, Int) -> Block): Pos {
        var current = start
        var changed = true
        while (changed) {
            changed = false
            if (blockAt(current.x + 1, current.y, current.z) == Block.BARRIER) {
                current = current.plus(1, 0, 0)
                changed = true
            }
            if (blockAt(current.x, current.y + 1, current.z) == Block.BARRIER) {
                current = current.plus(0, 1, 0)
                changed = true
            }
            if (blockAt(current.x, current.y, current.z + 1) == Block.BARRIER) {
                current = current.plus(0, 0, 1)
                changed = true
            }
        }
        return current
    }

    /** The four dwarven tools whose finds the detector's "all tools" setting waits for. */
    val TOOL_IDS = setOf("DWARVEN_LAPIS_SWORD", "DWARVEN_EMERALD_HAMMER", "DWARVEN_GOLD_HAMMER", "DWARVEN_DIAMOND_AXE")

    @JvmStatic
    fun hasAllTools(ids: Collection<String>): Boolean = ids.containsAll(TOOL_IDS)

    @JvmStatic
    fun distanceText(meters: Double): String = String.format(Locale.ROOT, "%.1fm", meters)
}
