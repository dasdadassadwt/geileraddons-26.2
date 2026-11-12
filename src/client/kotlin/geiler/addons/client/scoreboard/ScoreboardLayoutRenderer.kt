package geiler.addons.client.scoreboard

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.presentation.ComponentWidthLimiter
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import java.util.ArrayList
import java.util.function.ToIntFunction

/** Shared renderer used by the live HUD and the layout editor preview. */
class ScoreboardLayoutRenderer private constructor() {
    @JvmRecord
    data class Metrics(val box: ScoreboardGeometry.Box, val scaledWidth: Int, val scaledHeight: Int)

    private data class Row(
        val left: Component,
        val right: Component?,
        val title: Boolean,
        val spacer: Boolean,
    )

    companion object {
        private const val DEFAULT_FONT_COLOR = 0xFFFFFFFF.toInt()

        @JvmStatic
        fun measure(layout: ScoreboardLayout?, snapshot: ScoreboardSnapshot?, font: Font?): Metrics {
            if (layout == null || font == null) return Metrics(ScoreboardGeometry.Box(1, 1, 1, 0), 1, 1)
            val rows = rows(layout, snapshot ?: ScoreboardSnapshot.empty())
            val fontDescription = fontDescription(layout)
            val textWidth = measureTextWidth(rows, font, fontDescription)
            val box = ScoreboardGeometry.measure(
                rows.size,
                textWidth,
                layout.width(),
                layout.height(),
                font.lineHeight,
                layout.padding(),
                layout.lineSpacing(),
            )
            return Metrics(
                box,
                maxOf(1, kotlin.math.ceil(box.width * layout.scale()).toInt()),
                maxOf(1, kotlin.math.ceil(box.height * layout.scale()).toInt()),
            )
        }

        @JvmStatic
        fun render(
            graphics: GuiGraphicsExtractor,
            font: Font?,
            layout: ScoreboardLayout?,
            snapshot: ScoreboardSnapshot?,
            x: Int,
            y: Int,
        ) {
            if (layout == null || font == null) return
            layout.normalize()
            val rows = rows(layout, snapshot ?: ScoreboardSnapshot.empty())
            val fontDescription = fontDescription(layout)
            val textWidth = measureTextWidth(rows, font, fontDescription)
            val box = ScoreboardGeometry.measure(
                rows.size,
                textWidth,
                layout.width(),
                layout.height(),
                font.lineHeight,
                layout.padding(),
                layout.lineSpacing(),
            )
            val scale = layout.scale()
            val screenWidth = maxOf(1, kotlin.math.ceil(box.width * scale).toInt())
            val screenHeight = maxOf(1, kotlin.math.ceil(box.height * scale).toInt())
            graphics.enableScissor(x, y, x + screenWidth, y + screenHeight)
            graphics.pose().pushMatrix()
            graphics.pose().translate(x.toFloat(), y.toFloat())
            graphics.pose().scale(scale, scale)
            drawBackground(graphics, layout, box.width, box.height)
            for ((index, row) in rows.withIndex()) {
                if (row.spacer) continue
                val rowY = ScoreboardGeometry.rowY(box, index)
                val left = styled(row.left, fontDescription)
                val contentWidth = maxOf(0, box.width - box.padding * 2)
                if (row.title) {
                    val fitted = ComponentWidthLimiter.fit(left, contentWidth, textMeasure(font)) ?: Component.empty()
                    graphics.text(
                        font,
                        fitted,
                        maxOf(box.padding, (box.width - font.width(fitted)) / 2),
                        rowY,
                        DEFAULT_FONT_COLOR,
                    )
                } else {
                    val right = row.right?.let { styled(it, fontDescription) }
                    val fitted = ScoreboardTextLayout.fit(left, right, contentWidth, 7, textMeasure(font))
                    graphics.text(font, fitted.left, box.padding, rowY, DEFAULT_FONT_COLOR)
                    if (fitted.right != null) {
                        graphics.text(
                            font,
                            fitted.right,
                            maxOf(box.padding, box.width - box.padding - fitted.rightWidth),
                            rowY,
                            DEFAULT_FONT_COLOR,
                        )
                    }
                }
            }
            graphics.pose().popMatrix()
            graphics.disableScissor()
        }

        private fun textMeasure(font: Font): ToIntFunction<Component> = ToIntFunction { component -> font.width(component) }

        private fun measureTextWidth(rows: List<Row>, font: Font, fontDescription: FontDescription.Resource): Int {
            var textWidth = 0
            for (row in rows) {
                var width = font.width(styled(row.left, fontDescription))
                if (row.right != null) width += 7 + font.width(styled(row.right, fontDescription))
                textWidth = maxOf(textWidth, width)
            }
            return textWidth
        }

        private fun styled(component: Component, fontDescription: FontDescription.Resource): MutableComponent =
            component.copy().withStyle { style ->
                style.withFont(fontDescription).withColor(DEFAULT_FONT_COLOR).withBold(false)
            }

        private fun fontDescription(layout: ScoreboardLayout): FontDescription.Resource {
            var fontIdentifier = Identifier.tryParse(layout.fontId().orEmpty())
            if (fontIdentifier == null) fontIdentifier = Identifier.withDefaultNamespace("default")
            return FontDescription.Resource(fontIdentifier)
        }

        private fun rows(layout: ScoreboardLayout?, snapshot: ScoreboardSnapshot): List<Row> {
            val rows = ArrayList<Row>()
            if (layout == null) return rows
            for (line in layout.lines()) {
                when (line.type()) {
                    ScoreboardLine.Type.OBJECTIVE_TITLE -> {
                        if (snapshot.active()) rows.add(Row(snapshot.title(), null, true, false))
                    }
                    ScoreboardLine.Type.SCORE_ENTRY -> {
                        val index = line.sourceIndex()
                        if (snapshot.active() && index >= 0 && index < snapshot.entries().size) {
                            val entry = snapshot.entries()[index]
                            rows.add(Row(entry.label(), entry.value(), false, false))
                        }
                    }
                    ScoreboardLine.Type.CUSTOM_TEXT -> rows.add(
                        Row(Component.literal(line.text() ?: ""), null, false, false),
                    )
                    ScoreboardLine.Type.SPACER -> rows.add(Row(Component.empty(), null, false, true))
                    null -> Unit
                }
            }
            return rows
        }

        private fun drawBackground(graphics: GuiGraphicsExtractor, layout: ScoreboardLayout, width: Int, height: Int) {
            val style = layout.background()
            if (style == ScoreboardLayout.BackgroundStyle.NONE) return
            val color = layout.backgroundColor()
            val rounded = style == ScoreboardLayout.BackgroundStyle.ROUNDED
                || style == ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED
            val glass = style == ScoreboardLayout.BackgroundStyle.GLASS
                || style == ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED
            val top = color
            val bottom = if (glass) darken(color, 0.72f) else color
            val radius = if (rounded) minOf(layout.cornerRadius(), minOf(width, height) / 2) else 0
            if (layout.borderWidth() > 0) {
                if (rounded) {
                    GuiTheme.roundedRectBordered(
                        graphics,
                        0,
                        0,
                        width,
                        height,
                        radius,
                        top,
                        bottom,
                        layout.borderColor(),
                        layout.borderWidth(),
                    )
                } else {
                    graphics.fillGradient(0, 0, width, height, top, bottom)
                    drawSquareBorder(graphics, width, height, layout.borderColor(), layout.borderWidth())
                }
            } else if (rounded) {
                GuiTheme.roundedRect(graphics, 0, 0, width, height, radius, top, bottom)
            } else {
                graphics.fillGradient(0, 0, width, height, top, bottom)
            }
        }

        private fun darken(color: Int, factor: Float): Int {
            val alpha = color and 0xFF000000.toInt()
            val red = java.lang.Math.round(((color ushr 16) and 0xFF) * factor)
            val green = java.lang.Math.round(((color ushr 8) and 0xFF) * factor)
            val blue = java.lang.Math.round((color and 0xFF) * factor)
            return alpha or (red shl 16) or (green shl 8) or blue
        }

        private fun drawSquareBorder(
            graphics: GuiGraphicsExtractor,
            width: Int,
            height: Int,
            color: Int,
            borderWidth: Int,
        ) {
            val thickness = minOf(borderWidth, minOf(width, height) / 2)
            if (thickness <= 0) return
            graphics.fill(0, 0, width, thickness, color)
            graphics.fill(0, height - thickness, width, height, color)
            graphics.fill(0, thickness, thickness, height - thickness, color)
            graphics.fill(width - thickness, thickness, width, height - thickness, color)
        }

        @JvmStatic
        fun defaultTextColor(): Int = DEFAULT_FONT_COLOR
    }
}
