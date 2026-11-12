package geiler.addons.client.hud

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/** Routes short module alerts through Minecraft's fixed-position title line. */
object TitleAlertStack {
    /** Each new alert replaces the current title, matching vanilla title behavior. */
    @Suppress("UNUSED_PARAMETER")
    fun submit(source: String?, text: String?, durationTicks: Int, colorArgb: Int) {
        if (text.isNullOrBlank()) return
        val client = Minecraft.getInstance()

        val duration = durationTicks.coerceIn(1, 1200)
        var title: MutableComponent = Component.literal(text)
        if ((colorArgb ushr 24) != 0 || (colorArgb and 0x00FFFFFF) != 0x00FFFFFF) {
            title = title.withStyle { style -> style.withColor(TextColor.fromRgb(colorArgb and 0x00FFFFFF)) }
        }
        client.gui.hud.setTimes(0, duration, 0)
        client.gui.hud.setTitle(title)
    }

    /** Countdown overlays intentionally use the subtitle line beneath Minecraft's title. */
    @Suppress("UNUSED_PARAMETER")
    fun submitSubtitle(source: String?, text: String?, durationTicks: Int, colorArgb: Int) {
        if (text.isNullOrBlank()) return
        val client = Minecraft.getInstance()

        val duration = durationTicks.coerceIn(1, 1200)
        var subtitle: MutableComponent = Component.literal(text)
        if ((colorArgb ushr 24) != 0 || (colorArgb and 0x00FFFFFF) != 0x00FFFFFF) {
            subtitle = subtitle.withStyle { style -> style.withColor(TextColor.fromRgb(colorArgb and 0x00FFFFFF)) }
        }
        client.gui.hud.setTimes(0, duration, 0)
        client.gui.hud.setSubtitle(subtitle)
    }

    /** Clear requests are intentionally ignored because clearing could erase another module's alert. */
    @Suppress("UNUSED_PARAMETER")
    fun clearSource(source: String?) { }

    fun clear() { }
}
