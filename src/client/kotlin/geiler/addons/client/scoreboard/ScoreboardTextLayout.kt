package geiler.addons.client.scoreboard

import geiler.addons.client.presentation.ComponentWidthLimiter
import net.minecraft.network.chat.Component
import java.util.function.ToIntFunction

/** Fits one scoreboard row to its available width without allowing its columns to overlap. */
class ScoreboardTextLayout private constructor() {
    @JvmRecord
    data class FittedLine(
        val left: Component,
        val right: Component?,
        val leftWidth: Int,
        val rightWidth: Int,
        val gap: Int,
    ) {
        fun totalWidth(): Int = leftWidth + rightWidth + gap
    }

    companion object {
        @JvmStatic
        fun fit(
            left: Component?,
            right: Component?,
            availableWidth: Int,
            requestedGap: Int,
            measureWidth: ToIntFunction<Component>?,
        ): FittedLine {
            val safeLeft = left ?: Component.empty()
            if (measureWidth == null) return FittedLine(safeLeft, right, 0, 0, 0)
            val available = maxOf(0, availableWidth)
            val measure = ToIntFunction<Component> { component -> maxOf(0, measureWidth.applyAsInt(component)) }
            val leftWidth = measure.applyAsInt(safeLeft)
            val rightWidth = if (right == null) 0 else measure.applyAsInt(right)
            val gap = if (right != null && leftWidth > 0 && rightWidth > 0) maxOf(0, requestedGap) else 0
            if (leftWidth + rightWidth + gap <= available) {
                return FittedLine(safeLeft, right, leftWidth, rightWidth, gap)
            }

            if (right == null) {
                val fitted = ComponentWidthLimiter.fit(safeLeft, available, measure) ?: Component.empty()
                return FittedLine(fitted, null, measure.applyAsInt(fitted), 0, 0)
            }

            var visibleGap = if (leftWidth > 0 && rightWidth > 0) minOf(gap, maxOf(0, available - 2)) else 0
            val textSpace = available - visibleGap
            val leftLimit: Int
            val rightLimit: Int
            if (leftWidth == 0) {
                leftLimit = 0
                rightLimit = textSpace
            } else if (rightWidth == 0) {
                leftLimit = textSpace
                rightLimit = 0
            } else if (textSpace >= 2) {
                var fittedLeftLimit = (textSpace.toLong() * leftWidth / (leftWidth.toLong() + rightWidth)).toInt()
                fittedLeftLimit = fittedLeftLimit.coerceIn(1, textSpace - 1)
                leftLimit = fittedLeftLimit
                rightLimit = textSpace - fittedLeftLimit
            } else {
                leftLimit = textSpace
                rightLimit = 0
                visibleGap = 0
            }

            val fittedLeft = ComponentWidthLimiter.fit(safeLeft, leftLimit, measure) ?: Component.empty()
            val fittedRight = ComponentWidthLimiter.fit(right, rightLimit, measure) ?: Component.empty()
            if ((leftWidth > 0 || rightWidth > 0)
                && fittedLeft.getString().isEmpty() && fittedRight.getString().isEmpty() && available > 0
            ) {
                val fallbackLeft = ComponentWidthLimiter.fit(safeLeft, available, measure) ?: Component.empty()
                if (fallbackLeft.getString().isNotEmpty()) {
                    return FittedLine(fallbackLeft, null, measure.applyAsInt(fallbackLeft), 0, 0)
                }
                val fallbackRight = ComponentWidthLimiter.fit(right, available, measure) ?: Component.empty()
                if (fallbackRight.getString().isNotEmpty()) {
                    return FittedLine(Component.empty(), fallbackRight, 0, measure.applyAsInt(fallbackRight), 0)
                }
            }
            return FittedLine(
                fittedLeft,
                fittedRight,
                measure.applyAsInt(fittedLeft),
                measure.applyAsInt(fittedRight),
                visibleGap,
            )
        }
    }
}
