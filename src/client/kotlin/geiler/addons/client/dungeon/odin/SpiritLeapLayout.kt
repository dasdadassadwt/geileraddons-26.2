package geiler.addons.client.dungeon.odin

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Screen-relative layout and hover timing for the four Spirit Leap teammate cards. */
class SpiritLeapLayout private constructor() {
    @JvmRecord
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        // Keep Kotlin call sites on the pre-record method syntax while the JVM record accessors
        // retain the exact Java x(), y(), width(), and height() descriptors.
        @JvmName("xForKotlinCompatibility")
        fun x() = x

        @JvmName("yForKotlinCompatibility")
        fun y() = y

        @JvmName("widthForKotlinCompatibility")
        fun width() = width

        @JvmName("heightForKotlinCompatibility")
        fun height() = height

        override fun toString() = "Bounds[x=$x, y=$y, width=$width, height=$height]"
    }

    @JvmRecord
    data class Layout(
        val cardWidth: Int,
        val cardHeight: Int,
        val maxHoverGrowth: Int,
        // The frozen Java record did not validate reference components; keep nulls representable
        // through its canonical constructor while Kotlin callers still see the real contract.
        val topLeft: Bounds?,
        val topRight: Bounds?,
        val bottomLeft: Bounds?,
        val bottomRight: Bounds?,
    ) {
        // Keep Kotlin call sites on the pre-record method syntax while the JVM record accessors
        // retain the frozen Java component names and descriptors.
        @JvmName("cardWidthForKotlinCompatibility")
        fun cardWidth() = cardWidth

        @JvmName("cardHeightForKotlinCompatibility")
        fun cardHeight() = cardHeight

        @JvmName("maxHoverGrowthForKotlinCompatibility")
        fun maxHoverGrowth() = maxHoverGrowth

        @JvmName("topLeftForKotlinCompatibility")
        fun topLeft() = topLeft

        @JvmName("topRightForKotlinCompatibility")
        fun topRight() = topRight

        @JvmName("bottomLeftForKotlinCompatibility")
        fun bottomLeft() = bottomLeft

        @JvmName("bottomRightForKotlinCompatibility")
        fun bottomRight() = bottomRight

        fun quadrant(quadrant: Int): Bounds? = when (quadrant) {
            0 -> topLeft
            1 -> topRight
            2 -> bottomLeft
            3 -> bottomRight
            else -> null
        }

        override fun toString() =
            "Layout[cardWidth=$cardWidth, cardHeight=$cardHeight, maxHoverGrowth=$maxHoverGrowth, " +
                "topLeft=$topLeft, topRight=$topRight, bottomLeft=$bottomLeft, bottomRight=$bottomRight]"
    }

    /** Four-element, allocation-free 200ms hover interpolation used only while the menu is drawn. */
    class HoverAnimation {
        private val amounts = FloatArray(4)
        private var previousNanos = 0L
        private var initialized = false

        fun advance(hoveredQuadrant: Int, nowNanos: Long) {
            val elapsed = if (initialized) max(0L, nowNanos - previousNanos) else 0L
            previousNanos = nowNanos
            initialized = true
            val step = min(1.0f, elapsed.toFloat() / HOVER_DURATION_NANOS)
            for (quadrant in amounts.indices) {
                val target = if (quadrant == hoveredQuadrant) 1.0f else 0.0f
                val current = amounts[quadrant]
                amounts[quadrant] = current + max(-step, min(step, target - current))
            }
        }

        fun amount(quadrant: Int): Float = if (quadrant !in amounts.indices) 0.0f else amounts[quadrant]
    }

    companion object {
        const val CARD_WIDTH = 200
        const val CARD_HEIGHT = 75
        const val MIN_CARD_GAP = 10
        const val MAX_HOVER_GROWTH = 5
        const val MIN_DISTANCE_PERCENT = 5.5f
        const val MAX_DISTANCE_PERCENT = 18.0f
        const val DEFAULT_DISTANCE_PERCENT = 8.5f
        private const val HOVER_DURATION_NANOS = 200_000_000L

        /** Places cards symmetrically while preserving edge room and the minimum card gap. */
        @JvmStatic
        fun calculate(screenWidth: Int, screenHeight: Int, distancePercent: Float): Layout {
            val viewportWidth = max(1, screenWidth)
            val viewportHeight = max(1, screenHeight)
            val hoverGrowth = min(
                MAX_HOVER_GROWTH,
                max(0, min((viewportWidth - MIN_CARD_GAP - 2) / 4,
                    (viewportHeight - MIN_CARD_GAP - 2) / 4)),
            )
            var distance = if (java.lang.Float.isFinite(distancePercent)) distancePercent else DEFAULT_DISTANCE_PERCENT
            distance = max(MIN_DISTANCE_PERCENT, min(MAX_DISTANCE_PERCENT, distance))
            val cardWidth = min(CARD_WIDTH, max(1, (viewportWidth - 4 * hoverGrowth - MIN_CARD_GAP) / 2))
            val cardHeight = min(CARD_HEIGHT, max(1, (viewportHeight - 4 * hoverGrowth - MIN_CARD_GAP) / 2))
            val centerX = viewportWidth / 2.0f
            val centerY = viewportHeight / 2.0f
            val offsetX = max(viewportWidth * distance / 100.0f,
                (cardWidth + MIN_CARD_GAP + 2 * hoverGrowth) / 2.0f)
            val offsetY = max(viewportHeight * distance / 100.0f,
                (cardHeight + MIN_CARD_GAP + 2 * hoverGrowth) / 2.0f)
            val leftX = Math.round(centerX - offsetX - cardWidth / 2.0f)
            val rightX = Math.round(centerX + offsetX - cardWidth / 2.0f)
            val topY = Math.round(centerY - offsetY - cardHeight / 2.0f)
            val bottomY = Math.round(centerY + offsetY - cardHeight / 2.0f)
            return Layout(
                cardWidth, cardHeight, hoverGrowth,
                Bounds(leftX, topY, cardWidth, cardHeight),
                Bounds(rightX, topY, cardWidth, cardHeight),
                Bounds(leftX, bottomY, cardWidth, cardHeight),
                Bounds(rightX, bottomY, cardWidth, cardHeight),
            )
        }

        /** Returns the card-facing class label using locale-independent casing. */
        @JvmStatic
        fun displayClassLabel(className: String?, dead: Boolean): String {
            if (dead) return "DEAD"
            return if (className.isNullOrBlank()) "Class unknown" else className.uppercase(Locale.ROOT)
        }
    }
}
