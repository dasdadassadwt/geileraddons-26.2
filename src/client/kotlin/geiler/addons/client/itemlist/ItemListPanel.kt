package geiler.addons.client.itemlist

import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.gui.TextFieldInput
import geiler.addons.client.gui.TextFieldModel
import geiler.addons.client.inventory.SkyblockRarity
import geiler.addons.client.module.impl.PriceDisplayModule
import geiler.addons.client.pricing.NeuRecipes
import geiler.addons.client.pricing.PriceSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.net.URI

/**
 * The Item List: an item grid right of every inventory screen, favorites top left, and the recipe/uses window. All layout
 * is recomputed each frame; every clickable thing registers a [Hit] while drawing, so input always matches what is on screen.
 */
class ItemListPanel(private val host: Host) {
    interface Host {
        fun minecraftStyle(): Boolean
        fun buyInstantly(): Boolean

        /** Most columns the item grid may have. */
        fun columns(): Int

        /** Most rows the item grid may have; 0 fills the screen height. */
        fun rows(): Int
        fun event(text: String)
        fun saveFavorites(favorites: List<String>, open: Boolean, sort: String)

        /** Whether to color the slot behind each item by rarity. */
        fun rarityBackgrounds(): Boolean

        /** One rarity square (or disc, per the Item Rarity Backgrounds style) for an item drawn at [x], [y]. */
        fun drawRarity(g: GuiGraphicsExtractor, rarity: SkyblockRarity, x: Int, y: Int)
    }

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val id: String?, val tip: Component?, val click: (Int) -> Unit)

    private class Popup(val id: String, val recipes: Boolean, var tab: String, var page: Int)

    val favorites = ArrayList<String>()
    var open = true

    /** One of `ItemListData.SORT_*`; saved with the favorites. */
    var sort = ItemListData.SORT_BEST

    private val queryField = TextFieldModel(MAX_QUERY)
    private val query: String get() = queryField.text
    private var focused = false
    private var searchTextX = 0
    private var searchTextW = 0
    private var searchPress = false
    private var searchDragging = false
    private var page = 0
    private var category = ItemListData.CAT_ALL
    private var results: List<Entry> = emptyList()
    private var resultsKey = ""
    private var resultsBase = ""
    private var calc: Double? = null
    private var ghost = ""
    private var popup: Popup? = null
    private val history = ArrayDeque<Popup>()
    private val hits = ArrayList<Hit>()
    private var hoverId: String? = null
    private var listX = 0
    private var listW = 0
    private var listTop = 0
    private var listBottom = 0
    private var toast = ""
    private var toastUntil = 0L
    private var names: Map<String, String> = emptyMap()
    private var namesFor: Any? = null
    private var calculator: NeuRecipes.Calculator? = null
    private var calculatorFor: Any? = null
    private var calculatorMode = false

    /** Double-click on the search bar: the query also searches the slots of the open screen (yellow bar). */
    private var slotSearch = false
    private var lastSearchClick = 0L
    private var slotMatches = 0
    private var slotTotal = 0

    var searches = 0L
        private set
    var searchNanosTotal = 0L
        private set
    var searchNanosMax = 0L
        private set
    var recipeOpens = 0L
        private set
    var usesOpens = 0L
        private set
    var popupErrors = 0L
        private set
    var draws = 0L
        private set
    var tooltips = 0L
        private set
    private var drawNanosTotal = 0L
    private var drawNanosMax = 0L
    val stackFailures: Int get() = ItemListRepository.compiled?.stacks?.failures ?: 0
    val stackFailureSample: List<String> get() = ItemListRepository.compiled?.stacks?.failedSample ?: emptyList()
    val stacksBuilt: Int get() = ItemListRepository.compiled?.stacks?.size ?: 0
    val searchFocused: Boolean get() = focused

    private val mc: Boolean get() = host.minecraftStyle()

    // ----- state changes ---------------------------------------------------------------------------------------------

    private fun say(text: String) {
        toast = text
        toastUntil = System.currentTimeMillis() + 1800
    }

    private fun refresh(compiled: ItemListRepository.Compiled) {
        val snapshot = PriceDisplayModule.INSTANCE.prices()
        val priceSort = sort == ItemListData.SORT_PRICE_ASC || sort == ItemListData.SORT_PRICE_DESC
        // Prices load late and refresh now and then: a price sort re-sorts then, but keeps the page.
        val base = "$category\u0000$query\u0000$sort\u0000${System.identityHashCode(compiled)}"
        val key = if (priceSort) "$base\u0000${System.identityHashCode(snapshot)}${host.buyInstantly()}" else base
        if (key == resultsKey) return
        resultsKey = key
        val started = System.nanoTime()
        calc = SearchCalculator.evaluate(query) { fn, arg -> priceOf(compiled, snapshot, fn, arg) }
        val found = compiled.search.search(if (calc != null) "" else query, category)
        results = if (sort == ItemListData.SORT_BEST) found else ItemSearch.sort(found, sort) { id -> ItemPrices.unit(id, snapshot, host.buyInstantly()) }
        ghost = if (calc != null) "" else compiled.search.suggest(query)
        val elapsed = System.nanoTime() - started
        searches++
        searchNanosTotal += elapsed
        if (elapsed > searchNanosMax) searchNanosMax = elapsed
        if (base != resultsBase) page = 0
        resultsBase = base
    }

    private fun sortLabel(mode: String): String = when (mode) {
        ItemListData.SORT_NAME_ASC -> "A-Z"
        ItemListData.SORT_NAME_DESC -> "Z-A"
        ItemListData.SORT_PRICE_ASC -> "Price ^"
        ItemListData.SORT_PRICE_DESC -> "Price v"
        else -> "Best"
    }

    private fun sortTip(mode: String): String = when (mode) {
        ItemListData.SORT_NAME_ASC -> "Name, A to Z"
        ItemListData.SORT_NAME_DESC -> "Name, Z to A"
        ItemListData.SORT_PRICE_ASC -> "Price, low to high (unpriced last)"
        ItemListData.SORT_PRICE_DESC -> "Price, high to low (unpriced last)"
        else -> "Best match (name order with no search)"
    }

    /** Left click: the next sort; right click: the previous one. */
    private fun changeSort(button: Int) {
        sort = ItemListData.nextSort(sort, if (button == 1) -1 else 1)
        host.event("SORT $sort")
        host.saveFavorites(favorites, open, sort)
    }

    private fun priceOf(compiled: ItemListRepository.Compiled, snapshot: PriceSnapshot, fn: String, arg: String): Double? {
        if (namesFor !== compiled) {
            names = compiled.entries.associate { it.plainName.lowercase() to it.id }
            namesFor = compiled
        }
        val id = if (compiled.byId.containsKey(arg.uppercase().replace(' ', '_'))) arg.uppercase().replace(' ', '_') else names[arg.lowercase()] ?: return null
        return ItemPrices.source(fn, id, snapshot, host.buyInstantly())
    }

    private fun setQuery(text: String) {
        queryField.replaceAll(text)
    }

    private fun setCategory(value: String) {
        category = value
    }

    fun toggleOpen() {
        open = !open
        focused = false
        host.saveFavorites(favorites, open, sort)
    }

    fun focusSearch() {
        open = true
        focused = true
    }

    private fun toggleFavorite(id: String, name: String) {
        if (!favorites.remove(id)) favorites.add(id)
        say((if (id in favorites) "Added to favorites: " else "Removed from favorites: ") + name)
        host.saveFavorites(favorites, open, sort)
    }

    private fun openPopup(compiled: ItemListRepository.Compiled, id: String, recipes: Boolean) {
        val list = if (recipes) compiled.index.recipesFor(id) else compiled.index.usesOf(id)
        val name = compiled.byId[id]?.plainName ?: id
        if (list.isEmpty()) {
            say((if (recipes) "No recipes for " else "No uses for ") + name)
            return
        }
        popup?.let {
            history.addLast(Popup(it.id, it.recipes, it.tab, it.page))
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        val tab = ItemListData.TYPE_ORDER.first { type -> list.any { it.type == type } }
        popup = Popup(id, recipes, tab, 0)
        if (recipes) recipeOpens++ else usesOpens++
        host.event("OPEN ${if (recipes) "recipe" else "uses"} $id types=${list.map { it.type }.distinct()}")
    }

    private fun back() {
        popup = history.removeLastOrNull()
    }

    // ----- layout ----------------------------------------------------------------------------------------------------

    /** Whether the list fits beside [screen]; also sets the list rectangle. */
    private fun fits(screen: AbstractContainerScreen<*>, width: Int, height: Int): Boolean {
        val access = accessOf(screen)
        val right = access.left + access.imageWidth
        val free = width - right - 2 * MARGIN
        val cols = minOf(host.columns(), free / CELL)
        if (cols < minOf(MIN_COLS, host.columns())) return false
        listW = cols * CELL
        listX = width - MARGIN - listW
        listTop = MARGIN
        listBottom = height - MARGIN
        return listBottom - listTop > 120
    }

    private class AccessWrapper(val left: Int, val top: Int, val imageWidth: Int)

    private fun accessOf(screen: AbstractContainerScreen<*>): AccessWrapper {
        val access = screen as AbstractContainerScreenAccess
        return AccessWrapper(access.`geileraddons$getLeftPos`(), access.`geileraddons$getTopPos`(), access.`geileraddons$getImageWidth`())
    }

    /** Outlines the slots whose item name or lore holds the query and dims the other items; every slot of the open screen counts. */
    private fun drawSlotSearch(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        slotMatches = 0
        slotTotal = 0
        val terms = SlotSearch.terms(query)
        if (!slotSearch || terms.isEmpty()) return
        val access = accessOf(screen)
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue
            slotTotal++
            val x = access.left + slot.x
            val y = access.top + slot.y
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()
            if (SlotSearch.matches(terms, stack.hoverName.string, lore)) {
                slotMatches++
                g.outline(x - 1, y - 1, 18, 18, SEARCH_YELLOW)
            } else g.fill(x, y, x + 16, y + 16, 0x99000000.toInt())
        }
    }

    // ----- drawing ---------------------------------------------------------------------------------------------------

    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        hits.clear()
        hoverId = null
        val font = Minecraft.getInstance().font
        val compiled = ItemListRepository.compiled
        if (compiled == null) {
            ItemListRepository.request()
            g.text(font, "Item List: ${ItemListRepository.status}", g.guiWidth() - font.width("Item List: ${ItemListRepository.status}") - MARGIN, MARGIN, 0xFFAAAAAA.toInt(), true)
            return
        }
        val stacks = compiled.stacks
        stacks.beginFrame()
        refresh(compiled)
        draws++
        val frameStarted = System.nanoTime()
        val access = accessOf(screen)
        var tooltip: Pair<ItemStack, Component?>? = null
        var plainTip: Component? = null
        if (open) drawSlotSearch(screen, g)
        if (open && fits(screen, g.guiWidth(), g.guiHeight())) drawList(g, font, compiled, stacks, mouseX, mouseY)
        if (favorites.isNotEmpty()) drawFavorites(g, compiled, stacks, mouseX, mouseY, access.left)
        // The window sits on a dimmed screen and gets the last say over hover.
        if (popup != null) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), SHADE)
            hits.clear()
            hoverId = null
            try {
                drawPopup(g, font, compiled, stacks, mouseX, mouseY)
            } catch (exception: RuntimeException) {
                popupErrors++
                host.event("POPUP error ${exception.javaClass.simpleName}: ${exception.message?.take(80)}")
                popup = null
                history.clear()
            }
        }
        val hit = hits.lastOrNull { mouseX >= it.x && mouseX < it.x + it.w && mouseY >= it.y && mouseY < it.y + it.h }
        hoverId = hit?.id
        if (hit?.id != null) {
            compiled.byId[hit.id]?.let { entry ->
                val stack = stacks.get(entry)
                if (stack.isEmpty) plainTip = LegacyItemStacks.legacyText(entry.name) else tooltip = stack to null
            }
        } else plainTip = hit?.tip
        tooltip?.let { tooltips++; g.setTooltipForNextFrame(font, it.first, mouseX, mouseY) }
        plainTip?.let { g.setTooltipForNextFrame(font, it, mouseX, mouseY) }
        // Time spent handing this frame's items and text to the GUI; the GPU work after that is not in it.
        val drawNanos = System.nanoTime() - frameStarted
        drawNanosTotal += drawNanos
        if (drawNanos > drawNanosMax) drawNanosMax = drawNanos
        if (System.currentTimeMillis() < toastUntil) {
            g.text(font, toast, (g.guiWidth() - font.width(toast)) / 2, g.guiHeight() - 40, 0xFFFFFF55.toInt(), true)
        }
    }

    private fun cell(g: GuiGraphicsExtractor, entry: Entry?, stack: ItemStack, x: Int, y: Int, mouseX: Int, mouseY: Int, click: (Int) -> Unit) {
        val hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL
        if (hover) {
            if (mc) g.fill(x, y, x + CELL, y + CELL, 0x59FFFFFF) else GuiTheme.roundedRect(g, x, y, CELL, CELL, 3, 0x40FFFFFF)
        }
        g.item(stack, x + 1, y + 1)
        hits.add(Hit(x, y, CELL, CELL, entry?.id, null, click))
    }

    private fun drawFavorites(g: GuiGraphicsExtractor, compiled: ItemListRepository.Compiled, stacks: LegacyItemStacks.Store, mouseX: Int, mouseY: Int, left: Int) {
        val cols = ((left - 2 * MARGIN) / CELL).coerceIn(1, 8)
        val shown = favorites.toList().mapNotNull { compiled.byId[it] }
        // All rarity squares first, then all items: a fill between two items would push every later item up a layer.
        if (host.rarityBackgrounds()) {
            for ((i, entry) in shown.withIndex()) rarity(g, entry, MARGIN + (i % cols) * CELL, MARGIN + (i / cols) * CELL)
        }
        for ((i, entry) in shown.withIndex()) {
            val x = MARGIN + (i % cols) * CELL
            val y = MARGIN + (i / cols) * CELL
            cell(g, entry, stacks.get(entry), x, y, mouseX, mouseY) { button -> onEntryClick(compiled, entry, button) }
        }
    }

    /** The rarity square behind the item of [entry] in the cell at [x], [y]; nothing for an entry with no rarity. */
    private fun rarity(g: GuiGraphicsExtractor, entry: Entry, x: Int, y: Int) {
        val color = entry.rarityColor ?: return
        host.drawRarity(g, color, x + 1, y + 1)
    }

    private fun onEntryClick(compiled: ItemListRepository.Compiled, entry: Entry, button: Int) {
        if (button == 0) openPopup(compiled, entry.id, true) else if (button == 1) openPopup(compiled, entry.id, false)
    }

    private fun drawList(g: GuiGraphicsExtractor, font: Font, compiled: ItemListRepository.Compiled, stacks: LegacyItemStacks.Store, mouseX: Int, mouseY: Int) {
        val toolbarH = 16
        val gridY = listTop + toolbarH + 4
        val cols = listW / CELL
        val bottomH = CHIP + 3 + SEARCH_H
        val auto = ((listBottom - bottomH - 4 - gridY) / CELL).coerceAtLeast(1)
        val rows = if (host.rows() > 0) minOf(auto, host.rows()) else auto
        // A capped grid pulls the filter buttons and search bar up under it.
        val bottom = if (rows < auto) gridY + rows * CELL + 4 + bottomH else listBottom
        val perPage = cols * rows
        val pages = maxOf(1, (results.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        // Toolbar: the sort button on the left, the page arrows and counter centered (pushed right when the list is narrow).
        val sortText = sortLabel(sort)
        val sortW = font.width(sortText) + 8
        val sortHover = mouseX >= listX && mouseX < listX + sortW && mouseY >= listTop && mouseY < listTop + 16
        InventorySurfaces.button(g, font, listX, listTop, sortW, 16, sortText, sortHover, mc)
        val sortHint = Component.literal("Sort: " + sortTip(sort)).append(Component.literal("\nClick: next sort, right click: previous").withStyle(net.minecraft.ChatFormatting.GRAY))
        hits.add(Hit(listX, listTop, sortW, 16, null, sortHint) { button -> changeSort(button) })
        val label = "${page + 1}/$pages"
        val blockX = maxOf(listX + listW / 2 - 46, listX + sortW + 4)
        val mid = blockX + 46
        val prevX = blockX
        val nextX = blockX + 76
        arrow(g, font, prevX, listTop, "<", mouseX, mouseY) { page = (page - 1 + pages) % pages }
        arrow(g, font, nextX, listTop, ">", mouseX, mouseY) { page = (page + 1) % pages }
        g.text(font, label, mid - font.width(label) / 2, listTop + 4, TEXT, true)
        // Items float, no panel behind them. All rarity squares first, then all items (see drawFavorites).
        val start = page * perPage
        if (host.rarityBackgrounds()) {
            for (i in 0 until perPage) {
                val entry = results.getOrNull(start + i) ?: break
                rarity(g, entry, listX + (i % cols) * CELL, gridY + (i / cols) * CELL)
            }
        }
        for (i in 0 until perPage) {
            val entry = results.getOrNull(start + i) ?: break
            val x = listX + (i % cols) * CELL
            val y = gridY + (i / cols) * CELL
            cell(g, entry, stacks.get(entry), x, y, mouseX, mouseY) { button -> onEntryClick(compiled, entry, button) }
        }
        if (results.isEmpty()) g.text(font, "No items match.", listX + 4, gridY + 4, 0xFFAAAAAA.toInt(), true)
        listBounds = intArrayOf(listX, listTop, listW, bottom - listTop)
        // Filter buttons, then the search bar.
        val chipY = bottom - SEARCH_H - 3 - CHIP
        val gap = 2
        val chip = minOf(CHIP, (listW - gap * (FILTERS.size - 1)) / FILTERS.size)
        for ((i, filter) in FILTERS.withIndex()) {
            val x = listX + i * (chip + gap)
            val on = category == filter.category
            flat(g, x, chipY, chip, chip, on, mouseX, mouseY)
            g.item(filter.stack, x + (chip - 16) / 2, chipY + (chip - 16) / 2)
            hits.add(Hit(x, chipY, chip, chip, null, filter.tip()) { setCategory(filter.category) })
        }
        drawSearch(g, font, listX, bottom - SEARCH_H, listW, mouseX, mouseY)
    }

    private var listBounds = IntArray(4)

    private fun arrow(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, label: String, mouseX: Int, mouseY: Int, action: () -> Unit) {
        val hover = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16
        InventorySurfaces.button(g, font, x, y, 16, 16, label, hover, mc)
        hits.add(Hit(x, y, 16, 16, null, null) { action() })
    }

    /** A square filter button: raised vanilla bevel, or a flat rounded fill in the Click GUI look. */
    private fun flat(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, on: Boolean, mouseX: Int, mouseY: Int) {
        val hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        if (mc) {
            InventorySurfaces.card(g, x, y, w, h, on, true)
            if (hover) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x40FFFFFF)
        } else {
            GuiTheme.roundedRect(g, x, y, w, h, GuiTheme.RADIUS_SMALL, if (on) GuiTheme.SLIDER_FILL else if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        }
    }

    private fun drawSearch(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        g.fill(x + 2, y + 3, x + w + 2, y + SEARCH_H + 3, 0x55000000)
        if (mc) {
            g.fill(x - 1, y - 1, x + w + 1, y + SEARCH_H + 1, 0xFF000000.toInt())
            InventorySurfaces.bevel(g, x, y, w, SEARCH_H, 0xFF727272.toInt(), 1)
            if (slotSearch) g.outline(x - 1, y - 1, w + 2, SEARCH_H + 2, SEARCH_YELLOW)
            else if (focused) g.outline(x - 1, y - 1, w + 2, SEARCH_H + 2, 0xFFFFFFFF.toInt())
        } else {
            GuiTheme.roundedRect(g, x, y, w, SEARCH_H, GuiTheme.RADIUS_SMALL, GuiTheme.BUTTON_BG)
            if (slotSearch) GuiTheme.roundedRectOutline(g, x, y, w, SEARCH_H, GuiTheme.RADIUS_SMALL, SEARCH_YELLOW)
            else if (focused) GuiTheme.roundedRectOutline(g, x, y, w, SEARCH_H, GuiTheme.RADIUS_SMALL, GuiTheme.SLIDER_FILL)
        }
        hits.add(Hit(x, y, w, SEARCH_H, null, Component.literal("Double-click: search the open inventory")) { button ->
            if (button == 1) setQuery("")
            else if (button == 0) {
                val now = System.currentTimeMillis()
                if (now - lastSearchClick < DOUBLE_CLICK_MS) {
                    slotSearch = !slotSearch
                    host.event("SLOT_SEARCH ${if (slotSearch) "on" else "off"} query='${query.take(32)}'")
                    lastSearchClick = 0L
                } else lastSearchClick = now
                searchPress = true
            }
            focused = true
        })
        val textY = y + (SEARCH_H - font.lineHeight) / 2 + 1
        val result = calc?.let { "= " + SearchCalculator.format(it) }
        val room = w - 8 - (result?.let { font.width(it) + 6 } ?: 0)
        searchTextX = x + 4
        searchTextW = room
        val after = TextFieldInput.draw(g, font, queryField, x + 4, textY, room, 0xFFFFFFFF.toInt(), 0xAA3366CC.toInt(), focused, true)
        if (ghostApplies() && result == null) {
            g.text(font, ghost.substring(query.trim().length), after, textY, 0xFFAAAAAA.toInt(), true)
        }
        if (result != null) g.text(font, result, x + w - 4 - font.width(result), textY, 0xFF55FF55.toInt(), true)
    }

    // ----- recipe window ---------------------------------------------------------------------------------------------

    private fun priceSnapshot(): PriceSnapshot = PriceDisplayModule.INSTANCE.prices()

    private fun drawPopup(g: GuiGraphicsExtractor, font: Font, compiled: ItemListRepository.Compiled, stacks: LegacyItemStacks.Store, mouseX: Int, mouseY: Int) {
        val p = popup ?: return
        val name = compiled.byId[p.id]?.plainName ?: p.id
        val all = if (p.recipes) compiled.index.recipesFor(p.id) else compiled.index.usesOf(p.id)
        val types = ItemListData.TYPE_ORDER.filter { t -> all.any { it.type == t } }
        if (types.isEmpty()) {
            popup = null
            return
        }
        if (p.tab !in types) p.tab = types.first()
        val ofTab = all.filter { it.type == p.tab }
        p.page = p.page.coerceIn(0, ofTab.size - 1)
        val recipe = ofTab[p.page]
        val w = POPUP_W
        val h = POPUP_H
        val x = (g.guiWidth() - w) / 2
        val y = (g.guiHeight() - h) / 2
        // The window blocks everything behind it.
        hits.add(Hit(0, 0, g.guiWidth(), g.guiHeight(), null, null) { })
        hits.add(Hit(x, y, w, h, null, null) { })
        // Tabs on top of the panel.
        for ((i, type) in types.withIndex()) {
            val tx = x + 8 + i * 24
            val on = type == p.tab
            val ty = y - (if (on) 22 else 20)
            if (mc) InventorySurfaces.card(g, tx, ty, 22, 22 + 2, on, true)
            else GuiTheme.roundedRect(g, tx, ty, 22, 24, GuiTheme.RADIUS_SMALL, if (on) GuiTheme.PANEL_TOP else GuiTheme.BUTTON_BG)
            g.item(TAB_ICONS[type] ?: ItemStack(Items.PAPER), tx + 3, ty + 3)
            hits.add(Hit(tx, ty, 22, 24, null, Component.literal(typeTitle(type))) { p.tab = type; p.page = 0 })
        }
        InventorySurfaces.panel(g, x, y, w, h, mc, 1f, false)
        val label = if (mc) InventorySurfaces.labelColor(true) else GuiTheme.TEXT_PRIMARY
        val sub = if (mc) 0xFF555555.toInt() else GuiTheme.TEXT_SECONDARY
        // Header: back, title, close.
        arrow(g, font, x + 6, y + 6, "<", mouseX, mouseY) { back() }
        arrow(g, font, x + w - 22, y + 6, "x", mouseX, mouseY) { popup = null; history.clear() }
        g.text(font, (if (p.recipes) "Recipe: " else "Uses: ") + name, x + 26, y + 10, label, false)
        g.text(font, typeTitle(p.tab), x + 8, y + 28, sub, false)
        if (ofTab.size > 1) {
            val pageText = "${p.page + 1}/${ofTab.size}"
            arrow(g, font, x + w - 22 - 4 - 16 - font.width(pageText) - 4 - 16 - 4, y + 6, "<", mouseX, mouseY) { p.page = (p.page - 1 + ofTab.size) % ofTab.size }
            g.text(font, pageText, x + w - 22 - 4 - 16 - 4 - font.width(pageText) - 4, y + 10, label, false)
            arrow(g, font, x + w - 22 - 4 - 16, y + 6, ">", mouseX, mouseY) { p.page = (p.page + 1) % ofTab.size }
        }
        // Recipe slots.
        val layout = RecipeLayout.of(recipe)
        val cx = x + 10
        val cy = y + 40
        fun slot(c: Cell) {
            val sx = cx + c.x * CELL
            val sy = cy + c.y * CELL
            InventorySurfaces.slot(g, sx + 1, sy + 1, mc)
            val entry = compiled.byId[c.id]
            val stack = entry?.let { stacks.get(it) } ?: ItemStack(Items.BARRIER)
            g.item(stack, sx + 1, sy + 1)
            if (c.amount > 1) g.itemDecorations(font, stack, sx + 1, sy + 1, SearchCalculator.format(c.amount))
            val hover = mouseX >= sx && mouseX < sx + CELL && mouseY >= sy && mouseY < sy + CELL
            if (hover) g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0x80FFFFFF.toInt())
            if (c.label.isNotEmpty()) g.text(font, c.label + "  " + (entry?.plainName ?: c.id), sx + CELL + 4, sy + 5, label, false)
            val target = entry
            hits.add(Hit(sx, sy, CELL, CELL, c.id, null) { button ->
                val e = target
                if (e != null) onEntryClick(compiled, e, button)
            })
        }
        layout.inputs.forEach(::slot)
        layout.outputs.forEach(::slot)
        val ax = cx + layout.arrowX * CELL
        val ay = cy + layout.arrowY * CELL
        g.text(font, "->", ax + (CELL - font.width("->")) / 2, ay + 5, sub, false)
        var lineY = cy + layout.rows * CELL + 6
        for (line in layout.lines) {
            g.text(font, line, cx, lineY, label, false)
            lineY += font.lineHeight + 1
        }
        if (recipe.type == ItemListData.ESSENCE) drawFutureStars(g, font, compiled, ofTab, recipe, cx, lineY + 4, y + h - 30, COST_X - 20, label, sub)
        drawCost(g, font, compiled, recipe, x + COST_X, y + 28, label, sub)
        // Quick actions.
        var bx = x + 8
        val by = y + h - 24
        val entry = compiled.byId[p.id]
        val actions = listOf<Pair<String, () -> Unit>>(
            "Bazaar" to { command("bz ${entry?.plainName ?: p.id}") },
            "AH" to { command("ahs ${entry?.plainName ?: p.id}") },
            "Wiki" to { wiki(entry) },
            "Copy ID" to { Minecraft.getInstance().keyboardHandler.clipboard = p.id; say("Copied ${p.id}") },
            "/recipe" to { command("recipe ${p.id}") },
        )
        for ((text, action) in actions) {
            val bw = font.width(text) + 10
            val hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 16
            InventorySurfaces.button(g, font, bx, by, bw, 16, text, hover, mc)
            hits.add(Hit(bx, by, bw, 16, null, null) { action() })
            bx += bw + 4
        }
    }

    /** Runs the command and leaves the window and the inventory behind it, so the screen the command opens is the only one. */
    private fun command(text: String) {
        popup = null
        history.clear()
        Minecraft.getInstance().gui.screen()?.onClose()
        geiler.addons.client.utility.PlayerAlerts.command(text)
    }

    private fun wiki(entry: Entry?) {
        val url = entry?.wikiUrl
        if (url == null) {
            say("No wiki page for ${entry?.plainName ?: "this item"}")
            return
        }
        try {
            net.minecraft.util.Util.getPlatform().openUri(URI.create(url))
        } catch (_: RuntimeException) {
            say("Could not open the wiki")
        }
    }

    private fun typeTitle(type: String): String = when (type) {
        ItemListData.CRAFTING -> "Crafting"
        ItemListData.FORGE -> "Forge"
        ItemListData.NPC_SHOP -> "NPC shop"
        ItemListData.TRADE -> "Trade"
        ItemListData.DROPS -> "Mob drops"
        ItemListData.KAT -> "Kat upgrade"
        else -> "Essence upgrade"
    }

    private fun drawCost(g: GuiGraphicsExtractor, font: Font, compiled: ItemListRepository.Compiled, recipe: ItemRecipe, x: Int, top: Int, label: Int, sub: Int) {
        if (recipe.type == ItemListData.DROPS) return
        val snapshot = priceSnapshot()
        val instant = host.buyInstantly()
        var y = top
        g.text(font, "Cost", x, y, label, false)
        y += font.lineHeight + 3
        var total = 0.0
        var known = true
        for (input in recipe.inputs) {
            val unit = ItemPrices.unit(input.id, snapshot, instant)
            val name = compiled.byId[input.id]?.plainName ?: input.id
            val text = SearchCalculator.format(input.amount) + "x " + name
            val price = unit?.let { it * input.amount }
            if (price == null) known = false else total += price
            val priceText = price?.let { SearchCalculator.format(Math.round(it).toDouble()) } ?: "?"
            // The price keeps its room; the name is cut to what is left, so the two never overlap.
            g.text(font, font.plainSubstrByWidth(text, COST_W - font.width(priceText) - 8), x, y, sub, false)
            g.text(font, priceText, x + COST_W - font.width(priceText), y, label, false)
            y += font.lineHeight + 2
        }
        y += 2
        val totalText = (if (known) "" else "~") + SearchCalculator.format(Math.round(total).toDouble())
        g.text(font, "Total", x, y, label, false)
        g.text(font, totalText, x + COST_W - font.width(totalText), y, label, false)
        y += font.lineHeight + 2
        val out = recipe.outputs.firstOrNull() ?: return
        val buy = ItemPrices.unit(out.id, snapshot, instant)?.let { it * out.amount }
        if (buy != null && total > 0 && recipe.type != ItemListData.NPC_SHOP) {
            val buyText = SearchCalculator.format(Math.round(buy).toDouble())
            g.text(font, "Buy", x, y, sub, false)
            g.text(font, buyText, x + COST_W - font.width(buyText), y, sub, false)
            y += font.lineHeight + 2
            val diff = buy - total
            val good = if (mc) (if (diff >= 0) 0xFF00AA00.toInt() else 0xFFAA0000.toInt()) else (if (diff >= 0) 0xFF4ADE80.toInt() else GuiTheme.TEXT_ERROR)
            val diffText = (if (diff >= 0) "+" else "") + SearchCalculator.format(Math.round(diff).toDouble())
            g.text(font, if (diff >= 0) "Saves" else "Costs more", x, y, good, false)
            g.text(font, diffText, x + COST_W - font.width(diffText), y, good, false)
        }
    }

    /** Coins for all inputs of [recipe], or null when any has no price. */
    private fun recipeCost(recipe: ItemRecipe, snapshot: PriceSnapshot, instant: Boolean): Double? {
        var total = 0.0
        for (input in recipe.inputs) total += (ItemPrices.unit(input.id, snapshot, instant) ?: return null) * input.amount
        return total
    }

    /** Essence upgrades: what every later star costs, and the total to the last one. */
    private fun drawFutureStars(g: GuiGraphicsExtractor, font: Font, compiled: ItemListRepository.Compiled, all: List<ItemRecipe>, current: ItemRecipe, x: Int, top: Int, bottom: Int, width: Int, label: Int, sub: Int) {
        val star = current.extra["star"]?.toIntOrNull() ?: return
        val future = all.filter { (it.extra["star"]?.toIntOrNull() ?: 0) > star }.sortedBy { it.extra["star"]?.toIntOrNull() ?: 0 }
        if (future.isEmpty()) return
        val snapshot = priceSnapshot()
        val instant = host.buyInstantly()
        var y = top
        var known = true
        var coins = 0.0
        val totals = HashMap<String, Double>()
        for (r in future) {
            r.inputs.forEach { totals.merge(it.id, it.amount, Double::plus) }
            recipeCost(r, snapshot, instant)?.let { coins += it } ?: run { known = false }
        }
        g.text(font, "Next stars", x, y, label, false)
        y += font.lineHeight + 2
        val summary = "All remaining: " + totals.entries.sortedBy { it.key }.joinToString(", ") { SearchCalculator.format(it.value) + " " + (compiled.byId[it.key]?.plainName ?: it.key) }
        val coinsText = (if (known) "" else "~") + SearchCalculator.format(Math.round(coins).toDouble())
        g.text(font, font.plainSubstrByWidth(summary, width), x, y, sub, false)
        y += font.lineHeight + 1
        g.text(font, "= $coinsText coins", x, y, label, false)
        y += font.lineHeight + 3
        for (r in future) {
            if (y + font.lineHeight > bottom) break
            val priceText = recipeCost(r, snapshot, instant)?.let { SearchCalculator.format(Math.round(it).toDouble()) } ?: "?"
            val text = "Star ${r.extra["star"]}: " + r.inputs.joinToString(", ") { SearchCalculator.format(it.amount) + " " + (compiled.byId[it.id]?.plainName ?: it.id) }
            g.text(font, font.plainSubstrByWidth(text, width - font.width(priceText) - 8), x, y, sub, false)
            g.text(font, priceText, x + width - font.width(priceText), y, label, false)
            y += font.lineHeight + 1
        }
    }

    // ----- input -----------------------------------------------------------------------------------------------------

    /** True when the click was used and must not reach the screen. */
    fun handleClick(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        val hit = hits.lastOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }
        if (hit == null) {
            focused = false
            return false
        }
        focused = false
        searchPress = false
        hit.click(event.button())
        if (searchPress) {
            searchPress = false
            TextFieldInput.mousePressed(queryField, Minecraft.getInstance().font, event.x(), event.y(), searchTextX, searchTextW,
                event.hasShiftDown(), 1)
            searchDragging = true
        }
        return true
    }

    /** Extends the search selection while the mouse button is held after a press on the search bar. */
    fun handleDrag(event: MouseButtonEvent): Boolean {
        if (!searchDragging || event.button() != 0) return false
        TextFieldInput.mouseDragged(queryField, Minecraft.getInstance().font, event.x(), searchTextX, searchTextW)
        return true
    }

    fun handleRelease(event: MouseButtonEvent): Boolean {
        if (!searchDragging || event.button() != 0) return false
        searchDragging = false
        return true
    }

    /** The grey completion only shows while the caret sits at the end of the text with nothing selected. */
    private fun ghostApplies(): Boolean = !queryField.hasSelection() && queryField.cursor == query.length &&
        ghost.length > query.length && ghost.lowercase().startsWith(query.trim().lowercase())

    fun handleScroll(mouseX: Double, mouseY: Double, vertical: Double): Boolean {
        if (popup != null) return true
        val b = listBounds
        if (!open || b[2] == 0 || mouseX < b[0] || mouseX >= b[0] + b[2] || mouseY < b[1] || mouseY >= b[1] + b[3]) return false
        val step = if (vertical > 0) -1 else 1
        page = maxOf(0, page + step)
        return true
    }

    fun handleChar(event: CharacterEvent): Boolean {
        if (!focused) return false
        TextFieldInput.handleChar(queryField, event, "item-list-search")
        return true
    }

    /** [recipeKey], [usesKey] and [favoriteKey] are the Controls bindings; returns true when the key was used. */
    fun handleKey(event: KeyEvent, recipeKey: net.minecraft.client.KeyMapping, usesKey: net.minecraft.client.KeyMapping, favoriteKey: net.minecraft.client.KeyMapping): Boolean {
        val key = event.key()
        val ctrl = event.hasControlDown()
        val compiled = ItemListRepository.compiled ?: return false
        if (ctrl && key == GLFW.GLFW_KEY_O) {
            toggleOpen()
            return true
        }
        if (popup != null) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { popup = null; history.clear(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { back(); return true }
            }
            hover(compiled, event, recipeKey, usesKey, favoriteKey)
            return true
        }
        if (!open) return false
        if (ctrl && key == GLFW.GLFW_KEY_F) {
            focused = true
            return true
        }
        if (focused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> focused = false
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> calc?.let {
                    Minecraft.getInstance().keyboardHandler.clipboard = SearchCalculator.plain(it)
                    say("Copied " + SearchCalculator.format(it))
                }
                GLFW.GLFW_KEY_TAB -> if (ghostApplies() && queryField.cursor == query.length) setQuery(ghost)
                else -> {
                    val takesGhost = key == GLFW.GLFW_KEY_RIGHT && !ctrl && !event.hasShiftDown() &&
                        queryField.cursor == query.length && ghostApplies()
                    if (takesGhost) setQuery(ghost) else TextFieldInput.handleKey(queryField, event, "item-list-search")
                }
            }
            return true
        }
        return hover(compiled, event, recipeKey, usesKey, favoriteKey)
    }

    private fun hover(compiled: ItemListRepository.Compiled, event: KeyEvent, recipeKey: net.minecraft.client.KeyMapping, usesKey: net.minecraft.client.KeyMapping, favoriteKey: net.minecraft.client.KeyMapping): Boolean {
        val id = hoverId ?: return false
        val entry = compiled.byId[id] ?: return false
        when {
            recipeKey.matches(event) -> openPopup(compiled, id, true)
            usesKey.matches(event) -> openPopup(compiled, id, false)
            favoriteKey.matches(event) -> toggleFavorite(id, entry.plainName)
            else -> return false
        }
        return true
    }

    fun diagnosticsLine(): String =
        "open=$open slotSearch=$slotSearch slotMatches=$slotMatches/$slotTotal sort=$sort popup=${popup?.id} fav=${favorites.size} results=${results.size} searches=$searches " +
            "drawAvgMs=${if (draws == 0L) 0 else drawNanosTotal / draws / 1000 / 1000.0} drawMaxMs=${drawNanosMax / 1000 / 1000.0} " +
            "fallbackHeads=${ItemListRepository.compiled?.fallbacks ?: 0} paperLeft=${ItemListRepository.compiled?.paperLeft ?: 0} " +
            "searchAvgMs=${if (searches == 0L) 0 else searchNanosTotal / searches / 1000 / 1000.0} searchMaxMs=${searchNanosMax / 1000 / 1000.0} " +
            "recipeOpens=$recipeOpens usesOpens=$usesOpens popupErrors=$popupErrors stacks=$stacksBuilt stackFailures=$stackFailures draws=$draws tooltips=$tooltips"

    private class Filter(val category: String, val label: String, val stack: ItemStack, val about: String) {
        /** Name on the first line, what the filter keeps on the second. */
        fun tip(): Component = Component.literal(label).append(Component.literal("\n$about").withStyle(net.minecraft.ChatFormatting.GRAY))
    }

    companion object {
        const val CELL = 18
        private const val MARGIN = 6
        private const val CHIP = 18
        private const val SEARCH_H = 17
        private const val MIN_COLS = 8
        private const val MAX_QUERY = 64
        private const val MAX_HISTORY = 20
        private const val DOUBLE_CLICK_MS = 400L
        private val SEARCH_YELLOW = 0xFFFFFF55.toInt()
        private const val POPUP_W = 380
        private const val POPUP_H = 240
        private const val COST_X = 222
        private const val COST_W = 148
        private const val SHADE = 0x59000000
        private val TEXT = 0xFFFFFFFF.toInt()

        private val FILTERS: List<Filter> by lazy {
            listOf(
                Filter(ItemListData.CAT_ALL, "All items", ItemStack(Items.NETHER_STAR), "Every item, mob and NPC in the list, no filter."),
                Filter(ItemListData.CAT_WEAPON, "Weapons", ItemStack(Items.IRON_SWORD), "Swords, bows, staffs and other combat gear."),
                Filter(ItemListData.CAT_MATERIAL, "Materials", ItemStack(Items.DIAMOND), "Crafting ingredients, drops, ores and other raw items."),
                Filter(ItemListData.CAT_PET, "Pets", ItemStack(Items.BONE), "Pets and pet items."),
                Filter(ItemListData.CAT_BOOK, "Enchanted books", ItemStack(Items.ENCHANTED_BOOK), "One book for each enchantment and level."),
                Filter(ItemListData.CAT_MOB, "Mobs", ItemStack(Items.ZOMBIE_HEAD), "Mobs; open one to see what it drops."),
                Filter(ItemListData.CAT_NPC, "NPCs", ItemStack(Items.PLAYER_HEAD), "NPCs and what they sell or trade."),
                Filter(ItemListData.CAT_VANILLA, "Vanilla items", ItemStack(Items.GRASS_BLOCK), "Plain Minecraft items with no Skyblock version."),
            )
        }

        private val TAB_ICONS: Map<String, ItemStack> by lazy {
            mapOf(
                ItemListData.CRAFTING to ItemStack(Items.CRAFTING_TABLE),
                ItemListData.FORGE to ItemStack(Items.ANVIL),
                ItemListData.NPC_SHOP to ItemStack(Items.EMERALD),
                ItemListData.TRADE to ItemStack(Items.GOLD_INGOT),
                ItemListData.DROPS to ItemStack(Items.ROTTEN_FLESH),
                ItemListData.KAT to ItemStack(Items.NAME_TAG),
                ItemListData.ESSENCE to ItemStack(Items.NETHER_STAR),
            )
        }
    }
}
