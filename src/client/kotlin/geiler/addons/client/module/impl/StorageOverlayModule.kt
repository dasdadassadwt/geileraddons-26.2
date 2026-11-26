package geiler.addons.client.module.impl

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.gui.TextFieldInput
import geiler.addons.client.gui.TextFieldModel
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.storage.StorageOverlayRules
import geiler.addons.client.storage.StorageOverlayStore
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.BitSet
import java.util.UUID

/**
 * Storage Overlay: replaces the single-page `/storage`, Ender Chest and Backpack screens with one scrollable surface that
 * shows every page at once, with search. The idea and the page model follow Skyblocker's storage overlay (LGPL-3.0); this
 * version keeps the vanilla container screen alive underneath (as the terminal and experiment solvers do) and draws its
 * own surface, so no menu type or slot is replaced. Two looks: the Click GUI theme, or a vanilla chest look.
 *
 * Page contents are cached while a page is open; a page that was never opened is not shown, except that the `/storage`
 * home menu creates empty placeholders for the pages it lists.
 */
class StorageOverlayModule private constructor(private val s: Settings) : Module(
    "Storage Overlay",
    "Shows every Ender Chest and Backpack page on one searchable screen.",
    Category.MISCELLANEOUS,
    false,
    s.style, s.pagesPerRow, s.rememberSearch,
) {
    /** The item slots of one page (navigation row excluded) plus the search match for the last filter asked. */
    private class Page(val items: List<ItemStack>) {
        // Built on the first search, not when the page is created: reading names and lore of every stored stack is slow.
        private val names by lazy { items.map { if (it.isEmpty) "" else it.hoverName.string } }
        private val lore by lazy { items.map { stack -> lore(stack) } }
        private var matchedFor: String? = null
        private var matched = BitSet()

        fun matches(filter: String): BitSet {
            if (matchedFor != filter) {
                matched = BitSet()
                for (i in items.indices) {
                    if (!items[i].isEmpty && StorageOverlayRules.matches(filter, names[i], lore[i])) matched.set(i)
                }
                matchedFor = filter
            }
            return matched
        }
    }

    private class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + w && py < y + h
    }

    /** A page card on screen; [items] are the live slots for the open page, the cached ones otherwise. */
    private class Card(val index: Int, val rect: Rect, val cols: Int, val stacks: List<ItemStack>, val open: Boolean, val page: Page?)

    private class Cell(val slot: Slot, val x: Int, val y: Int)

    private class Layout(
        val panel: Rect, val search: Rect, val home: Rect, val hide: Rect, val view: Rect,
        val cards: List<Card>, val contentHeight: Int, val inv: Rect, val invCells: List<Cell>,
    )

    private var current: AbstractContainerScreen<*>? = null
    private var hidden: AbstractContainerScreen<*>? = null
    private var loadedMenu: AbstractContainerMenu? = null
    private var skipNext = false
    private val pages = arrayOfNulls<Page>(StorageOverlayRules.PAGE_COUNT)
    private val searchField = TextFieldModel(MAX_SEARCH)
    private val search: String get() = searchField.text
    private var searchFocused = false
    private var searchDragging = false
    private var scroll = 0.0
    private var closedAtMs = 0L
    private var loadedFor: UUID? = null
    private var loadingFor: UUID? = null

    /** What the worker read for [id]; picked up by [tick] on the client thread. */
    private class LoadResult(val id: UUID, val loaded: StorageOverlayStore.Loaded?, val millis: Long)

    @Volatile
    private var loadResult: LoadResult? = null
    private var dirty = false
    private var saves = 0
    private var lastCommandMs = 0L
    private var screenTicks = 0
    private var slotClicks = 0
    private var switches = 0
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 320)

    override fun settingTooltip(setting: Setting): String? = when (setting) {
        s.style -> "Click GUI uses your theme; Minecraft uses the vanilla chest look."
        s.pagesPerRow -> "How many pages sit side by side. Fewer columns are used when the window is narrow."
        s.rememberSearch -> "Keep the search text when you close and reopen the storage."
        else -> null
    }

    override fun onEnable() {
        diagnostics.enable("style=${s.style.value()} pagesPerRow=${s.pagesPerRow.intValue()} rememberSearch=${s.rememberSearch.value()}")
    }

    override fun onDisable() {
        save("disable")
        loadingFor = null
        loadResult = null
        current = null
        hidden = null
        loadedMenu = null
        diagnostics.disable()
    }

    /** Registers the typing and scrolling hooks every container screen gets; they do nothing unless the overlay owns it. */
    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenKeyboardEvents.allowCharType(screen).register { target, event -> !handleChar(target, event) }
                ScreenMouseEvents.allowMouseScroll(screen).register { target, _, _, _, vertical ->
                    !handleScroll(target, vertical)
                }
            }
        }
    }

    // ----- screen lifecycle ----------------------------------------------------------------------------------------

    fun onScreenOpened(screen: AbstractContainerScreen<*>) {
        if (!isEnabled() || screen === current || screen === hidden) return
        val title = screen.title.string
        val home = StorageOverlayRules.isHomeTitle(title)
        val index = StorageOverlayRules.indexFromTitle(title)
        if ((!home && index < 0) || screen.menu !is ChestMenu) return
        if (skipNext) {
            // Placing or removing a backpack needs the vanilla menu, so the overlay steps aside once.
            skipNext = false
            hidden = screen
            diagnostics.event("SKIP vanilla menu for backpack change title=${title.take(40)}")
            return
        }
        startLoad()
        if (System.currentTimeMillis() - closedAtMs > REOPEN_WINDOW_MS) {
            scroll = 0.0
            if (!s.rememberSearch.value()) searchField.reset("")
        }
        current = screen
        searchFocused = false
        screenTicks = 0
        diagnostics.event("OPEN title=${title.take(40)} home=$home index=$index cached=${pages.count { it != null }}")
    }

    fun onScreenClosed(screen: AbstractContainerScreen<*>) {
        if (screen === hidden) hidden = null
        if (screen !== current) return
        capture(screen)
        closedAtMs = System.currentTimeMillis()
        diagnostics.event("CLOSE cached=${pages.count { it != null }} slotClicks=$slotClicks switches=$switches")
        current = null
        loadedMenu = null
    }

    /** The server filled [menu]; until then its slots are empty and must not overwrite the cache. */
    fun onContentsSet(menu: AbstractContainerMenu) {
        if (!isEnabled() || menu !== current?.menu) return
        loadedMenu = menu
        capture(current ?: return)
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        if (plain.contains("Placing backpack") || plain.contains("Removed backpack")) skipNext = true
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        startLoad()
        applyLoad()
        val screen = current
        if (screen != null && ++screenTicks % CAPTURE_INTERVAL_TICKS == 0) capture(screen)
        // Saved shortly after the storage closes (not on every page switch), so a crash loses at most the last browse.
        if (dirty && screen == null && System.currentTimeMillis() - closedAtMs > SAVE_DELAY_MS) save("idle")
        diagnostics.status {
            "screen=${screen?.title?.string?.take(24)} cached=${pages.count { it != null }} search=${search.length}ch " +
                "scroll=${scroll.toInt()} slotClicks=$slotClicks switches=$switches saves=$saves dirty=$dirty suppressedEvents=$it"
        }
    }

    /** The cache belongs to one account: leaving the server saves it and forgets it, so another account never sees it. */
    fun onDisconnect(registries: HolderLookup.Provider?) {
        if (registries != null) save("disconnect", registries)
        pages.fill(null)
        loadedFor = null
        loadingFor = null
        loadResult = null
        dirty = false
        current = null
        hidden = null
        loadedMenu = null
    }

    /** Client shutdown: saves what is unsaved and waits (bounded) for the file write. */
    fun flush() {
        save("stop")
        StorageOverlayStore.flush()
    }

    private fun registries(): HolderLookup.Provider? = Minecraft.getInstance().player?.connection?.registryAccess()

    /**
     * Starts reading the saved cache of the signed-in player, once, on a worker: the gzip read and the stack decode took about
     * 600 ms on the client thread when the storage was first opened. Until [applyLoad] merges it, [save] writes nothing
     * (`loadedFor` is unset), so an unfinished read can never overwrite the file with a partial cache.
     */
    private fun startLoad() {
        val player = Minecraft.getInstance().player ?: return
        val id = player.uuid
        if (loadedFor == id || loadingFor == id) return
        if (loadedFor != null) pages.fill(null)
        loadedFor = null
        loadingFor = id
        val registries = player.connection.registryAccess()
        LOADER.execute {
            val started = System.nanoTime()
            val loaded = StorageOverlayStore.load(id, registries)
            loadResult = LoadResult(id, loaded, (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** Client thread: merges a finished read; pages already captured this session are kept as fresher. */
    private fun applyLoad() {
        val result = loadResult ?: return
        loadResult = null
        if (result.id != loadingFor) return
        loadingFor = null
        loadedFor = result.id
        val loaded = result.loaded
        if (loaded == null) {
            diagnostics.event("LOAD none (no saved cache) ms=${result.millis} thread=worker")
            return
        }
        var filled = 0
        for ((index, items) in loaded.pages) {
            if (pages[index] == null) {
                pages[index] = Page(items)
                filled++
            }
        }
        diagnostics.event("LOAD pages=$filled dropped=${loaded.dropped} bytes=${loaded.bytes} ms=${result.millis} thread=worker")
    }

    private fun save(reason: String, registries: HolderLookup.Provider? = registries()) {
        val id = loadedFor ?: return
        if (!dirty || registries == null) return
        val snapshot = (0 until StorageOverlayRules.PAGE_COUNT).mapNotNull { index -> pages[index]?.let { index to it.items } }
        val root = StorageOverlayStore.encode(snapshot, registries)
        if (root == null) {
            diagnostics.event("SAVE_FAILED reason=$reason a stack did not encode")
            return
        }
        StorageOverlayStore.saveAsync(id, root)
        dirty = false
        saves++
        diagnostics.event("SAVE reason=$reason pages=${snapshot.size}")
    }

    /** True while the overlay replaces [screen]. */
    fun ownsScreen(screen: AbstractContainerScreen<*>?): Boolean =
        isEnabled() && screen != null && screen === current && screen !== hidden

    // ----- cache -----------------------------------------------------------------------------------------------------

    private fun capture(screen: AbstractContainerScreen<*>) {
        val menu = screen.menu
        if (menu !== loadedMenu) return
        val title = screen.title.string
        val index = StorageOverlayRules.indexFromTitle(title)
        if (index >= 0) {
            val size = menu.slots.count { it.container !is Inventory }
            val items = (StorageOverlayRules.NAV_SLOTS until size).map { menu.slots[it].item.copy() }
            // The periodic capture runs while a page is open; only a real change replaces the page and marks it for saving.
            if (pages[index]?.items?.let { ItemStack.listMatches(it, items) } == true) return
            pages[index] = Page(items)
            dirty = true
            diagnostics.event("CAPTURE page=$index items=${items.count { !it.isEmpty }}/${items.size}")
        } else if (StorageOverlayRules.isHomeTitle(title)) {
            captureHome(menu)
        }
    }

    /** The home menu lists which pages exist: blank placeholders appear for new ones, vanished backpacks are dropped. */
    private fun captureHome(menu: AbstractContainerMenu) {
        var created = 0
        for (slotId in 9..44) {
            val index = StorageOverlayRules.homeIndex(slotId)
            val stack = menu.slots.getOrNull(slotId)?.item ?: continue
            if (index < 0) continue
            val pane = BuiltInRegistries.ITEM.getKey(stack.item).path
            if (index < StorageOverlayRules.ECHEST_PAGES) {
                if (pane == "red_stained_glass_pane" || pages[index] != null) continue
                pages[index] = Page(List(ECHEST_ITEM_SLOTS) { ItemStack.EMPTY })
                created++
            } else {
                if (pane == "brown_stained_glass_pane") {
                    if (pages[index] != null) dirty = true
                    pages[index] = null
                    continue
                }
                if (pages[index] != null) continue
                val size = lore(stack).firstNotNullOfOrNull { line -> StorageOverlayRules.backpackSlots(line).takeIf { it > 0 } } ?: continue
                pages[index] = Page(List(size) { ItemStack.EMPTY })
                created++
            }
        }
        if (created > 0) {
            dirty = true
            diagnostics.event("HOME placeholders=$created")
        }
    }

    private fun liveItems(menu: AbstractContainerMenu): List<ItemStack> {
        val size = menu.slots.count { it.container !is Inventory }
        return (StorageOverlayRules.NAV_SLOTS until size).map { menu.slots[it].item }
    }

    // ----- layout ----------------------------------------------------------------------------------------------------

    private fun layout(screen: AbstractContainerScreen<*>): Layout {
        val perRow = s.pagesPerRow.intValue().coerceIn(1, 6)
        val availableWidth = ((screen.width - 24 - 2 * PAD - SCROLLBAR) - (perRow - 1) * GAP) / perRow
        val cols = StorageOverlayRules.fittingColumns(9, availableWidth)
        val cardW = cols * StorageOverlayRules.SLOT + 2 * StorageOverlayRules.EDGE
        val gridW = perRow * cardW + (perRow - 1) * GAP
        val panelW = gridW + SCROLLBAR + 2 * PAD
        val invH = StorageOverlayRules.HEADER + 4 * StorageOverlayRules.SLOT + 4 + StorageOverlayRules.FOOTER
        val panelH = maxOf(130, screen.height - 12 - SECTION_GAP - invH)
        val top = maxOf(4, (screen.height - panelH - SECTION_GAP - invH) / 2)
        val panelX = (screen.width - panelW) / 2
        val panel = Rect(panelX, top, panelW, panelH)

        val buttonW = 38
        val hide = Rect(panelX + panelW - PAD - buttonW, top + PAD, buttonW, HEADER_ROW)
        val home = Rect(hide.x - GAP - buttonW, top + PAD, buttonW, HEADER_ROW)
        val searchRect = Rect(panelX + PAD, top + PAD, home.x - GAP - (panelX + PAD), HEADER_ROW)
        val viewY = top + PAD + HEADER_ROW + PAD
        val view = Rect(panelX + PAD, viewY, gridW + SCROLLBAR, panel.y + panelH - PAD - viewY)

        val menu = screen.menu
        val openIndex = StorageOverlayRules.indexFromTitle(screen.title.string)
        val filter = search.trim()
        class Entry(val index: Int, val stacks: List<ItemStack>, val page: Page?)
        val entries = ArrayList<Entry>()
        for (index in 0 until StorageOverlayRules.PAGE_COUNT) {
            val page = pages[index]
            val isOpen = index == openIndex
            if (page == null && !isOpen) continue
            if (filter.isNotEmpty() && !isOpen && (page == null || page.matches(filter).isEmpty)) continue
            entries.add(Entry(index, if (isOpen) liveItems(menu) else page!!.items, page))
        }
        val heights = entries.map { StorageOverlayRules.cardHeight(StorageOverlayRules.pageRows(it.stacks.size + StorageOverlayRules.NAV_SLOTS, cols)) }
        val offsets = StorageOverlayRules.rowOffsets(heights, perRow, GAP)
        val rowHeights = heights.chunked(perRow).map { it.max() }
        val content = if (offsets.isEmpty()) 0 else offsets.last() + rowHeights.last()
        scroll = StorageOverlayRules.clampScroll(scroll, content, view.h)
        val cards = entries.mapIndexed { i, entry ->
            val x = view.x + (i % perRow) * (cardW + GAP)
            val y = view.y + offsets[i / perRow] - scroll.toInt()
            Card(entry.index, Rect(x, y, cardW, heights[i]), cols, entry.stacks, entry.index == openIndex, entry.page)
        }

        val invW = 9 * StorageOverlayRules.SLOT + 2 * StorageOverlayRules.EDGE
        val inv = Rect((screen.width - invW) / 2, top + panelH + SECTION_GAP, invW, invH)
        val cells = ArrayList<Cell>(36)
        for (k in 0 until 36) {
            val containerSlot = if (k < 27) 9 + k else k - 27
            val slot = menu.slots.firstOrNull { it.container is Inventory && it.containerSlot == containerSlot } ?: continue
            val row = if (k < 27) k / 9 else 3
            val x = inv.x + StorageOverlayRules.EDGE + 1 + (k % 9) * StorageOverlayRules.SLOT
            val y = inv.y + StorageOverlayRules.HEADER + 1 + row * StorageOverlayRules.SLOT + if (k >= 27) 4 else 0
            cells.add(Cell(slot, x, y))
        }
        return Layout(panel, searchRect, home, hide, view, cards, content, inv, cells)
    }

    private fun cellX(card: Card, j: Int) = card.rect.x + StorageOverlayRules.EDGE + 1 + (j % card.cols) * StorageOverlayRules.SLOT
    private fun cellY(card: Card, j: Int) = card.rect.y + StorageOverlayRules.HEADER + 1 + (j / card.cols) * StorageOverlayRules.SLOT

    // ----- drawing ---------------------------------------------------------------------------------------------------

    private fun minecraftStyle() = s.style.value() == STYLE_MINECRAFT

    /** Draws the overlay in place of the vanilla screen; returns true so the caller cancels vanilla. */
    fun renderCustomScreen(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
        if (!ownsScreen(screen)) return false
        val font = Minecraft.getInstance().font
        val lay = layout(screen)
        val mc = minecraftStyle()
        val filter = search.trim()
        graphics.fill(0, 0, screen.width, screen.height, SCREEN_SHADE)

        panelBackground(graphics, lay.panel, mc)
        searchBox(graphics, font, lay.search, mc)
        button(graphics, font, lay.home, "Home", lay.home.contains(mouseX.toDouble(), mouseY.toDouble()), mc)
        button(graphics, font, lay.hide, "Hide", lay.hide.contains(mouseX.toDouble(), mouseY.toDouble()), mc)

        var tooltip: ItemStack? = null
        graphics.enableScissor(lay.view.x, lay.view.y, lay.view.x + lay.view.w, lay.view.y + lay.view.h)
        // Backgrounds first, items second: a background drawn between two items would push every later item up a layer.
        val rarity = ItemRarityBackgroundsModule.INSTANCE
        for (card in lay.cards) {
            if (card.rect.y + card.rect.h < lay.view.y || card.rect.y > lay.view.y + lay.view.h) continue
            cardBackground(graphics, card.rect, card.open, mc)
            graphics.text(font, StorageOverlayRules.pageName(card.index), card.rect.x + 8, card.rect.y + 6, labelColor(mc), false)
            for (j in card.stacks.indices) {
                val x = cellX(card, j)
                val y = cellY(card, j)
                slotBackground(graphics, x, y, mc)
                rarity.drawBackground(graphics, card.stacks[j], x, y)
            }
        }
        for (card in lay.cards) {
            if (card.rect.y + card.rect.h < lay.view.y || card.rect.y > lay.view.y + lay.view.h) continue
            val matched = if (filter.isEmpty()) null else card.page?.matches(filter)
            for (j in card.stacks.indices) {
                val x = cellX(card, j)
                val y = cellY(card, j)
                val stack = card.stacks[j]
                if (!stack.isEmpty) {
                    graphics.item(stack, x, y)
                    graphics.itemDecorations(font, stack, x, y)
                }
                if (matched != null && !matched.get(j)) graphics.fill(x, y, x + 16, y + 16, NOT_MATCHED)
                if (tooltip == null && !stack.isEmpty && lay.view.contains(mouseX.toDouble(), mouseY.toDouble()) &&
                    mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17
                ) tooltip = stack
            }
        }
        if (lay.cards.isEmpty()) emptyState(graphics, font, lay.view, filter.isNotEmpty(), mc)
        graphics.disableScissor()
        scrollbar(graphics, lay, mc)

        cardBackground(graphics, lay.inv, false, mc)
        graphics.text(font, "Inventory", lay.inv.x + 8, lay.inv.y + 6, labelColor(mc), false)
        for (cell in lay.invCells) {
            slotBackground(graphics, cell.x, cell.y, mc)
            rarity.drawSlotBackground(graphics, cell.slot, cell.x, cell.y)
        }
        for (cell in lay.invCells) {
            val stack = cell.slot.item
            if (stack.isEmpty) continue
            graphics.item(stack, cell.x, cell.y)
            graphics.itemDecorations(font, stack, cell.x, cell.y)
            if (tooltip == null && mouseX >= cell.x - 1 && mouseX < cell.x + 17 && mouseY >= cell.y - 1 && mouseY < cell.y + 17) tooltip = stack
        }

        val carried = screen.menu.carried
        if (!carried.isEmpty) {
            graphics.item(carried, mouseX - 8, mouseY - 8)
            graphics.itemDecorations(font, carried, mouseX - 8, mouseY - 8)
        } else if (tooltip != null) {
            graphics.setTooltipForNextFrame(font, tooltip, mouseX, mouseY)
        }
        return true
    }

    private fun labelColor(mc: Boolean) = InventorySurfaces.labelColor(mc)

    private fun panelBackground(g: GuiGraphicsExtractor, r: Rect, mc: Boolean) = InventorySurfaces.panel(g, r.x, r.y, r.w, r.h, mc)

    private fun cardBackground(g: GuiGraphicsExtractor, r: Rect, open: Boolean, mc: Boolean) = InventorySurfaces.card(g, r.x, r.y, r.w, r.h, open, mc)

    private fun slotBackground(g: GuiGraphicsExtractor, x: Int, y: Int, mc: Boolean) = InventorySurfaces.slot(g, x, y, mc)

    private fun searchBox(g: GuiGraphicsExtractor, font: Font, r: Rect, mc: Boolean) {
        if (mc) {
            g.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (searchFocused) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt())
            g.fill(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + r.h - 1, 0xFF000000.toInt())
        } else {
            GuiTheme.roundedRectBordered(
                g, r.x, r.y, r.w, r.h, GuiTheme.RADIUS_SMALL, GuiTheme.CARD_BG_HOVER, GuiTheme.CARD_BG_HOVER,
                if (searchFocused) GuiTheme.CARD_BORDER_ENABLED else GuiTheme.CARD_BORDER,
            )
        }
        val textY = r.y + (r.h - font.lineHeight) / 2 + 1
        val color = if (mc) 0xFFFFFFFF.toInt() else GuiTheme.TEXT_PRIMARY
        if (search.isEmpty() && !searchFocused) {
            g.text(font, "Search storage", r.x + 5, textY, if (mc) 0xFF909090.toInt() else GuiTheme.TEXT_MUTED, false)
        } else {
            TextFieldInput.draw(g, font, searchField, r.x + 5, textY, r.w - 10, color, 0xAA3366CC.toInt(), searchFocused)
        }
    }

    private fun button(g: GuiGraphicsExtractor, font: Font, r: Rect, label: String, hover: Boolean, mc: Boolean) =
        InventorySurfaces.button(g, font, r.x, r.y, r.w, r.h, label, hover, mc)

    private fun emptyState(g: GuiGraphicsExtractor, font: Font, view: Rect, searching: Boolean, mc: Boolean) {
        val text = if (searching) "No page holds an item matching that search." else "No pages cached yet. Open a page once and it shows here."
        g.text(font, text, view.x + 6, view.y + 8, if (mc) 0xFF404040.toInt() else GuiTheme.TEXT_MUTED, false)
    }

    private fun scrollbar(g: GuiGraphicsExtractor, lay: Layout, mc: Boolean) {
        if (lay.contentHeight <= lay.view.h) return
        val trackX = lay.view.x + lay.view.w - SCROLLBAR + 1
        val thumbH = maxOf(12, lay.view.h * lay.view.h / lay.contentHeight)
        val travel = lay.view.h - thumbH
        val thumbY = lay.view.y + (scroll / (lay.contentHeight - lay.view.h) * travel).toInt()
        if (mc) {
            g.fill(trackX, lay.view.y, trackX + 4, lay.view.y + lay.view.h, 0xFF8B8B8B.toInt())
            g.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF373737.toInt())
        } else {
            GuiTheme.roundedRect(g, trackX, thumbY, 3, thumbH, 1, GuiTheme.SCROLLBAR)
        }
    }

    // ----- input -----------------------------------------------------------------------------------------------------

    fun interface SlotClickDispatcher {
        fun dispatch(slot: Slot, slotId: Int, button: Int, input: ContainerInput)
    }

    /** A click while the overlay is up: search focus, buttons, page switch, or a click on a real slot. */
    fun handleMouseClick(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, dispatcher: SlotClickDispatcher): Boolean {
        if (!ownsScreen(screen)) return false
        val lay = layout(screen)
        val x = event.x()
        val y = event.y()
        searchFocused = lay.search.contains(x, y)
        // Outside both panels vanilla would treat the click as "outside the window" and throw a carried stack.
        if (!lay.panel.contains(x, y) && !lay.inv.contains(x, y)) return true
        if (event.button() != 0 && event.button() != 1) return true
        if (searchFocused) {
            if (event.button() == 0) {
                TextFieldInput.mousePressed(searchField, Minecraft.getInstance().font, x, y, lay.search.x + 5, lay.search.w - 10, event.hasShiftDown())
                searchDragging = true
            }
            return true
        }
        if (event.button() == 0 && lay.home.contains(x, y)) {
            // The home button leads to the vanilla Storage menu (upgrades, skins), so the overlay steps aside once.
            if (sendCommand("storage", "home")) skipNext = true
            return true
        }
        if (event.button() == 0 && lay.hide.contains(x, y)) {
            capture(screen)
            hidden = screen
            current = null
            diagnostics.event("HIDE title=${screen.title.string.take(40)}")
            return true
        }
        val input = if (event.modifiers() and GLFW.GLFW_MOD_SHIFT != 0) ContainerInput.QUICK_MOVE else ContainerInput.PICKUP
        lay.invCells.firstOrNull { x >= it.x - 1 && x < it.x + 17 && y >= it.y - 1 && y < it.y + 17 }?.let {
            click(it.slot, event.button(), input, dispatcher, "inventory")
            return true
        }
        if (!lay.view.contains(x, y)) return true
        val card = lay.cards.firstOrNull { it.rect.contains(x, y) } ?: return true
        if (!card.open) {
            StorageOverlayRules.commandFor(card.index)?.let {
                switches++
                sendCommand(it, "page ${card.index}")
            }
            return true
        }
        val j = card.stacks.indices.firstOrNull { x >= cellX(card, it) - 1 && x < cellX(card, it) + 17 && y >= cellY(card, it) - 1 && y < cellY(card, it) + 17 }
        val slot = j?.let { screen.menu.slots.getOrNull(StorageOverlayRules.NAV_SLOTS + it) }
        if (slot != null) click(slot, event.button(), input, dispatcher, "page ${card.index}")
        return true
    }

    private fun click(slot: Slot, button: Int, input: ContainerInput, dispatcher: SlotClickDispatcher, where: String) {
        slotClicks++
        diagnostics.event("SLOT_CLICK where=$where slot=${slot.index} button=$button input=$input")
        dispatcher.dispatch(slot, slot.index, button, input)
    }

    /** Opens another page. Hypixel throttles menu commands, so a second click inside the guard is dropped. */
    private fun sendCommand(command: String, why: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCommandMs < COMMAND_GUARD_MS) {
            diagnostics.event("COMMAND_DROPPED command=$command gapMs=${now - lastCommandMs}")
            return false
        }
        val connection = Minecraft.getInstance().player?.connection ?: return false
        lastCommandMs = now
        diagnostics.event("COMMAND command=$command why=$why")
        connection.sendCommand(command)
        return true
    }

    fun handleKey(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        if (!ownsScreen(screen)) return false
        if (!searchFocused) {
            // The vanilla hovered slot sits under the hidden slot grid; drop, swap and hotbar keys would hit it.
            val options = Minecraft.getInstance().options
            return options.keyDrop.matches(event) || options.keySwapOffhand.matches(event) || options.keyHotbarSlots.any { it.matches(event) }
        }
        when (event.key()) {
            GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> searchFocused = false
            else -> if (TextFieldInput.handleKey(searchField, event, "storage-search") == TextFieldInput.Outcome.CHANGED) {
                scroll = 0.0
                diagnostics.event("SEARCH length=${search.length}")
            }
        }
        // Everything else is swallowed while typing so the inventory key does not close the screen.
        return true
    }

    /** Extends the search selection while the button stays down after a press on the search box. */
    fun handleMouseDrag(screen: AbstractContainerScreen<*>, event: MouseButtonEvent) {
        if (!searchDragging || !ownsScreen(screen) || event.button() != 0) return
        val r = layout(screen).search
        TextFieldInput.mouseDragged(searchField, Minecraft.getInstance().font, event.x(), r.x + 5, r.w - 10)
    }

    fun handleMouseRelease(event: MouseButtonEvent) {
        if (event.button() == 0) searchDragging = false
    }

    private fun handleChar(screen: net.minecraft.client.gui.screens.Screen, event: CharacterEvent): Boolean {
        if (screen !is AbstractContainerScreen<*> || !ownsScreen(screen) || !searchFocused) return false
        if (TextFieldInput.handleChar(searchField, event, "storage-search") == TextFieldInput.Outcome.CHANGED) {
            scroll = 0.0
            diagnostics.event("SEARCH length=${search.length}")
        }
        return true
    }

    private fun handleScroll(screen: net.minecraft.client.gui.screens.Screen, vertical: Double): Boolean {
        if (screen !is AbstractContainerScreen<*> || !ownsScreen(screen)) return false
        val lay = layout(screen)
        scroll = StorageOverlayRules.clampScroll(scroll - vertical * SCROLL_STEP, lay.contentHeight, lay.view.h)
        return true
    }

    class Settings {
        val style = InventorySurfaces.styleSetting()
        val pagesPerRow = NumberSetting("Pages Per Row", 1f, 6f, 3f, true)
        val rememberSearch = BooleanSetting("Remember Search", false)
    }

    companion object {
        const val STYLE_CLICK_GUI = InventorySurfaces.STYLE_CLICK_GUI
        const val STYLE_MINECRAFT = InventorySurfaces.STYLE_MINECRAFT
        private const val PAD = 8
        private const val GAP = 4
        private const val SECTION_GAP = 6
        private const val SCROLLBAR = 6
        private const val HEADER_ROW = 16
        private const val SCROLL_STEP = 15.0
        private const val MAX_SEARCH = 32
        private const val ECHEST_ITEM_SLOTS = 45
        private const val CAPTURE_INTERVAL_TICKS = 10
        private const val COMMAND_GUARD_MS = 350L
        private const val REOPEN_WINDOW_MS = 1_500L
        private const val SAVE_DELAY_MS = 2_000L
        private val LOADER = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "geileraddons-storage-load").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 }
        }
        private val SCREEN_SHADE = 0x70000000
        private val NOT_MATCHED = 0x99000000.toInt()

        private fun lore(stack: ItemStack): List<String> =
            if (stack.isEmpty) emptyList() else stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

        @JvmField
        val INSTANCE = StorageOverlayModule(Settings())
    }
}
