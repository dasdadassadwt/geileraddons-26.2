package geiler.addons.client.gui

import geiler.addons.client.module.ChoiceSetting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The two looks shared by the inventory-style screens (Storage Overlay, Inventory HUD, Garden Plots): the mod's Click
 * GUI theme, or flat vanilla container greys. Every helper takes [mc] (true = Minecraft look) and an optional
 * [opacity] for panels drawn over the world.
 */
object InventorySurfaces {
    const val STYLE_CLICK_GUI = "CLICK_GUI"
    const val STYLE_MINECRAFT = "MINECRAFT"

    /** A fresh "Style" choice; the saved keys are the constants above. */
    @JvmStatic
    fun styleSetting() = ChoiceSetting(
        "Style", STYLE_CLICK_GUI,
        mapOf(STYLE_CLICK_GUI to "Click GUI", STYLE_MINECRAFT to "Minecraft"),
        STYLE_CLICK_GUI, STYLE_MINECRAFT,
    )

    @JvmStatic
    fun isMinecraft(style: String?): Boolean = style == STYLE_MINECRAFT

    @JvmStatic
    fun labelColor(mc: Boolean): Int = if (mc) 0xFF404040.toInt() else GuiTheme.TEXT_PRIMARY

    @JvmStatic
    fun fade(color: Int, opacity: Float): Int =
        if (opacity >= 1f) color else (((color ushr 24) * opacity.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)

    @JvmStatic
    @JvmOverloads
    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, mc: Boolean, opacity: Float = 1f, highlight: Boolean = true) {
        if (mc) {
            bevel(g, x, y, w, h, fade(0xFFC6C6C6.toInt(), opacity), 3, opacity)
            g.outline(x - 1, y - 1, w + 2, h + 2, fade(0xFF000000.toInt(), opacity))
        } else {
            GuiTheme.roundedRectBordered(
                g, x, y, w, h, GuiTheme.RADIUS, fade(GuiTheme.PANEL_TOP, opacity), fade(GuiTheme.PANEL_BOTTOM, opacity), fade(GuiTheme.BORDER, opacity),
            )
            if (highlight) g.fill(x + GuiTheme.RADIUS, y + 1, x + w - GuiTheme.RADIUS, y + 2, fade(GuiTheme.PANEL_HIGHLIGHT, opacity))
        }
    }

    /** A card inside a panel; [open] outlines the one that is currently selected. */
    @JvmStatic
    @JvmOverloads
    fun card(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, open: Boolean, mc: Boolean, opacity: Float = 1f) {
        if (mc) {
            bevel(g, x, y, w, h, fade(0xFFC6C6C6.toInt(), opacity), 2, opacity)
            if (open) g.outline(x - 1, y - 1, w + 2, h + 2, 0xFFFFFF55.toInt())
        } else {
            GuiTheme.roundedRectBordered(
                g, x, y, w, h, GuiTheme.RADIUS_SMALL, fade(GuiTheme.CARD_BG, opacity), fade(GuiTheme.CARD_BG, opacity), fade(GuiTheme.CARD_BORDER, opacity),
            )
            if (open) GuiTheme.roundedRectOutline(g, x - 1, y - 1, w + 2, h + 2, GuiTheme.RADIUS_SMALL + 1, GuiTheme.SLIDER_FILL)
        }
    }

    /** A raised vanilla-style surface: light top-left edge, dark bottom-right edge. */
    @JvmStatic
    @JvmOverloads
    fun bevel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, face: Int, edge: Int, opacity: Float = 1f) {
        g.fill(x, y, x + w, y + h, fade(0xFF555555.toInt(), opacity))
        g.fill(x, y, x + w - edge, y + h - edge, fade(0xFFFFFFFF.toInt(), opacity))
        g.fill(x + edge, y + edge, x + w - edge, y + h - edge, face)
    }

    /** One 16x16 item slot whose top-left item pixel is ([x], [y]). */
    @JvmStatic
    @JvmOverloads
    fun slot(g: GuiGraphicsExtractor, x: Int, y: Int, mc: Boolean, opacity: Float = 1f) {
        if (mc) {
            g.fill(x - 1, y - 1, x + 17, y + 17, fade(0xFF373737.toInt(), opacity))
            g.fill(x, y, x + 17, y + 17, fade(0xFFFFFFFF.toInt(), opacity))
            g.fill(x, y, x + 16, y + 16, fade(0xFF8B8B8B.toInt(), opacity))
        } else {
            g.fill(x - 1, y - 1, x + 17, y + 17, fade(GuiTheme.CARD_BG, opacity))
        }
    }

    @JvmStatic
    fun button(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, w: Int, h: Int, label: String, hover: Boolean, mc: Boolean) {
        val textY = y + (h - font.lineHeight) / 2 + 1
        if (mc) {
            g.fill(x, y, x + w, y + h, 0xFF000000.toInt())
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, if (hover) 0xFF7A86C8.toInt() else 0xFF6F6F6F.toInt())
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFA8A8A8.toInt())
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF3A3A3A.toInt())
            g.text(font, label, x + (w - font.width(label)) / 2, textY, 0xFFFFFFFF.toInt(), true)
        } else {
            GuiTheme.roundedRect(g, x, y, w, h, GuiTheme.RADIUS_SMALL, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
            g.text(font, label, x + (w - font.width(label)) / 2, textY, if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY, false)
        }
    }
}
