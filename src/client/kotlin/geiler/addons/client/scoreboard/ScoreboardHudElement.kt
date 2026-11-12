package geiler.addons.client.scoreboard

import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.impl.ScoreboardModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Movable HUD adapter; all content and styling come from the feature-local layout. */
object ScoreboardHudElement : HudElement {
    override fun id(): String = "custom_scoreboard"

    override fun displayName(): String = "Custom Scoreboard"

    override fun width(font: Font?): Int =
        ScoreboardLayoutRenderer.measure(ScoreboardBackend.layout(), measurementSnapshot(), font!!).scaledWidth

    override fun height(font: Font?): Int =
        ScoreboardLayoutRenderer.measure(ScoreboardBackend.layout(), measurementSnapshot(), font!!).scaledHeight

    override fun visible(): Boolean {
        if (!ScoreboardModule.INSTANCE.isEnabled()) return false
        if (ScoreboardBackend.snapshot().active()) return true
        return ScoreboardBackend.layout().lines().any { line ->
            line.enabled() && (line.type() == ScoreboardLine.Type.CUSTOM_TEXT || line.type() == ScoreboardLine.Type.SPACER)
        }
    }

    override fun previewMode(): String = if (ScoreboardBackend.snapshot().active()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        ScoreboardLayoutRenderer.render(graphics, font, ScoreboardBackend.layout(), ScoreboardBackend.snapshot(), x, y)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        ScoreboardLayoutRenderer.render(graphics, font, ScoreboardBackend.layout(), previewSnapshot(), x, y)
    }

    private fun measurementSnapshot(): ScoreboardSnapshot = previewSnapshot()

    private fun previewSnapshot(): ScoreboardSnapshot {
        val current = ScoreboardBackend.snapshot()
        return if (current.active()) current else ScoreboardSnapshot.preview()
    }
}
