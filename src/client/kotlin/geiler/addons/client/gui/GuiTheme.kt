package geiler.addons.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Shared palette and panel drawing for the mod's screens, so they look like one GUI.
 *
 * The palette is derived from five colours rather than stored as thirty. Every hover tint,
 * card fill and slider track is a function of those five, so a theme cannot be half-applied.
 * [geiler.addons.client.module.impl.VisualModule] owns the five and calls [apply].
 */
object GuiTheme {
    const val RADIUS = 8
    /** Corner radius for the small stuff: rows, cards, swatches, and buttons. */
    const val RADIUS_SMALL = 5

    /** Meaning, not decoration: a warning stops reading as one if the theme can recolour it. */
    const val TEXT_ERROR = -38_037 // 0xFFFF6B6B
    const val TEXT_WARN = -15_012 // 0xFFFFC55C
    const val DANGER_BG = -8_773_845 // 0xFF7A1F2B
    const val DANGER_HOVER = -4_181_953 // 0xFFC0303F
    /** Dims the screen behind a modal dialog. */
    const val DIALOG_SHADE = -1_342_177_280 // 0xB0000000
    const val SLIDER_TRACK = -1_610_612_736 // 0xA0000000

    @JvmField var PANEL_TOP = 0
    @JvmField var PANEL_BOTTOM = 0
    @JvmField var MODULE_PANEL_TOP = 0
    @JvmField var MODULE_PANEL_BOTTOM = 0
    @JvmField var BORDER = 0
    @JvmField var CATEGORY_SELECTED = 0
    @JvmField var CATEGORY_HOVER = 0
    /** Card background: raised just enough off the panel to read as a separate surface. */
    @JvmField var CARD_BG = 0
    @JvmField var CARD_BG_HOVER = 0
    @JvmField var CARD_BG_ENABLED = 0
    @JvmField var CARD_BORDER = 0
    @JvmField var CARD_BORDER_ENABLED = 0
    @JvmField var GROUP_HEADER = 0
    @JvmField var TEXT_PRIMARY = 0
    @JvmField var TEXT_SECONDARY = 0
    @JvmField var TEXT_MUTED = 0
    /** Readable on top of the accent, whichever way the accent's brightness went. */
    @JvmField var TEXT_ON_ACCENT = 0
    @JvmField var SLIDER_FILL = 0
    @JvmField var SWITCH_OFF = 0
    @JvmField var SWITCH_ON = 0
    /** The switch ring is derived from text so it stays visible over both track colours. */
    @JvmField var SWITCH_BORDER = 0
    @JvmField var SWITCH_KNOB = 0
    /** A solid outline that stays distinct from the filled shape beneath it. */
    @JvmField var SELECTION_OUTLINE = 0
    @JvmField var BUTTON_BG = 0
    @JvmField var BUTTON_HOVER = 0
    @JvmField var SCROLLBAR = 0
    /** A quiet accent line used to give the two panels a deliberate top edge. */
    @JvmField var PANEL_HIGHLIGHT = 0

    private const val MIN_OUTLINE_CONTRAST = 0.35f
    private const val MIN_LABEL_CONTRAST = 0.25f

    init {
        // A palette must exist before config is read in case a screen is drawn first.
        apply(0x810B1220.toInt(), 0xFF191D24.toInt(), 0xFF3B82F6.toInt(),
            0xFFEAF2FF.toInt(), 0xFF7E8FA8.toInt())
    }

    /** Rebuilds the palette from the five colours a theme is made of. */
    @JvmStatic
    fun apply(background: Int, border: Int, accent: Int, text: Int, muted: Int) {
        PANEL_TOP = background
        PANEL_BOTTOM = shade(background, -0.22f)
        MODULE_PANEL_TOP = shade(background, 0.10f)
        MODULE_PANEL_BOTTOM = shade(background, -0.10f)
        // Preserve the alpha selected in the theme settings. Several presets intentionally use a
        // glassy, low-opacity outline; forcing it opaque makes every panel edge much brighter.
        BORDER = border

        CATEGORY_SELECTED = opaque(accent)
        CATEGORY_HOVER = withAlpha(accent, 18)
        // Overlays are tinted from text rather than hardcoded white, so a light theme gets darker cards.
        CARD_BG = withAlpha(text, 11)
        CARD_BG_HOVER = withAlpha(text, 22)
        CARD_BG_ENABLED = withAlpha(accent, 22)
        CARD_BORDER = withAlpha(border, alpha(border) / 2)
        CARD_BORDER_ENABLED = opaque(accent)
        GROUP_HEADER = withAlpha(text, 12)

        TEXT_PRIMARY = opaque(text)
        TEXT_SECONDARY = lerpColor(opaque(text), opaque(muted), 0.5f)
        TEXT_MUTED = opaque(muted)
        TEXT_ON_ACCENT = if (luminance(accent) > 0.55f) 0xFF101014.toInt() else -1

        SLIDER_FILL = opaque(accent)
        SWITCH_OFF = opaque(shade(background, 0.45f))
        SWITCH_ON = opaque(accent)
        SWITCH_BORDER = withAlpha(text, 150)
        SWITCH_KNOB = opaque(text)
        BUTTON_BG = opaque(shade(accent, -0.38f))
        BUTTON_HOVER = opaque(accent)
        SCROLLBAR = withAlpha(text, 96)
        PANEL_HIGHLIGHT = withAlpha(accent, 150)
        SELECTION_OUTLINE = selectionOutline(accent)
    }

