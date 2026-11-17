package geiler.addons.client.dungeon.terminal

import kotlin.math.max

/**
 * Terminal Solver aim assist: a click that misses a clickable slot by at most the margin still counts as that slot.
 * Pure geometry in the custom grid's own units, so it can be checked offline.
 */
object TerminalAim {
    class Box(val slot: Int, val x: Int, val y: Int, val size: Int)

    /**
     * The slot a click at ([x], [y]) should press, or null. A click inside a clickable slot wins; otherwise the one
     * clickable slot within [margin] of the point. Two clickable slots within the margin give null, so a single
     * click never lands on the wrong one.
     */
    @JvmStatic
    fun pick(x: Double, y: Double, boxes: List<Box>, clickable: (Int) -> Boolean, margin: Double): Int? {
        var near: Int? = null
        var nearCount = 0
        for (box in boxes) {
            if (!clickable(box.slot)) continue
            if (x >= box.x && y >= box.y && x < box.x + box.size && y < box.y + box.size) return box.slot
            if (margin <= 0.0 || distance(x, y, box) > margin) continue
            near = box.slot
            nearCount++
        }
        return if (nearCount == 1) near else null
    }

    private fun distance(x: Double, y: Double, box: Box): Double {
        val dx = max(max(box.x - x, x - (box.x + box.size)), 0.0)
        val dy = max(max(box.y - y, y - (box.y + box.size)), 0.0)
        return Math.hypot(dx, dy)
    }
}
