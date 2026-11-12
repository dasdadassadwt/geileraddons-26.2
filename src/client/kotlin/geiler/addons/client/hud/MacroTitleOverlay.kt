package geiler.addons.client.hud

import geiler.addons.client.macro.MacroStep
import geiler.addons.client.macro.MacroTitleTiming
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier

/** One transient, centered macro title. A later title replaces the one currently being shown. */
object MacroTitleOverlay {
    private var current: State? = null

    @JvmStatic
    fun show(title: MacroStep.Title?) {
        if (title == null || title.text().isBlank()) return
        val now = System.nanoTime()
        val total = title.fadeInMillis().toLong() + title.holdMillis() + title.fadeOutMillis()
        current = State(
            title.text(), title.font(), title.scale(), title.textColor(),
            title.showBackground(), title.backgroundColor(), title.backgroundOpacity(),
            title.fadeInMillis(), title.holdMillis(), title.fadeOutMillis(), now,
            now + maxOf(1L, total) * 1_000_000L,
        )
    }

    @JvmStatic
    fun render(graphics: GuiGraphicsExtractor) {
        val state = current ?: return
        val now = System.nanoTime()
        if (now >= state.expiresAt) {
            current = null
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden() || minecraft.font == null) return
        val alpha = opacity(state, now)
        val fontId = Identifier.tryParse(state.font)
        var title: MutableComponent = Component.literal(state.text)
        if (fontId != null) {
            title = title.withStyle { style -> style.withFont(FontDescription.Resource(fontId)) }
        }
        val font: Font = minecraft.font
        val scale = state.scale
        val textWidth = font.width(title) * scale
        val textHeight = font.lineHeight * scale
        val x = (minecraft.window.guiScaledWidth - textWidth) / 2.0f
        val y = (minecraft.window.guiScaledHeight - textHeight) / 2.0f
        val paddingX = 12.0f * scale
        val paddingY = 6.0f * scale
        if (state.showBackground && state.backgroundOpacity > 0) {
            val backgroundAlpha = Math.round(state.backgroundOpacity * alpha)
            val background = (backgroundAlpha shl 24) or (state.backgroundColor and 0x00FFFFFF)
            graphics.fill(
                (x - paddingX).toInt(), (y - paddingY).toInt(),
                (x + textWidth + paddingX).toInt(), (y + textHeight + paddingY).toInt(), background,
            )
        }
        val text = withAlpha(state.textColor, alpha)
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        graphics.pose().scale(scale, scale)
        graphics.text(font, title, 0, 0, text)
        graphics.pose().popMatrix()
    }

    private fun opacity(state: State, now: Long): Float {
        val elapsed = maxOf(0L, (now - state.startedAt) / 1_000_000L)
        return MacroTitleTiming.opacity(elapsed, state.fadeInMillis, state.holdMillis, state.fadeOutMillis)
    }

    private fun withAlpha(color: Int, multiplier: Float): Int {
        val alpha = Math.round((color ushr 24) * maxOf(0.0f, minOf(1.0f, multiplier)))
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private data class State(
        val text: String,
        val font: String,
        val scale: Float,
        val textColor: Int,
        val showBackground: Boolean,
        val backgroundColor: Int,
        val backgroundOpacity: Int,
        val fadeInMillis: Int,
        val holdMillis: Int,
        val fadeOutMillis: Int,
        val startedAt: Long,
        val expiresAt: Long,
    )
}
