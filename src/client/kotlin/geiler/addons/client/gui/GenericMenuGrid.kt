package geiler.addons.client.gui

import geiler.addons.client.bazaar.BazaarMenus
import geiler.addons.client.module.impl.rarityItem
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ChestMenu
import kotlin.math.max
import kotlin.math.min

/**
 * The themed view of a chest menu that has no page of its own (AH and Bazaar side pages, the dungeon menus around the
 * Party Finder): every non-filler item as a card on the menu's own 9-wide grid with its name under it. The caller
 * turns each returned card into a click on its real slot.
 */
object GenericMenuGrid {
    class Card(val x: Int, val y: Int, val w: Int, val h: Int, val slot: Int) {
        fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
    }

    /** Panel bounds of the last draw: x, y, w, h. */
    var panel = IntArray(4)
        private set

    /** Draws the panel, title and cards; returns the cards. The header's right side is left free for the caller's buttons. */
    fun draw(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, title: String, mouseX: Int, mouseY: Int): List<Card> {
        val font = Minecraft.getInstance().font
        val rows = (screen.menu as? ChestMenu)?.rowCount ?: return emptyList()
        val used = (0 until min(rows * 9, screen.menu.slots.size)).filter {
            val s = screen.menu.slots[it].item
            !s.isEmpty && !BazaarMenus.isFiller(BuiltInRegistries.ITEM.getKey(s.item).toString(), SkyblockItems.realName(s))
        }
        val firstRow = used.minOfOrNull { it / 9 } ?: 0
        val lastRow = used.maxOfOrNull { it / 9 } ?: 0
        val w = 9 * CELL_W + 16
        val h = min((lastRow - firstRow + 1) * CELL_H + 32, screen.height - 12)
        val x = (screen.width - w) / 2
        val y = max(6, (screen.height - h) / 2)
        panel = intArrayOf(x, y, w, h)
        GuiTheme.roundedRectBordered(graphics, x, y, w, h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "§l" + GuiTheme.fit(font, title.replace("➜", "›"), w - 120), x + 8, y + 7, GuiTheme.TEXT_PRIMARY, false)
        val cards = ArrayList<Card>()
        for (slot in used) {
            val s = screen.menu.slots[slot].item
            val c = Card(x + 8 + (slot % 9) * CELL_W, y + 24 + (slot / 9 - firstRow) * CELL_H, CELL_W - 3, CELL_H - 3, slot)
            if (c.y + c.h > y + h) continue
            val hover = c.contains(mouseX.toDouble(), mouseY.toDouble())
            graphics.fill(c.x, c.y, c.x + c.w, c.y + c.h, if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.rarityItem(s, c.x + (c.w - 16) / 2, c.y + 3)
            graphics.itemDecorations(font, s, c.x + (c.w - 16) / 2, c.y + 3)
            label(graphics, font, geiler.addons.client.dungeon.PartyFinderEntries.shortLabel(SkyblockItems.realName(s)), c)
            if (hover) graphics.setTooltipForNextFrame(font, s, mouseX, mouseY)
            cards.add(c)
        }
        return cards
    }

    /** The item name under a card in small text, wrapped onto up to two lines so it is not cut to "Buy a ...". */
    private fun label(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font, name: String, c: Card) {
        val width = ((c.w - 2) / LABEL_SCALE).toInt()
        val lines = wrap(font, name, width)
        graphics.pose().pushMatrix()
        graphics.pose().translate((c.x + c.w / 2).toFloat(), (c.y + 21).toFloat())
        graphics.pose().scale(LABEL_SCALE, LABEL_SCALE)
        for ((i, line) in lines.withIndex()) graphics.centeredText(font, line, 0, i * 9, GuiTheme.TEXT_SECONDARY)
        graphics.pose().popMatrix()
    }

    /** Words onto at most two lines of [width]; whatever is left is cut on the second line. */
    @JvmStatic
    fun wrap(font: net.minecraft.client.gui.Font, text: String, width: Int): List<String> {
        if (font.width(text) <= width) return listOf(text)
        val words = text.split(' ')
        var first = ""
        var i = 0
        while (i < words.size) {
            val next = if (first.isEmpty()) words[i] else "$first ${words[i]}"
            if (font.width(next) > width && first.isNotEmpty()) break
            first = next
            i++
        }
        val rest = words.drop(i).joinToString(" ")
        return if (rest.isEmpty()) listOf(GuiTheme.fit(font, first, width)) else listOf(GuiTheme.fit(font, first, width), GuiTheme.fit(font, rest, width))
    }

    private const val LABEL_SCALE = 0.75f
    private const val CELL_W = 46
    private const val CELL_H = 42
}
