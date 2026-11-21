package geiler.addons.client.dungeon.helpers

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.util.ArrayList
import java.util.Optional

/** Recolors the whole confirmed option line red while preserving every other style (click events, bold, ...). */
object DungeonQuizChatStyler {
    private const val CORRECT_LINE_COLOR = 0xFF5555
    private val LEGACY_CODE = Regex("(?i)§[0-9A-FK-OR]")

    @JvmStatic
    fun apply(source: Component?, marker: String?, answer: String?): Component? {
        if (source == null || marker.isNullOrEmpty() || answer.isNullOrEmpty()) return source
        // Live lines are indented and may carry literal legacy codes ("     §6 ⓐ §a7 Fairy Souls").
        val visibleText = LEGACY_CODE.replace(source.string, "").trim()
        if (!visibleText.startsWith(marker) || !visibleText.endsWith(answer) || visibleText.length <= marker.length) {
            return source
        }

        val runs = ArrayList<StyledRun>()
        source.visit({ style, text ->
            val clean = LEGACY_CODE.replace(text, "")
            if (clean.isNotEmpty()) runs.add(StyledRun(clean, style))
            Optional.empty<Void>()
        }, Style.EMPTY)
        if (runs.isEmpty()) return source

        val output = Component.empty()
        for (run in runs) {
            output.append(Component.literal(run.text).withStyle(run.style.withColor(TextColor.fromRgb(CORRECT_LINE_COLOR))))
        }
        return output
    }

    private data class StyledRun(val text: String, val style: Style)
}
