package geiler.addons.client.macros.pathing

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Whether a straight line a body is carried along - a teleport - goes through anything shut: at every half block of it, the block
 * columns the width of the body touches, at the feet, the middle and the head. Plain arithmetic over a question about cells, so
 * checks can run it.
 */
object ShutSweep {
    /** Whether the cell holding this block position is shut. */
    fun interface Shut {
        fun at(x: Int, y: Int, z: Int): Boolean
    }

    private const val STEP = 0.5
    private const val EPSILON = 1.0E-4

    /** [halfWidth] is half the width of the body and [height] its height; the line goes from the feet at one point to the feet at the other. */
    @JvmStatic
    fun crosses(
        fromX: Double, fromY: Double, fromZ: Double, toX: Double, toY: Double, toZ: Double, halfWidth: Double, height: Double, shut: Shut,
    ): Boolean {
        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val steps = max(1, ceil(length / STEP).toInt())
        for (i in 0..steps) {
            val t = if (length < EPSILON) 0.0 else i.toDouble() / steps
            val x = fromX + dx * t
            val y = fromY + dy * t
            val z = fromZ + dz * t
            if (cellsShut(x, y, z, halfWidth, height, shut)) return true
        }
        return false
    }

    private fun cellsShut(x: Double, y: Double, z: Double, halfWidth: Double, height: Double, shut: Shut): Boolean {
        val feet = floor(y + EPSILON).toInt()
        val middle = floor(y + height * 0.5).toInt()
        val head = floor(y + height - EPSILON).toInt()
        for (cx in floor(x - halfWidth).toInt()..floor(x + halfWidth).toInt()) {
            for (cz in floor(z - halfWidth).toInt()..floor(z + halfWidth).toInt()) {
                if (shut.at(cx, feet, cz) || shut.at(cx, middle, cz) || shut.at(cx, head, cz)) return true
            }
        }
        return false
    }
}