    /** A selection outline that cannot disappear into the thing it selects. */
    @JvmStatic
    fun selectionOutline(reference: Int): Int =
        if (luminance(reference) > 0.5f) 0xFF000000.toInt() else -1

    /** Whether the outline a shape should draw is far enough from its body colour to be seen. */
    @JvmStatic
    fun outlinesReadably(body: Int, outline: Int): Boolean =
        kotlin.math.abs(luminance(body) - luminance(outline)) >= MIN_OUTLINE_CONTRAST

    /** Whether a label drawn on a filled surface is far enough from it to be read. */
    @JvmStatic
    fun labelsReadably(text: Int, fill: Int): Boolean =
        kotlin.math.abs(luminance(text) - luminance(fill)) >= MIN_LABEL_CONTRAST

    /** A tint for the search glyph that remains distinct from the search field on every theme. */
    @JvmStatic
    fun searchIconColor(): Int {
        val surface = compositeOver(GROUP_HEADER, compositeOver(PANEL_TOP, 0xFF000000.toInt()))
        if (labelsReadably(TEXT_PRIMARY, surface)) return TEXT_PRIMARY
        if (labelsReadably(TEXT_SECONDARY, surface)) return TEXT_SECONDARY
        return if (luminance(surface) > 0.5f) 0xFF000000.toInt() else -1
    }

