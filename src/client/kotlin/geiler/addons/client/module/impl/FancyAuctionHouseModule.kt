package geiler.addons.client.module.impl

import geiler.addons.client.bazaar.AuctionMenus
import geiler.addons.client.bazaar.BazaarMenus
import geiler.addons.client.gui.ContainerSkin
import geiler.addons.client.gui.ContainerSkins
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.pricing.SkyblockItemIds
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Fancy Auction House and Bazaar: themed replacements for the Auctions Browser, the auction view and the Bazaar's
 * category and product pages. The AH layout follows Skyblocker's Fancy Auction House; the Bazaar layout is this mod's
 * own in the same style. Every control clicks the real menu slot; hovering shows the real item tooltip.
 */
class FancyAuctionHouseModule private constructor(
    private val bazaar: BooleanSetting,
    private val cheapBin: BooleanSetting,
) : Module(
    "Fancy Auction House",
    "Restyles the Auctions Browser, auction pages and (optionally) the Bazaar. Every button clicks the real menu.",
    Category.VISUAL,
    false,
    bazaar,
    cheapBin,
), ContainerSkin {
    private enum class Kind { BROWSER, VIEW, BAZAAR, GENERIC }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
    }

    private class Target(val rect: Rect, val slot: Int, val what: String)

    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 300)
    private val targets = ArrayList<Target>()
    private var currentScreen: AbstractContainerScreen<*>? = null
    private var bypassContainer = -1
    private var lastKind: Kind? = null
    private var lastTitle = ""
    private var clicks = 0
    private var lastClickMs = 0L
    /** When an AH or Bazaar menu was last drawn: follow-up pages (amount, confirm) are skinned only right after one. */
    private var contextMs = 0L
    /** Page progress thumb, eased toward the page shown. */
    private var thumb = 0f
    private var loggedArrowTitle = ""

    override fun onEnable() {
        diagnostics.enable("bazaar=${bazaar.value()} cheapBin=${cheapBin.value()}")
    }

    override fun onDisable() {
        diagnostics.event("COUNTS clicks=$clicks")
        diagnostics.disable()
    }

    /** A diagnostics line from the sign overlay. */
    fun note(text: String) {
        if (isEnabled()) diagnostics.event(text)
    }

    private fun kind(screen: AbstractContainerScreen<*>): Kind? {
        if (!isEnabled() || screen.menu !is ChestMenu || screen.menu.containerId == bypassContainer) return null
        val title = screen.title.string
        val kind = when {
            AuctionMenus.isBrowser(title) && (screen.menu as ChestMenu).rowCount == 6 -> Kind.BROWSER
            AuctionMenus.isView(title) -> Kind.VIEW
            bazaar.value() && BazaarMenus.isBazaar(title, names(screen)) -> Kind.BAZAAR
            BazaarMenus.isGeneric(title, backLore(screen), System.currentTimeMillis() - contextMs < CONTEXT_MS)
                && (bazaar.value() || !backLore(screen).orEmpty().lowercase(Locale.ROOT).startsWith("to bazaar")) -> Kind.GENERIC
            else -> null
        }
        if (kind != lastKind || title != lastTitle) {
            lastKind = kind
            lastTitle = title
            if (kind != null) diagnostics.event("MENU kind=$kind title=${title.take(40)} rows=${(screen.menu as ChestMenu).rowCount}")
        }
        return kind
    }

    override fun owns(screen: AbstractContainerScreen<*>): Boolean = kind(screen) != null

    private fun backLore(screen: AbstractContainerScreen<*>): String? = screen.menu.slots.take(rows(screen) * 9)
        .firstOrNull { SkyblockItems.realName(it.item) == "Go Back" }?.let { SkyblockItems.loreLines(it.item).firstOrNull()?.trim() }

    private fun names(screen: AbstractContainerScreen<*>): List<String> =
        screen.menu.slots.take(rows(screen) * 9).map { SkyblockItems.realName(it.item) }

    private fun rows(screen: AbstractContainerScreen<*>) = (screen.menu as ChestMenu).rowCount

    private fun stack(screen: AbstractContainerScreen<*>, slot: Int): ItemStack = screen.menu.slots.getOrNull(slot)?.item ?: ItemStack.EMPTY

    private fun id(stack: ItemStack) = if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString()

    // ---- drawing --------------------------------------------------------------------------------------------

    override fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        targets.clear()
        currentScreen = screen
        diagnostics.advance()
        graphics.fill(0, 0, screen.width, screen.height, GuiTheme.DIALOG_SHADE)
        val kind = kind(screen)
        if (kind != null) contextMs = System.currentTimeMillis()
        when (kind) {
            Kind.BROWSER -> browser(screen, graphics, mouseX, mouseY)
            Kind.VIEW -> view(screen, graphics, mouseX, mouseY)
            Kind.BAZAAR -> bazaarPage(screen, graphics, mouseX, mouseY)
            Kind.GENERIC -> generic(screen, graphics, mouseX, mouseY)
            null -> Unit
        }
    }

    private fun panel(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, w: Int, h: Int): Rect {
        val r = Rect((screen.width - w) / 2, max(6, (screen.height - h) / 2), w, min(h, screen.height - 12))
        GuiTheme.roundedRectBordered(graphics, r.x, r.y, r.w, r.h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        return r
    }

    private fun button(graphics: GuiGraphicsExtractor, r: Rect, label: String, slot: Int, what: String, mouseX: Int, mouseY: Int, active: Boolean = slot >= 0) {
        val font = Minecraft.getInstance().font
        val hover = active && r.contains(mouseX.toDouble(), mouseY.toDouble())
        GuiTheme.roundedRect(graphics, r.x, r.y, r.w, r.h, 3, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        graphics.centeredText(font, GuiTheme.fit(font, label, r.w - 4), r.x + r.w / 2, r.y + (r.h - 8) / 2, if (active) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_MUTED)
        if (active) {
            targets.add(Target(r, slot, what))
            val stack = if (slot >= 0) currentScreen?.let { stack(it, slot) } else null
            if (hover && stack != null && !stack.isEmpty) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
        }
    }

    private fun header(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, p: Rect, title: String, mouseX: Int, mouseY: Int) {
        graphics.text(Minecraft.getInstance().font, "§l" + title, p.x + 8, p.y + 7, GuiTheme.TEXT_PRIMARY, false)
        button(graphics, Rect(p.x + p.w - 52, p.y + 4, 44, 14), "Vanilla", VANILLA, "vanilla", mouseX, mouseY, true)
    }

    private fun itemCell(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        if (stack.isEmpty) return
        graphics.rarityItem(stack, x, y)
        graphics.itemDecorations(Minecraft.getInstance().font, stack, x, y)
        if (mouseX in x until x + 16 && mouseY in y until y + 16) graphics.setTooltipForNextFrame(Minecraft.getInstance().font, stack, mouseX, mouseY)
    }

    private fun browser(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val p = panel(screen, graphics, 420, 250)
        val title = screen.title.string.let { if (it.startsWith("Auctions: ")) "Search: " + it.removePrefix("Auctions: ").trim('"') else "Auction House" }
        header(screen, graphics, p, title, mouseX, mouseY)
        button(graphics, Rect(p.x + p.w - 100, p.y + 4, 44, 14), "Back", AuctionMenus.BACK, "back", mouseX, mouseY)
        button(graphics, Rect(p.x + p.w - 156, p.y + 4, 52, 14), "Search", AuctionMenus.SEARCH, "search", mouseX, mouseY)
        if (id(stack(screen, AuctionMenus.RESET)) == "minecraft:anvil") button(graphics, Rect(p.x + p.w - 204, p.y + 4, 44, 14), "Reset", AuctionMenus.RESET, "reset", mouseX, mouseY)
        // Category rail (column 0).
        for (row in 0 until 6) {
            val slot = row * 9
            val icon = stack(screen, slot)
            if (icon.isEmpty || BazaarMenus.isFiller(id(icon), SkyblockItems.realName(icon))) continue
            val r = Rect(p.x + 8, p.y + 24 + row * 24, 22, 22)
            val selected = AuctionMenus.selectedCategory(SkyblockItems.loreLines(icon))
            graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (selected) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
            itemCell(graphics, icon, r.x + 3, r.y + 3, mouseX, mouseY)
            targets.add(Target(r, slot, "category"))
        }
        // Listings, 6 by 4.
        val prices = PriceDisplayModule.INSTANCE.prices().threeDayAverage
        val gx = p.x + 38
        val cw = (p.w - 46) / 6
        val ch = 46
        for ((i, slot) in AuctionMenus.listingSlots(6).withIndex()) {
            val stack = stack(screen, slot)
            val r = Rect(gx + (i % 6) * cw, p.y + 24 + (i / 6) * ch, cw - 3, ch - 3)
            graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, GuiTheme.CARD_BG)
            if (stack.isEmpty || BazaarMenus.isFiller(id(stack), SkyblockItems.realName(stack))) continue
            val listing = AuctionMenus.listing(SkyblockItems.loreLines(stack))
            val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
            if (hover) graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, GuiTheme.CARD_BG_HOVER)
            if (cheapBin.value() && listing.bin && listing.price != null && prices != null) {
                val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
                val avg = tag?.let { prices[SkyblockItemIds.neuName(it)] }
                if (avg != null && listing.price < avg) graphics.outline(r.x, r.y, r.w, r.h, CHEAP)
            }
            graphics.rarityItem(stack, r.x + (r.w - 16) / 2, r.y + 3)
            graphics.centeredText(font, (if (listing.bin) "§6" else "§e") + (listing.price?.let { coins(it) } ?: "?"), r.x + r.w / 2, r.y + 22, GuiTheme.TEXT_PRIMARY)
            listing.ends?.let { graphics.centeredText(font, "§8" + GuiTheme.fit(font, AuctionMenus.shortTime(it), r.w - 2), r.x + r.w / 2, r.y + 32, GuiTheme.TEXT_MUTED) }
            if (hover) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
            targets.add(Target(r, slot, "listing"))
        }
        pageProgress(screen, graphics, Rect(p.x + p.w - 6, p.y + 24, 3, 4 * ch - 3))
        // Footer: pages and filters.
        val fy = p.y + p.h - 20
        val prev = id(stack(screen, AuctionMenus.PREVIOUS)) == "minecraft:arrow"
        val next = id(stack(screen, AuctionMenus.NEXT)) == "minecraft:arrow"
        button(graphics, Rect(p.x + 8, fy, 20, 14), "◀", if (prev) AuctionMenus.PREVIOUS else -1, "previous", mouseX, mouseY)
        button(graphics, Rect(p.x + p.w - 28, fy, 20, 14), "▶", if (next) AuctionMenus.NEXT else -1, "next", mouseX, mouseY)
        val page = AuctionMenus.page(SkyblockItems.loreLines(stack(screen, AuctionMenus.NEXT))).let { if (it > 0) it - 1 else AuctionMenus.page(SkyblockItems.loreLines(stack(screen, AuctionMenus.PREVIOUS))) + 1 }
        graphics.text(font, "§7Page ${max(1, page)}", p.x + 34, fy + 3, GuiTheme.TEXT_SECONDARY, false)
        val fw = (p.w - 140) / 3
        for ((i, pair) in listOf(AuctionMenus.SORT to "Sort", AuctionMenus.RARITY to "Rarity", AuctionMenus.TYPE to "Type").withIndex()) {
            val value = AuctionMenus.option(SkyblockItems.loreLines(stack(screen, pair.first)))
            // Only the chosen option: "Sort: Lowest Price" did not fit the button.
            button(graphics, Rect(p.x + 84 + i * (fw + 4), fy, fw, 14), value.ifEmpty { pair.second }, if (stack(screen, pair.first).isEmpty) -1 else pair.first, pair.second.lowercase(Locale.ROOT), mouseX, mouseY)
        }
    }

    /**
     * Scroll progress beside the listings: the thumb sits at the page shown out of all pages when the arrow lore names
     * the total, else at the top, middle or end from which arrows exist. It eases between pages.
     */
    private fun pageProgress(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, track: Rect) {
        val nextLore = SkyblockItems.loreLines(stack(screen, AuctionMenus.NEXT))
        val prevLore = SkyblockItems.loreLines(stack(screen, AuctionMenus.PREVIOUS))
        val hasNext = id(stack(screen, AuctionMenus.NEXT)) == "minecraft:arrow"
        val hasPrev = id(stack(screen, AuctionMenus.PREVIOUS)) == "minecraft:arrow"
        if (!hasNext && !hasPrev) return
        if (screen.title.string != loggedArrowTitle) {
            loggedArrowTitle = screen.title.string
            diagnostics.event("ARROW_LORE next=${nextLore.joinToString("|").take(80)} prev=${prevLore.joinToString("|").take(80)}")
        }
        val total = AuctionMenus.pageOf(nextLore)?.second ?: AuctionMenus.pageOf(prevLore)?.second
        val page = AuctionMenus.page(nextLore).let { if (it > 0) it - 1 else AuctionMenus.page(prevLore) + 1 }
        val target = when {
            total != null && total > 1 -> ((page - 1).coerceIn(0, total - 1)).toFloat() / (total - 1)
            !hasPrev -> 0f
            !hasNext -> 1f
            else -> 0.5f
        }
        thumb += (target - thumb) * 0.25f
        val thumbH = if (total != null && total > 0) max(6, track.h / total) else max(6, track.h / 4)
        val y = track.y + ((track.h - thumbH) * thumb).toInt()
        graphics.fill(track.x, track.y, track.x + track.w, track.y + track.h, GuiTheme.CARD_BG)
        graphics.fill(track.x, y, track.x + track.w, y + thumbH, GuiTheme.SLIDER_FILL)
    }

    /**
     * Any other AH or Bazaar menu (main menu, your auctions and bids, orders, amount and confirm pages): its items as
     * themed cards on the menu's own grid, names under the items, every card clicking its real slot.
     */
    private fun generic(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val cards = geiler.addons.client.gui.GenericMenuGrid.draw(screen, graphics, screen.title.string, mouseX, mouseY)
        val (px, py, pw) = geiler.addons.client.gui.GenericMenuGrid.panel
        button(graphics, Rect(px + pw - 52, py + 4, 44, 14), "Vanilla", VANILLA, "vanilla", mouseX, mouseY, true)
        for (c in cards) targets.add(Target(Rect(c.x, c.y, c.w, c.h), c.slot, "generic"))
    }

    private fun view(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val bin = AuctionMenus.isBin(screen.title.string)
        val p = panel(screen, graphics, 240, 150)
        header(screen, graphics, p, if (bin) "BIN Auction" else "Auction", mouseX, mouseY)
        button(graphics, Rect(p.x + p.w - 100, p.y + 4, 44, 14), "Back", AuctionMenus.BACK, "back", mouseX, mouseY)
        val item = stack(screen, AuctionMenus.VIEW_ITEM)
        graphics.pose().pushMatrix()
        graphics.pose().translate((p.x + 16).toFloat(), (p.y + 30).toFloat())
        graphics.pose().scale(2.5f, 2.5f)
        graphics.rarityItem(item, 0, 0)
        graphics.pose().popMatrix()
        if (mouseX in p.x + 16 until p.x + 56 && mouseY in p.y + 30 until p.y + 70 && !item.isEmpty) graphics.setTooltipForNextFrame(font, item, mouseX, mouseY)
        graphics.text(font, GuiTheme.fit(font, item.hoverName.string, p.w - 80), p.x + 66, p.y + 30, GuiTheme.TEXT_PRIMARY, false)
        // The action slot: the first non-filler item that is a buy, bid, cancel or collect button.
        var action: Pair<Int, AuctionMenus.ViewAction>? = null
        for (slot in 0 until rows(screen) * 9) {
            if (slot == AuctionMenus.VIEW_ITEM) continue
            val s = stack(screen, slot)
            AuctionMenus.viewAction(id(s), SkyblockItems.realName(s))?.let { if (action == null || it == AuctionMenus.ViewAction.CANCEL) action = slot to it }
        }
        val act = action
        var y = p.y + 44
        if (act != null) {
            val lore = SkyblockItems.loreLines(stack(screen, act.first))
            for (line in lore.map { it.trim() }.filter { l -> PRICE_KEYS.any { l.lowercase(Locale.ROOT).startsWith(it) } }.take(3)) {
                graphics.text(font, "§7" + GuiTheme.fit(font, line, p.w - 80), p.x + 66, y, GuiTheme.TEXT_PRIMARY, false)
                y += 11
            }
            val usable = act.second != AuctionMenus.ViewAction.CANT_AFFORD && act.second != AuctionMenus.ViewAction.GRACE && act.second != AuctionMenus.ViewAction.TOP_BID
            button(graphics, Rect(p.x + 66, p.y + p.h - 26, 100, 18), act.second.label, if (usable) act.first else -1, act.second.name.lowercase(Locale.ROOT), mouseX, mouseY)
        } else {
            graphics.text(font, "§7Waiting for the auction...", p.x + 66, y, GuiTheme.TEXT_PRIMARY, false)
        }
        if (!bin && act?.second != AuctionMenus.ViewAction.COLLECT && !stack(screen, AuctionMenus.VIEW_EDIT_BID).isEmpty) {
            button(graphics, Rect(p.x + 170, p.y + p.h - 26, 62, 18), "Edit Bid", AuctionMenus.VIEW_EDIT_BID, "edit-bid", mouseX, mouseY)
        }
    }

    private fun bazaarPage(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val rows = rows(screen)
        val p = panel(screen, graphics, 420, 250)
        header(screen, graphics, p, screen.title.string.replace("➜", "›"), mouseX, mouseY)
        val rail = ArrayList<Int>()
        val content = ArrayList<Int>()
        val bottom = ArrayList<Int>()
        for (slot in 0 until rows * 9) {
            val s = stack(screen, slot)
            if (s.isEmpty || BazaarMenus.isFiller(id(s), SkyblockItems.realName(s))) continue
            when {
                slot >= (rows - 1) * 9 -> bottom.add(slot)
                slot % 9 == 0 && screen.title.string.startsWith("Bazaar ➜") -> rail.add(slot)
                else -> content.add(slot)
            }
        }
        for ((i, slot) in rail.withIndex()) {
            val icon = stack(screen, slot)
            val r = Rect(p.x + 8, p.y + 24 + i * 24, 22, 22)
            graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (icon.hasFoil()) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
            itemCell(graphics, icon, r.x + 3, r.y + 3, mouseX, mouseY)
            targets.add(Target(r, slot, "category"))
        }
        val gx = if (rail.isEmpty()) p.x + 8 else p.x + 38
        val cols = 3
        val cw = (p.x + p.w - 8 - gx) / cols
        val ch = 36
        val maxRows = (p.h - 50) / ch
        for ((i, slot) in content.take(cols * maxRows).withIndex()) {
            val s = stack(screen, slot)
            val r = Rect(gx + (i % cols) * cw, p.y + 24 + (i / cols) * ch, cw - 3, ch - 3)
            val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
            graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.rarityItem(s, r.x + 3, r.y + (r.h - 16) / 2)
            graphics.text(font, GuiTheme.fit(font, s.hoverName.string, r.w - 26), r.x + 22, r.y + 3, GuiTheme.TEXT_PRIMARY, false)
            BazaarMenus.priceLines(SkyblockItems.loreLines(s)).take(2).forEachIndexed { line, text ->
                graphics.text(font, "§7" + GuiTheme.fit(font, text, r.w - 26), r.x + 22, r.y + 13 + line * 9, GuiTheme.TEXT_SECONDARY, false)
            }
            if (hover) graphics.setTooltipForNextFrame(font, s, mouseX, mouseY)
            targets.add(Target(r, slot, "item"))
        }
        if (content.size > cols * maxRows) diagnostics.event("OVERFLOW items=${content.size} shown=${cols * maxRows}")
        val bw = if (bottom.isEmpty()) 0 else min(90, (p.w - 16) / bottom.size - 3)
        for ((i, slot) in bottom.withIndex()) {
            button(graphics, Rect(p.x + 8 + i * (bw + 3), p.y + p.h - 20, bw, 14), stack(screen, slot).hoverName.string, slot, "bottom", mouseX, mouseY)
        }
    }

    private fun coins(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2fB", value / 1e9)
        value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1e6)
        value >= 1_000 -> String.format(Locale.ROOT, "%.1fk", value / 1e3)
        else -> value.toString()
    }

    // ---- input ----------------------------------------------------------------------------------------------

    override fun click(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        if (event.button() != 0 && event.button() != 1) return true
        val target = targets.firstOrNull { it.rect.contains(event.x(), event.y()) } ?: return true
        if (target.slot == VANILLA) {
            bypassContainer = screen.menu.containerId
            diagnostics.event("VANILLA kind=$lastKind")
            return true
        }
        val now = System.currentTimeMillis()
        if (now - lastClickMs < CLICK_GUARD_MS) return true
        lastClickMs = now
        clicks++
        diagnostics.event("CLICK kind=$lastKind what=${target.what} slot=${target.slot} button=${event.button()}")
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        ContainerSkins.clickSlot(screen, target.slot, event.button())
        return true
    }

    override fun scroll(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, amount: Double): Boolean {
        if (kind(screen) == Kind.BROWSER) {
            val slot = if (amount > 0) AuctionMenus.PREVIOUS else AuctionMenus.NEXT
            if (id(stack(screen, slot)) == "minecraft:arrow" && System.currentTimeMillis() - lastClickMs >= CLICK_GUARD_MS) {
                lastClickMs = System.currentTimeMillis()
                diagnostics.event("PAGE scroll slot=$slot")
                ContainerSkins.clickSlot(screen, slot)
            }
        }
        return true
    }

    override fun key(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        if (kind(screen) == Kind.BROWSER && (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)) {
            scroll(screen, 0.0, 0.0, if (event.key() == GLFW.GLFW_KEY_UP) 1.0 else -1.0)
            return true
        }
        // Hidden vanilla slots must not take hotbar, drop or swap keys; the inventory key still closes the menu.
        return !Minecraft.getInstance().options.keyInventory.matches(event)
    }

    companion object {
        private const val VANILLA = -2
        private const val CLICK_GUARD_MS = 250L
        private const val CONTEXT_MS = 4_000L
        private const val CHEAP = -16_711_936 // 0xFF00FF00
        private val PRICE_KEYS = listOf("price:", "new bid:", "minimum bid:", "you pay:", "top bid:", "starting bid:", "buy it now:", "can buy in:")

        @JvmField
        val INSTANCE = FancyAuctionHouseModule(BooleanSetting("Bazaar", true), BooleanSetting("Highlight Cheap BIN", true))
    }
}
