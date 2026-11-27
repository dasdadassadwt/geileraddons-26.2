package geiler.addons.client.gui

import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.mixin.AbstractSignEditScreenAccessor
import geiler.addons.client.module.impl.FancyAuctionHouseModule
import geiler.addons.client.module.impl.rarityItem
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.util.Locale

/**
 * Fancy Auction House: the Hypixel sign prompts (Bazaar amount or price, AH bid, AH search) drawn as a themed dialog
 * over the vanilla sign editor. Typing still goes into the real sign, and Done closes it the vanilla way, so what the
 * server receives is unchanged. The AH search shows matching items from the item list; clicking one fills the query.
 */
object FancySignOverlay {
    enum class Kind { SEARCH, AMOUNT }

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit) {
        fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
    }

    private val hits = ArrayList<Hit>()

    /** The sign editor this overlay replaces; its vanilla sign and title are not drawn (see AbstractSignEditScreenMixin). */
    private var active = java.lang.ref.WeakReference<AbstractSignEditScreen>(null)

    /** True when [screen] is a sign editor shown as the fancy dialog, so the vanilla sign must not be drawn. */
    @JvmStatic
    fun hides(screen: Any): Boolean = active.get() === screen && FancyAuctionHouseModule.INSTANCE.isEnabled()
    private var cachedQuery: String? = null
    private var cachedMatches: List<geiler.addons.client.itemlist.Entry> = emptyList()

    /** Which prompt the sign's hint lines (2 to 4) are, or null for any other sign. */
    @JvmStatic
    fun kind(hintLines: List<String>): Kind? {
        val text = hintLines.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            "enter query" in text || "auction search" in text -> Kind.SEARCH
            listOf("enter amount", "enter price", "your bid", "per unit", "starting bid", "enter bid").any { it in text } -> Kind.AMOUNT
            else -> null
        }
    }

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractSignEditScreen) return@register
            val sign = screen as AbstractSignEditScreenAccessor
            val kind = kind(sign.`geiler$messages`().drop(1).toList())
            if (kind == null || !FancyAuctionHouseModule.INSTANCE.isEnabled()) return@register
            FancyAuctionHouseModule.INSTANCE.note("SIGN kind=$kind")
            active = java.lang.ref.WeakReference(screen)
            ScreenEvents.afterExtract(screen).register { s, graphics, mouseX, mouseY, _ ->
                draw(s as AbstractSignEditScreen, sign, kind, graphics, mouseX, mouseY)
            }
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                val hit = hits.firstOrNull { it.contains(event.x(), event.y()) }
                if (hit != null) {
                    Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                    hit.action()
                }
                false
            }
        }
    }

    private fun draw(
        screen: AbstractSignEditScreen, sign: AbstractSignEditScreenAccessor, kind: Kind, graphics: GuiGraphicsExtractor,
        mouseX: Int, mouseY: Int,
    ) {
        hits.clear()
        val font = Minecraft.getInstance().font
        val lines = sign.`geiler$messages`()
        val typed = lines[0]
        val hint = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.all { c -> c == '^' } }.joinToString(" ")
        val matches = if (kind == Kind.SEARCH) matches(typed) else emptyList()
        val cols = 8
        val gridRows = if (kind == Kind.SEARCH) 3 else 0
        val w = 260
        val h = 74 + gridRows * 22
        val x = (screen.width - w) / 2
        val y = (screen.height - h) / 2
        graphics.fill(0, 0, screen.width, screen.height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(graphics, x, y, w, h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "§l" + (if (kind == Kind.SEARCH) "Search Auctions" else hint.ifEmpty { "Enter amount" }), x + 8, y + 7, GuiTheme.TEXT_PRIMARY, false)
        // The typed text, with a blinking cursor; keys still go to the sign itself.
        graphics.fill(x + 8, y + 22, x + w - 8, y + 38, GuiTheme.CARD_BG)
        val cursor = if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        graphics.text(font, GuiTheme.fit(font, typed, w - 24) + cursor, x + 12, y + 26, GuiTheme.TEXT_PRIMARY, false)
        if (kind == Kind.SEARCH) {
            for ((i, entry) in matches.take(cols * gridRows).withIndex()) {
                val cx = x + 8 + (i % cols) * 31
                val cy = y + 44 + (i / cols) * 22
                val hover = mouseX in cx until cx + 29 && mouseY in cy until cy + 20
                graphics.fill(cx, cy, cx + 29, cy + 20, if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
                ItemListRepository.compiled?.stacks?.get(entry)?.let { graphics.rarityItem(it, cx + 6, cy + 2) }
                if (hover) graphics.text(font, GuiTheme.fit(font, entry.plainName, w - 16), x + 8, y + h - 34, GuiTheme.TEXT_SECONDARY, false)
                hits.add(Hit(cx, cy, 29, 20) { fill(sign, entry.plainName) })
            }
            if (matches.isEmpty() && typed.isNotBlank()) graphics.text(font, "§7No matching items", x + 8, y + 48, GuiTheme.TEXT_MUTED, false)
        }
        val bx = x + w - 68
        val by = y + h - 22
        val hover = mouseX in bx until bx + 60 && mouseY in by until by + 16
        GuiTheme.roundedRect(graphics, bx, by, 60, 16, 3, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        graphics.centeredText(font, "Done", bx + 30, by + 4, GuiTheme.TEXT_PRIMARY)
        hits.add(Hit(bx, by, 60, 16) { screen.onClose() })
    }

    private fun fill(sign: AbstractSignEditScreenAccessor, text: String) {
        sign.`geiler$messages`()[0] = text.take(MAX_LINE)
        sign.`geiler$signField`()?.setCursorToEnd()
    }

    private fun matches(query: String): List<geiler.addons.client.itemlist.Entry> {
        if (query == cachedQuery) return cachedMatches
        cachedQuery = query
        val q = query.trim()
        cachedMatches = if (q.length < 2) emptyList()
        else ItemListRepository.compiled?.search?.search(q)?.distinctBy { it.plainName }?.take(MAX_MATCHES) ?: emptyList()
        return cachedMatches
    }

    private const val MAX_MATCHES = 24
    /** A sign line holds at most this many characters on Hypixel's prompts. */
    private const val MAX_LINE = 30
}
