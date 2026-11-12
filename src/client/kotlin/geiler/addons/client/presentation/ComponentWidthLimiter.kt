package geiler.addons.client.presentation

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.ArrayList
import java.util.Optional
import java.util.function.ToIntFunction

/** Truncates visible component text to a measured width while retaining each displayed run's style. */
object ComponentWidthLimiter {
    private const val ELLIPSIS = "…"

    @JvmStatic
    fun fit(source: Component?, maximumWidth: Int, measureWidth: ToIntFunction<Component>?): Component? {
        if (source == null || measureWidth == null) return source
        if (maximumWidth <= 0) return Component.empty()
        if (measureWidth.applyAsInt(source) <= maximumWidth) return source

        val runs = ArrayList<StyledText>()
        source.visit({ style, text ->
            if (text.isNotEmpty()) runs.add(StyledText(text, style))
            Optional.empty<Void>()
        }, Style.EMPTY)
        if (runs.isEmpty()) return Component.empty()

        val emptyPrefixStyle = runs.first().style
        val ellipsis = Component.literal(ELLIPSIS).withStyle(emptyPrefixStyle)
        if (measureWidth.applyAsInt(ellipsis) > maximumWidth) return Component.empty()

        val prefix = ArrayList<StyledText>()
        for (run in runs) {
            var offset = 0
            while (offset < run.text.length) {
                val next = offset + Character.charCount(Character.codePointAt(run.text, offset))
                addStyled(prefix, run.text.substring(offset, next), run.style)
                val ellipsisStyle = prefix.last().style
                if (measureWidth.applyAsInt(build(prefix, ellipsisStyle)) > maximumWidth) {
                    removeLastCodePoint(prefix)
                    return build(prefix, if (prefix.isEmpty()) emptyPrefixStyle else prefix.last().style)
                }
                offset = next
            }
        }
        return build(prefix, if (prefix.isEmpty()) emptyPrefixStyle else prefix.last().style)
    }

    private fun build(prefix: List<StyledText>, ellipsisStyle: Style): Component {
        val result: MutableComponent = Component.empty()
        for (run in prefix) result.append(Component.literal(run.text).withStyle(run.style))
        result.append(Component.literal(ELLIPSIS).withStyle(ellipsisStyle))
        return result
    }

    private fun addStyled(output: MutableList<StyledText>, text: String, style: Style) {
        val last = output.lastIndex
        if (last >= 0 && output[last].style == style) {
            val previous = output[last]
            output[last] = StyledText(previous.text + text, style)
        } else {
            output.add(StyledText(text, style))
        }
    }

    private fun removeLastCodePoint(output: MutableList<StyledText>) {
        val last = output.lastIndex
        if (last < 0) return
        val previous = output[last]
        val end = previous.text.offsetByCodePoints(previous.text.length, -1)
        if (end == 0) output.removeAt(last)
        else output[last] = StyledText(previous.text.substring(0, end), previous.style)
    }

    private data class StyledText(val text: String, val style: Style)
}