    private fun compositeOver(foreground: Int, background: Int): Int {
        val a = alpha(foreground)
        val inverse = 255 - a
        val r = (channel(foreground, 16) * a + channel(background, 16) * inverse + 127) / 255
        val g = (channel(foreground, 8) * a + channel(background, 8) * inverse + 127) / 255
        val b = (channel(foreground, 0) * a + channel(background, 0) * inverse + 127) / 255
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /** Toward white for a positive amount, toward black for a negative one; alpha is kept. */
    private fun shade(color: Int, amount: Float): Int {
        var r = channel(color, 16)
        var g = channel(color, 8)
        var b = channel(color, 0)
        if (amount >= 0f) {
            r += java.lang.Math.round((255 - r) * amount)
            g += java.lang.Math.round((255 - g) * amount)
            b += java.lang.Math.round((255 - b) * amount)
        } else {
            r += java.lang.Math.round(r * amount)
            g += java.lang.Math.round(g * amount)
            b += java.lang.Math.round(b * amount)
        }
        return (color and 0xFF000000.toInt()) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    }

    private fun withAlpha(color: Int, newAlpha: Int): Int =
        (clamp(newAlpha) shl 24) or (color and 0x00FFFFFF)

    /** Applies animation opacity without changing hue or accidentally brightening a colour. */
    @JvmStatic
    fun withOpacity(color: Int, opacity: Float): Int {
        val oldAlpha = alpha(color)
        val newAlpha = java.lang.Math.round(oldAlpha * java.lang.Math.max(0f, java.lang.Math.min(1f, opacity)))
        return (clamp(newAlpha) shl 24) or (color and 0x00FFFFFF)
    }

    private fun opaque(color: Int): Int = 0xFF000000.toInt() or (color and 0x00FFFFFF)
    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF

    /** Perceptual, so yellow counts as light even though its blue channel is small. */
    private fun luminance(color: Int): Float =
        (0.2126f * channel(color, 16) + 0.7152f * channel(color, 8) + 0.0722f * channel(color, 0)) / 255f

    private fun clamp(value: Int): Int = java.lang.Math.max(0, java.lang.Math.min(255, value))

    @JvmStatic
    fun roundedRect(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) =
        roundedRect(graphics, x, y, w, h, radius, color, color)

    /** A rounded rectangle with antialiased corners. */
    @JvmStatic
    fun roundedRect(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        colorTop: Int,
        colorBottom: Int,
    ) {
        if (w <= 0 || h <= 0) return
        val clampedRadius = java.lang.Math.max(0, java.lang.Math.min(radius, java.lang.Math.min(w, h) / 2))
        if (clampedRadius == 0) {
            fillRect(graphics, x, y, w, h, colorTop, colorBottom)
            return
        }
        RoundedRectRenderState.fill(graphics, x, y, w, h, clampedRadius, colorTop, colorBottom)
    }

    /** Rounded rectangle with a one-pixel border, both antialiased. */
    @JvmStatic
    fun roundedRectBordered(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        colorTop: Int,
        colorBottom: Int,
        borderColor: Int,
    ) = roundedRectBordered(graphics, x, y, w, h, radius, colorTop, colorBottom, borderColor, 1)

    /** Rounded rectangle with a border of a chosen width, both antialiased. */
    @JvmStatic
    fun roundedRectBordered(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        colorTop: Int,
        colorBottom: Int,
        borderColor: Int,
        borderWidth: Int,
    ) {
        val width = java.lang.Math.max(1, borderWidth)
        roundedRect(graphics, x, y, w, h, radius, borderColor, borderColor)
        roundedRect(graphics, x + width, y + width, w - width * 2, h - width * 2,
            java.lang.Math.max(0, radius - width), colorTop, colorBottom)
    }

    /** Draws only the antialiased one-pixel perimeter of a rounded rectangle. */
    @JvmStatic
    fun roundedRectOutline(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        if (w <= 2 || h <= 2) {
            roundedRect(graphics, x, y, w, h, radius, color)
            return
        }
        val clampedRadius = java.lang.Math.max(0, java.lang.Math.min(radius, java.lang.Math.min(w, h) / 2))
        if (clampedRadius == 0) {
            graphics.fill(x, y, x + w, y + 1, color)
            graphics.fill(x, y + h - 1, x + w, y + h, color)
            graphics.fill(x, y + 1, x + 1, y + h - 1, color)
            graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
            return
        }
        RoundedRectRenderState.outline(graphics, x, y, w, h, clampedRadius, color)
    }

    /** A pill-shaped on/off switch, the control that enables a module. */
    @JvmStatic
    fun toggleSwitch(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, on: Boolean) =
        toggleSwitch(graphics, x, y, w, h, if (on) 1f else 0f)

    /** Animated switch variant. The track colour and knob position use the same fraction. */
    @JvmStatic
    fun toggleSwitch(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, position: Float) {
        val clampedPosition = java.lang.Math.max(0f, java.lang.Math.min(1f, position))
        val radius = h / 2
        val track = lerpColor(SWITCH_OFF, SWITCH_ON, clampedPosition)
        roundedRectBordered(graphics, x, y, w, h, radius, track, track, SWITCH_BORDER)
        val knobSize = h - 4
        val knobX = java.lang.Math.round(lerp(x + 2f, (x + w - knobSize - 2).toFloat(), clampedPosition))
        roundedRectBordered(graphics, knobX, y + 2, knobSize, knobSize, knobSize / 2,
            SWITCH_KNOB, SWITCH_KNOB, selectionOutline(track))
    }

    private fun fillRect(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        colorTop: Int,
        colorBottom: Int,
    ) {
        if (w <= 0 || h <= 0) return
        if (colorTop == colorBottom) graphics.fill(x, y, x + w, y + h, colorTop)
        else graphics.fillGradient(x, y, x + w, y + h, colorTop, colorBottom)
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    @JvmStatic
    fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val clampedFraction = java.lang.Math.max(0f, java.lang.Math.min(1f, fraction))
        val a1 = (from ushr 24) and 0xFF
        val r1 = (from ushr 16) and 0xFF
        val g1 = (from ushr 8) and 0xFF
        val b1 = from and 0xFF
        val a2 = (to ushr 24) and 0xFF
        val r2 = (to ushr 16) and 0xFF
        val g2 = (to ushr 8) and 0xFF
        val b2 = to and 0xFF
        val a = (a1 + (a2 - a1) * clampedFraction).toInt()
        val r = (r1 + (r2 - r1) * clampedFraction).toInt()
        val g = (g1 + (g2 - g1) * clampedFraction).toInt()
        val b = (b1 + (b2 - b1) * clampedFraction).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Shortens [text] to [maxWidth] pixels with an ellipsis; the one text-fit used by every screen. */
    @JvmStatic
    fun fit(font: Font, text: String?, maxWidth: Int): String {
        if (text == null || maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = font.width(ellipsis)
        if (maxWidth < ellipsisWidth) return font.plainSubstrByWidth(text, maxWidth)
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + ellipsis
    }
}
