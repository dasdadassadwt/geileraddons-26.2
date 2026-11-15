package geiler.addons.client.dungeon.odin

import java.util.Locale
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Bounded text and color for the Livid boss-world timer. */
class DungeonLividOverlayLabel private constructor() {
    @JvmRecord
    data class Snapshot(val text: String?, val colorArgb: Int) {
        override fun toString(): String = "Snapshot[text=$text, colorArgb=$colorArgb]"
    }

    companion object {
        private const val MAX_TIMER_TICKS = 340
        /** Real-font scale for the projected Livid timer; 1.0 matches Tiki Helper's default text size. */
        const val HUD_SCALE = 1.0f

        @JvmStatic
        fun create(timerEnabled: Boolean, floorFiveBoss: Boolean, remainingTicks: Int): Snapshot? {
            if (!timerEnabled || !floorFiveBoss || remainingTicks <= 0) return null
            val ticks = minOf(remainingTicks, MAX_TIMER_TICKS)
            val color = when {
                ticks > 260 -> 0xFF55FF55.toInt()
                ticks > 130 -> 0xFFFFFF55.toInt()
                else -> 0xFFFF5555.toInt()
            }
            return Snapshot(String.format(Locale.ROOT, "%.1fS", ticks / 20.0), color)
        }
    }
}
