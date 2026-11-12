package geiler.addons.client.gui

import java.util.function.ToIntFunction

/** Shared geometry for the live slot-id labels (small text, no backing plate) drawn over inventory and container slots. */
class SlotIdBadgeLayout private constructor() {
    @JvmRecord
    data class SlotPosition(val index: Int, val x: Int, val y: Int) {
        override fun toString(): String = "SlotPosition[index=$index, x=$x, y=$y]"
    }

    @JvmRecord
    data class Badge(
        val x: Int,
        val y: Int,
        val width: Int,
        val label: String,
        val scale: Float,
        val textX: Int,
        val textY: Int,
    ) {
        override fun toString(): String =
            "Badge[x=$x, y=$y, width=$width, label=$label, scale=$scale, textX=$textX, textY=$textY]"
    }

    companion object {
        const val BADGE_HEIGHT: Int = 9
        const val BADGE_PADDING: Int = 2
        const val LABEL_COLOR: Int = -45734
        const val LABEL_SCALE: Float = 0.6f

        @JvmStatic
        fun layout(
            slots: List<SlotPosition>,
            left: Int,
            top: Int,
            textWidth: ToIntFunction<String>,
        ): List<Badge> {
            val result = ArrayList<Badge>(slots.size)
            for (slot in slots) {
                val label = slot.index.toString()
                val scale = LABEL_SCALE
                val measuredWidth = Math.round(textWidth.applyAsInt(label) * scale)
                val width = maxOf(8, minOf(15, measuredWidth + BADGE_PADDING))
                val x = left + slot.x + 16 - width - 1
                val y = top + slot.y + 1
                result.add(Badge(x, y, width, label, scale, x + width - measuredWidth - 1, y))
            }
            return java.util.List.copyOf(result)
        }
    }
}
