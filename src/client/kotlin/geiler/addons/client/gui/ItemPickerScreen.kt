package geiler.addons.client.gui

import geiler.addons.client.itemlist.Entry
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.sign

/**
 * Searchable multi-select over the mod's item list (the NEU repository the Item List module loads), using the item
 * list's own search syntax. Clicking a row ticks or unticks it; Done hands back the ticked ids in order.
 */
class ItemPickerScreen(
    private val parent: Screen?,
    private val heading: String,
    initial: List<String>,
    private val limit: Int,
    private val done: (List<String>) -> Unit,
) : Screen(Component.literal(heading)) {
    private val chosen = LinkedHashSet(initial)
    private lateinit var search: EditBox
    private var filter = ""
    private var scroll = 0
    private var panel = GuiRect(0, 0, 0, 0)
    private var cacheFilter: String? = null
    private var cacheSource: Any? = null
    private var cache: List<Entry> = emptyList()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        ItemListRepository.request()
        panel = panelBounds()
        search = EditBox(font, panel.x + 10, panel.y + 34, panel.w - 20, 18, Component.literal("Search items"))
        search.setMaxLength(128)
        search.setValue(filter)
        search.setResponder { value ->
            filter = value
            scroll = 0
        }
        addRenderableWidget(search)
        setInitialFocus(search)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        panel = panelBounds()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, GuiTheme.fit(font, heading, panel.w - 20), panel.x + 10, panel.y + 9, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, GuiTheme.fit(font, "Click to tick items (${chosen.size}/$limit). Same search as the Item List.", panel.w - 20),
            panel.x + 10, panel.y + 21, GuiTheme.TEXT_MUTED)
        val compiled = ItemListRepository.compiled
        val view = viewRect()
        if (compiled == null) {
            graphics.text(font, "Item list: ${ItemListRepository.status}", view.x + 8, view.y + 8, GuiTheme.TEXT_MUTED)
        } else {
            val visible = results()
            val rows = maxOf(1, view.h / ROW)
            scroll = scroll.coerceIn(0, maxOf(0, visible.size - rows))
            graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
            for (index in scroll until minOf(visible.size, scroll + rows)) {
                val entry = visible[index]
                val y = view.y + (index - scroll) * ROW
                val hover = mouseX >= view.x && mouseX < view.x + view.w && mouseY >= y && mouseY < y + ROW
                val ticked = entry.id in chosen
                if (ticked || hover) {
                    GuiTheme.roundedRect(graphics, view.x, y, view.w - 4, ROW - 1, GuiTheme.RADIUS_SMALL,
                        if (ticked) GuiTheme.CATEGORY_SELECTED else GuiTheme.CATEGORY_HOVER)
                }
                graphics.item(compiled.stacks.get(entry), view.x + 4, y + 2)
                val color = if (ticked || hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY
                graphics.text(font, GuiTheme.fit(font, (if (ticked) "✔ " else "") + entry.plainName, view.w - 170), view.x + 26, y + 6, color)
                graphics.text(font, GuiTheme.fit(font, entry.id, 140), view.x + view.w - 150, y + 6,
                    if (ticked || hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_MUTED)
            }
            graphics.disableScissor()
            if (visible.isEmpty()) graphics.text(font, "No matching items.", view.x + 8, view.y + 8, GuiTheme.TEXT_MUTED)
        }
        val button = doneRect()
        val hoverDone = button.contains(mouseX, mouseY)
        GuiTheme.roundedRect(graphics, button.x, button.y, button.w, button.h, GuiTheme.RADIUS_SMALL,
            if (hoverDone) GuiTheme.CATEGORY_SELECTED else GuiTheme.CATEGORY_HOVER)
        graphics.text(font, "Done", button.x + (button.w - font.width("Done")) / 2, button.y + 5,
            if (hoverDone) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    /** Search results, rebuilt only when the text or the loaded repository changes. Ticked items come first when empty. */
    private fun results(): List<Entry> {
        val compiled = ItemListRepository.compiled ?: return emptyList()
        if (filter == cacheFilter && compiled === cacheSource) return cache
        cacheFilter = filter
        cacheSource = compiled
        cache = if (filter.isBlank()) chosen.mapNotNull { compiled.byId[it] } else compiled.search.search(filter).take(MAX_RESULTS)
        return cache
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        if (event.button() == 0 && doneRect().contains(x, y)) {
            finish()
            return true
        }
        val view = viewRect()
        if (event.button() == 0 && view.contains(x, y)) {
            val index = scroll + (y - view.y) / ROW
            results().getOrNull(index)?.let { entry ->
                if (!chosen.remove(entry.id) && chosen.size < limit) chosen.add(entry.id)
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = maxOf(0, scroll - sign(scrollY).toInt() * 3)
        return true
    }

    private fun finish() {
        done(chosen.toList())
        minecraft?.gui?.setScreen(parent)
    }

    /** Escape keeps the changes too: the ticks are the edit. */
    override fun onClose() = finish()

    private fun panelBounds(): GuiRect {
        val w = minOf(620, maxOf(1, width - 20))
        val h = minOf(430, maxOf(1, height - 20))
        return GuiRect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun viewRect(): GuiRect = GuiRect(panel.x + 8, panel.y + 58, panel.w - 16, panel.h - 92)

    private fun doneRect(): GuiRect = GuiRect(panel.x + panel.w - 90, panel.y + panel.h - 28, 80, 18)

    companion object {
        private const val ROW = 20
        private const val MAX_RESULTS = 200
    }
}
