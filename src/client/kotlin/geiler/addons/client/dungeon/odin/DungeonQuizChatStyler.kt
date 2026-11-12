package geiler.addons.client.dungeon.odin

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.util.ArrayList
import java.util.Optional

/** Highlights only the confirmed option marker while preserving every other chat style. */
object DungeonQuizChatStyler {
    private const val CORRECT_MARKER_COLOR = 0x55FF55

    @JvmStatic
    fun apply(source: Component?, marker: String?, answer: String?): Component? {
        if (source == null || marker.isNullOrEmpty() || answer.isNullOrEmpty()) return source
        val visibleText = source.string
        if (!visibleText.startsWith(marker) || !visibleText.endsWith(answer) || visibleText.length <= marker.length) {
            return source
        }

        val runs = ArrayList<StyledRun>()
        source.visit({ style, text ->
            if (text.isNotEmpty()) runs.add(StyledRun(text, style))
            Optional.empty<Void>()
        }, Style.EMPTY)
        if (runs.isEmpty() || !runs.first().text.startsWith(marker)) return source

        val output = Component.empty()
        var markerPending = true
        for (run in runs) {
            if (markerPending) {
                if (run.text.length < marker.length) return source
                output.append(Component.literal(marker).withStyle(
                    run.style.withColor(TextColor.fromRgb(CORRECT_MARKER_COLOR)),
                ))
                if (run.text.length > marker.length) {
                    output.append(Component.literal(run.text.substring(marker.length)).withStyle(run.style))
                }
                markerPending = false
            } else {
                output.append(Component.literal(run.text).withStyle(run.style))
            }
        }
        return if (markerPending) source else output
    }

    private data class StyledRun(val text: String, val style: Style)
}
