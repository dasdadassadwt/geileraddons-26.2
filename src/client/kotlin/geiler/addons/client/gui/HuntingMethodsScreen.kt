package geiler.addons.client.gui

import geiler.addons.client.module.impl.rarityItem
import geiler.addons.client.config.ModConfig
import geiler.addons.client.hunting.HuntingMethods
import geiler.addons.client.hunting.HuntingMethods.Column
import geiler.addons.client.hunting.ShardPriceFeed
import geiler.addons.client.hunting.ShardValue
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.module.impl.HuntingMethodsModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The Hunting Methods screen (Click GUI top-bar shard button, `/ga hunt`): every huntable shard ranked by coins per
 * hour, with search, sortable columns, Hunting Fortune and price controls, and kill rates edited in place.
 */
class HuntingMethodsScreen(private val parent: Screen?) : Screen(Component.literal("Hunting Methods")) {
    private val module = HuntingMethodsModule.INSTANCE
    private lateinit var search: EditBox
    private lateinit var fortune: EditBox
    private lateinit var editor: EditBox
    private lateinit var addSearch: EditBox

    /** The "Add shard" picker is open. */
    private var adding = false
    private var addScroll = 0

    /** Table rows as drawn this frame (shard id and its row), for right-click removal. */
    private val rowHits = ArrayList<Pair<Rect, String>>()

    /** The cell being edited: shard id and which rate. */
    private var editing: Pair<String, Column>? = null

    /** Shards picked (click on the name) for "Group". */
    private val selected = LinkedHashSet<String>()
    private var contentHeight = 0

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px < x + w && py >= y && py < y + h
        val right get() = x + w
        val bottom get() = y + h
    }

    private val targets = ArrayList<Pair<Rect, () -> Unit>>()

    override fun isPauseScreen(): Boolean = false

    private fun panel(): Rect {
        val w = min(width - 24, 660)
        val h = min(height - 24, 380)
        return Rect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun list(panel: Rect): Rect = Rect(panel.x + 10, panel.y + 70, panel.w - 24, panel.h - 80)

    override fun init() {
        val panel = panel()
        search = EditBox(font, panel.x + 10, panel.y + 28, 150, 16, Component.literal("Search shards"))
        search.setHint(Component.literal("Search shard, mob, place"))
        search.setMaxLength(48)
        search.setValue(query)
        search.setResponder { query = it; scroll = 0 }
        addRenderableWidget(search)
        fortune = EditBox(font, panel.x + 260, panel.y + 28, 44, 16, Component.literal("Hunting Fortune"))
        fortune.setMaxLength(4)
        fortune.setValue(module.fortuneSetting().value().toInt().toString())
        fortune.setResponder { text ->
            text.toIntOrNull()?.coerceIn(0, 1000)?.let { module.fortuneSetting().setValue(it.toFloat()) }
        }
        addRenderableWidget(fortune)
        editor = EditBox(font, 0, 0, 50, 14, Component.literal("Rate"))
        editor.setMaxLength(9)
        editor.visible = false
        addRenderableWidget(editor)
        addSearch = EditBox(font, 0, 0, 200, 16, Component.literal("Find shard"))
        addSearch.setHint(Component.literal("Search any shard"))
        addSearch.setMaxLength(32)
        addSearch.setResponder { addScroll = 0 }
        addSearch.visible = false
        addRenderableWidget(addSearch)
        setInitialFocus(search)
        ItemListRepository.request()
        geiler.addons.client.profileviewer.PvConstants.get("attribute_shards")
    }

    /** Every hunting shard (bazaar id, name) from the attribute list, for the picker; empty while it loads. */
    private fun shardChoices(): List<Pair<String, String>> {
        val json = geiler.addons.client.profileviewer.PvConstants.get("attribute_shards") ?: return emptyList()
        return json.get("attributes")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            val a = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = a.get("bazaarName")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val name = a.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString ?: id
            id to name.replace(Regex("§."), "")
        }?.distinctBy { it.first }?.sortedBy { it.second } ?: emptyList()
    }

    /** The picker: a search box and the matching shards; a click adds one with no rate yet. */
    private fun drawPicker(graphics: GuiGraphicsExtractor, panel: Rect, mouseX: Int, mouseY: Int) {
        val w = 260
        val h = min(panel.h - 40, 240)
        val box = Rect(panel.x + (panel.w - w) / 2, panel.y + 30, w, h)
        GuiTheme.roundedRectBordered(graphics, box.x, box.y, box.w, box.h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "Add shard", box.x + 8, box.y + 7, GuiTheme.TEXT_PRIMARY, false)
        val close = Rect(box.right - 18, box.y + 4, 14, 14)
        graphics.text(font, "✕", close.x + 3, close.y + 3, if (close.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_SECONDARY, false)
        targets.add(0, close to { closePicker() })
        addSearch.setX(box.x + 8)
        addSearch.setY(box.y + 20)
        addSearch.setWidth(box.w - 16)
        val listed = module.methodList().map { it.shardId }.toSet()
        val q = addSearch.value.trim().lowercase(Locale.ROOT)
        val choices = shardChoices().filter { (id, name) -> id !in listed && (q.isEmpty() || name.lowercase(Locale.ROOT).contains(q) || id.lowercase(Locale.ROOT).contains(q)) }
        val list = Rect(box.x + 8, box.y + 42, box.w - 16, box.h - 48)
        val visible = list.h / ROW_H
        addScroll = addScroll.coerceIn(0, max(0, choices.size - visible))
        if (choices.isEmpty()) {
            val text = if (shardChoices().isEmpty()) "Loading shard list..." else "No shard matches."
            graphics.text(font, text, list.x + 4, list.y + 4, GuiTheme.TEXT_MUTED, false)
        }
        for ((i, choice) in choices.drop(addScroll).take(visible).withIndex()) {
            val r = Rect(list.x, list.y + i * ROW_H, list.w, ROW_H - 1)
            val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
            graphics.fill(r.x, r.y, r.right, r.bottom, if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.rarityItem(icon(choice.first, choice.second), r.x + 2, r.y + 1)
            graphics.text(font, GuiTheme.fit(font, choice.second, r.w - 26), r.x + 22, r.y + 6, GuiTheme.TEXT_PRIMARY, false)
            targets.add(0, r to { module.addMethod(choice.first, choice.second); click() })
        }
    }

    private fun openPicker() {
        if (editing != null) commitEdit()
        adding = true
        addSearch.visible = true
        addSearch.setValue("")
        setFocused(addSearch)
        addSearch.setFocused(true)
    }

    private fun closePicker() {
        adding = false
        addSearch.visible = false
        setFocused(search)
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        targets.clear()
        rowHits.clear()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val panel = panel()
        GuiTheme.roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        val back = Rect(panel.x + 10, panel.y + 8, 18 + font.width("Hunting Methods"), 14)
        graphics.text(font, "‹  Hunting Methods", back.x, back.y + 3,
            if (back.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_SECONDARY, false)
        targets.add(back to { onClose() })
        val status = "Prices: " + module.priceStatus(System.currentTimeMillis())
        graphics.text(font, status, panel.right - 10 - font.width(status), back.y + 3, GuiTheme.TEXT_MUTED, false)

        drawControls(graphics, panel, mouseX, mouseY)
        val prices = module.shardPrices()
        val rows = HuntingMethods.view(
            HuntingMethods.table(module.methodList(), prices, module.fortuneSetting().value().toDouble(), module.offers(),
                module::average, module.groupList()),
            query, sortColumn, descending)
        selected.retainAll(module.methodList().map { it.shardId }.toSet())
        var unit = 0
        unitRanks = IntArray(rows.size) { i -> if (rows[i].inGroup == null) ++unit else unit }
        val box = list(panel)
        drawHeader(graphics, box, mouseX, mouseY)
        val body = Rect(box.x, box.y + 14, box.w, box.h - 14)
        graphics.enableScissor(body.x, body.y, body.right, body.bottom)
        contentHeight = rows.size * ROW_H
        scroll = scroll.coerceIn(0, max(0, contentHeight - body.h))
        var editorPlaced = false
        val stacks = ItemListRepository.compiled
        stacks?.stacks?.beginFrame()
        for ((index, row) in rows.withIndex()) {
            val y = body.y + index * ROW_H - scroll
            if (y + ROW_H < body.y || y > body.bottom) continue
            val hovered = mouseY >= body.y && mouseY < body.bottom && Rect(body.x, y, body.w, ROW_H).contains(mouseX.toDouble(), mouseY.toDouble())
            if (hovered) GuiTheme.roundedRect(graphics, body.x, y, body.w, ROW_H - 1, GuiTheme.RADIUS_SMALL, GuiTheme.CARD_BG_HOVER)
            else if (index % 2 == 0) graphics.fill(body.x, y, body.right, y + ROW_H - 1, GuiTheme.CARD_BG)
            editorPlaced = drawRow(graphics, body, y, index, row, mouseX, mouseY, hovered) || editorPlaced
            rowHits.add(Rect(body.x, max(y, body.y), body.w, ROW_H) to row.method.shardId)
        }
        if (rows.isEmpty()) {
            val empty = if (module.methodList().isEmpty()) "No shards yet: click + Add shard. Right click a row to remove it." else "No shard matches."
            graphics.text(font, empty, body.x + 6, body.y + 6, GuiTheme.TEXT_MUTED, false)
        }
        graphics.disableScissor()
        if (!editorPlaced && editing != null) commitEdit()
        editor.visible = editing != null
        val rated = module.methodList().count { (it.killsPerHour ?: 0.0) > 0.0 }
        if (rated == 0 && module.methodList().isNotEmpty() && module.groupList().isEmpty()) {
            val hint = "Click a Kills/h cell to type your rate (saved to hunting-methods.json)"
            graphics.text(font, hint, box.x, panel.bottom - 9, GuiTheme.TEXT_WARN, false)
        } else if (module.methodList().size >= 2) {
            val hint = if (selected.isNotEmpty()) "${selected.size} selected: click Group to hunt them together"
            else "Click shard names to select shards hunted together, then Group. Right click a group to ungroup."
            graphics.text(font, GuiTheme.fit(font, hint, box.w), box.x, panel.bottom - 9, GuiTheme.TEXT_MUTED, false)
        }
        if (adding) drawPicker(graphics, panel, mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawControls(graphics: GuiGraphicsExtractor, panel: Rect, mouseX: Int, mouseY: Int) {
        graphics.text(font, "Hunting Fortune", panel.x + 172, panel.y + 32, GuiTheme.TEXT_SECONDARY, false)
        var x = panel.x + 316
        val price = module.priceSetting()
        for (choice in listOf("Sell Offer", "Insta-Sell")) {
            val chip = Rect(x, panel.y + 29, font.width(choice) + 12, 14)
            val selected = price.value() == choice
            GuiTheme.roundedRect(graphics, chip.x, chip.y, chip.w, chip.h, 4,
                if (selected) GuiTheme.SLIDER_FILL else if (chip.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.text(font, choice, chip.x + 6, chip.y + 3, if (selected) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY, false)
            targets.add(chip to { price.setValue(choice); module.note("UI PRICE $choice"); click() })
            x = chip.right + 6
        }
        val add = Rect(panel.right - 10 - font.width("Reload file") - 12 - 6 - font.width("+ Add shard") - 12, panel.y + 29, font.width("+ Add shard") + 12, 14)
        GuiTheme.roundedRect(graphics, add.x, add.y, add.w, add.h, 4,
            if (add.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        graphics.text(font, "+ Add shard", add.x + 6, add.y + 3, GuiTheme.TEXT_PRIMARY, false)
        targets.add(add to { openPicker(); click() })
        if (selected.size >= 2) {
            val label = "Group (${selected.size})"
            val group = Rect(add.x - 6 - font.width(label) - 12, panel.y + 29, font.width(label) + 12, 14)
            GuiTheme.roundedRect(graphics, group.x, group.y, group.w, group.h, 4,
                if (group.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.BUTTON_HOVER else GuiTheme.SLIDER_FILL)
            graphics.text(font, label, group.x + 6, group.y + 3, GuiTheme.TEXT_ON_ACCENT, false)
            targets.add(group to { module.groupMethods(selected.toList()); selected.clear(); click() })
        }
        val reload = Rect(panel.right - 10 - font.width("Reload file") - 12, panel.y + 29, font.width("Reload file") + 12, 14)
        GuiTheme.roundedRect(graphics, reload.x, reload.y, reload.w, reload.h, 4,
            if (reload.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
        graphics.text(font, "Reload file", reload.x + 6, reload.y + 3, GuiTheme.TEXT_PRIMARY, false)
        targets.add(reload to { module.reloadMethods(); click() })
        val note = "After 1.25% bazaar tax. Averages: prices this client saw."
        graphics.text(font, GuiTheme.fit(font, note, panel.w - 20), panel.x + 10, panel.y + 52, GuiTheme.TEXT_MUTED, false)
    }

    /** Column x positions inside [box]: the shard column takes what the number columns leave. */
    private fun columns(box: Rect): List<Pair<Column, Rect>> {
        val numbers = Column.entries.drop(1)
        val numberW = min(62, (box.w - 140) / numbers.size)
        val shardW = box.w - numberW * numbers.size
        val out = ArrayList<Pair<Column, Rect>>()
        out.add(Column.SHARD to Rect(box.x, 0, shardW, 0))
        for ((i, c) in numbers.withIndex()) out.add(c to Rect(box.x + shardW + i * numberW, 0, numberW, 0))
        return out
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor, box: Rect, mouseX: Int, mouseY: Int) {
        for ((column, r) in columns(box)) {
            val cell = Rect(r.x, box.y, r.w, 12)
            val active = column == sortColumn
            val label = column.label + if (active) (if (descending) " ▼" else " ▲") else ""
            val color = if (active) GuiTheme.TEXT_PRIMARY else if (cell.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.TEXT_SECONDARY else GuiTheme.TEXT_MUTED
            val text = GuiTheme.fit(font, label, cell.w - 4)
            val tx = if (column == Column.SHARD) cell.x + 22 else cell.right - 4 - font.width(text)
            graphics.text(font, text, tx, cell.y + 2, color, false)
            targets.add(cell to {
                if (sortColumn == column) descending = !descending else {
                    sortColumn = column
                    descending = column != Column.SHARD
                }
                click()
            })
        }
        graphics.fill(box.x, box.y + 12, box.right, box.y + 13, GuiTheme.BORDER)
    }

    /** Returns true when the open editor sits in this row (so it stays open). */
    private fun drawRow(graphics: GuiGraphicsExtractor, body: Rect, y: Int, index: Int, row: HuntingMethods.Row,
                        mouseX: Int, mouseY: Int, hovered: Boolean): Boolean {
        val m = row.method
        var editorHere = false
        val best = row.ranked && row.lostPerHour <= 0.0
        for ((column, r) in columns(body)) {
            val cell = Rect(r.x, y, r.w, ROW_H)
            when (column) {
                Column.SHARD -> {
                    val indent = if (row.inGroup != null) 10 else 0
                    if (m.shardId in selected) GuiTheme.roundedRect(graphics, cell.x + indent, y + 1, cell.w - indent - 2, ROW_H - 3, 3, GuiTheme.CATEGORY_SELECTED)
                    if (row.inGroup != null) graphics.fill(cell.x + 4, y, cell.x + 5, y + ROW_H - 1, GuiTheme.BORDER)
                    val members = row.group?.let { g -> module.methodList().filter { it.group == g.id } }
                    val first = members?.firstOrNull()
                    graphics.rarityItem(if (first != null) icon(first.shardId, first.name) else icon(m.shardId, m.name), cell.x + 2 + indent, y + 1)
                    val rank = if (row.inGroup == null && row.ranked && sortColumn == Column.PER_HOUR && descending) "${unitRank(index)}. " else ""
                    val label = if (row.group != null) "§l${m.name}" else m.name
                    graphics.text(font, GuiTheme.fit(font, rank + label, cell.w - 26 - indent), cell.x + 22 + indent, y + 6,
                        if (best) GuiTheme.SLIDER_FILL else GuiTheme.TEXT_PRIMARY, false)
                    if (hovered && cell.contains(mouseX.toDouble(), mouseY.toDouble())) {
                        val lines = if (row.group != null) listOf(Component.literal(m.name), Component.literal("§7Group of ${members?.size ?: 0} shards"),
                            Component.literal("§7Coins/h is the sum of its members."), Component.literal("§8Right click to ungroup"))
                        else listOf(Component.literal(m.name), Component.literal("§7${m.mob} — ${m.location}"),
                            Component.literal("§8${m.shardId}"), Component.literal("§8Click to select for a group"))
                        graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
                    }
                    if (row.group == null && y + ROW_H > body.y && y < body.bottom) targets.add(Rect(cell.x, max(y, body.y), cell.w, ROW_H) to {
                        if (!selected.remove(m.shardId)) selected.add(m.shardId)
                        click()
                    })
                }
                Column.SHARDS_PER_KILL -> if (row.group != null) {
                    graphics.text(font, "—", cell.right - 4 - font.width("—"), y + 6, GuiTheme.TEXT_MUTED, false)
                } else editorHere = rateCell(graphics, body, cell, y, row, column, mouseX, mouseY, hovered) || editorHere
                Column.KILLS -> editorHere = rateCell(graphics, body, cell, y, row, column, mouseX, mouseY, hovered) || editorHere
                else -> {
                    val value = when (column) {
                        Column.PER_HOUR -> row.perHour
                        Column.LOST -> row.lostPerHour
                        Column.OFFER -> row.sellOffer
                        Column.INSTA -> row.instantSell
                        Column.AVG3 -> row.avg3
                        else -> row.avg7
                    }
                    val text = when {
                        value.isNaN() -> "—"
                        column == Column.LOST && value <= 0.0 -> "best"
                        column == Column.LOST -> "-" + ShardValue.coins(value)
                        else -> ShardValue.coins(value)
                    }
                    val color = when {
                        value.isNaN() -> GuiTheme.TEXT_MUTED
                        column == Column.PER_HOUR -> GuiTheme.SLIDER_FILL
                        column == Column.LOST -> if (value <= 0.0) GuiTheme.SLIDER_FILL else GuiTheme.TEXT_WARN
                        else -> GuiTheme.TEXT_SECONDARY
                    }
                    graphics.text(font, text, cell.right - 4 - font.width(text), y + 6, color, false)
                }
            }
        }
        return editorHere
    }

    /** A Kills/h or Shards/kill cell, or the editor when it is open there; true when the editor sits here. */
    private fun rateCell(graphics: GuiGraphicsExtractor, body: Rect, cell: Rect, y: Int, row: HuntingMethods.Row, column: Column,
                         mouseX: Int, mouseY: Int, hovered: Boolean): Boolean {
        val m = row.method
        if (editing == (m.shardId to column)) {
            editor.setX(cell.x + 2)
            editor.setY(y + 3)
            editor.setWidth(cell.w - 4)
            return true
        }
        // A member without its own rate shows its share of the group total (~), muted; clicking sets its own.
        val own = if (column == Column.KILLS) (if (row.split) null else m.killsPerHour) else m.shardsPerKill
        val share = m.killsPerHour
        val text = own?.let { number(it) } ?: if (row.split && share != null) "~" + number(Math.round(share * 10) / 10.0) else "set"
        val cellHovered = hovered && cell.contains(mouseX.toDouble(), mouseY.toDouble())
        if (cellHovered) GuiTheme.roundedRect(graphics, cell.x + 2, y + 2, cell.w - 4, ROW_H - 5, 3, GuiTheme.BUTTON_BG)
        val color = when {
            own != null -> GuiTheme.TEXT_PRIMARY
            row.split -> GuiTheme.TEXT_MUTED
            else -> GuiTheme.TEXT_WARN
        }
        graphics.text(font, text, cell.right - 4 - font.width(text), y + 6, color, false)
        if (cellHovered && row.group != null && column == Column.KILLS) graphics.setTooltipForNextFrame(font, listOf(Component.literal("Total kills/h of the group"),
            Component.literal("§7Members without their own rate share it evenly.")), java.util.Optional.empty(), mouseX, mouseY)
        if (y >= body.y && y + ROW_H <= body.bottom + ROW_H) targets.add(Rect(cell.x, max(y, body.y), cell.w, ROW_H) to { startEdit(m, column, row.split) })
        return false
    }

    /** 1-based rank of the unit (single shard or group) per row of the current view. */
    private var unitRanks: IntArray = IntArray(0)

    private fun unitRank(index: Int): Int = unitRanks.getOrElse(index) { index + 1 }

    private val iconCache = HashMap<String, ItemStack>()

    /**
     * The shard's head from the item list once it is loaded; a prismarine shard until then. The item list keys shards by
     * attribute ("ATTRIBUTE_SHARD_FORAGING_SPEED;1" is the Foxtrot Shard), so the display name finds them.
     */
    private fun icon(shardId: String, name: String): ItemStack {
        iconCache[shardId]?.let { return it }
        val compiled = ItemListRepository.compiled ?: return FALLBACK
        val entry = compiled.byId[shardId] ?: compiled.byId["ATTRIBUTE_$shardId"]
            ?: compiled.entries.firstOrNull { it.id.startsWith("ATTRIBUTE_SHARD_") && it.plainName.equals(name, ignoreCase = true) }
        val stack = entry?.let { compiled.stacks.get(it) }?.takeIf { !it.isEmpty } ?: return FALLBACK.also {
            if (entry == null) iconCache[shardId] = FALLBACK
        }
        iconCache[shardId] = stack
        return stack
    }

    private fun number(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value)

    // ---- editing --------------------------------------------------------------------------------

    private fun startEdit(m: HuntingMethods.Method, column: Column, split: Boolean = false) {
        if (editing != null) commitEdit()
        editing = m.shardId to column
        val value = if (column == Column.KILLS) (if (split) null else m.killsPerHour) else m.shardsPerKill
        editor.setValue(value?.let { number(it) } ?: "")
        editor.visible = true
        setFocused(editor)
        editor.setFocused(true)
        click()
    }

    private fun commitEdit() {
        val (shardId, column) = editing ?: return
        editing = null
        editor.visible = false
        if (shardId.startsWith(HuntingMethods.GROUP_PREFIX)) {
            val id = shardId.removePrefix(HuntingMethods.GROUP_PREFIX)
            val total = editor.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_RATE }
            if (total != module.groupList().firstOrNull { it.id == id }?.killsPerHour) module.setGroupRate(id, total)
            setFocused(search)
            return
        }
        val m = module.methodList().firstOrNull { it.shardId == shardId } ?: return
        val typed = editor.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_RATE }
        if (column == Column.KILLS) {
            if (typed != m.killsPerHour) module.setRates(shardId, typed, m.shardsPerKill)
        } else if (typed != null && typed > 0.0 && typed != m.shardsPerKill) {
            module.setRates(shardId, m.killsPerHour, typed)
        }
        setFocused(search)
    }

    // ---- input ----------------------------------------------------------------------------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (adding) {
            if (event.button() == 0) {
                if (addSearch.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick)
                targets.firstOrNull { it.first.contains(event.x(), event.y()) }?.let { it.second(); return true }
            }
            return true
        }
        if (event.button() == 1) {
            rowHits.firstOrNull { it.first.contains(event.x(), event.y()) }?.let { (_, id) ->
                if (editing != null) commitEdit()
                if (id.startsWith(HuntingMethods.GROUP_PREFIX)) module.ungroup(id.removePrefix(HuntingMethods.GROUP_PREFIX))
                else module.removeMethod(id)
                click()
                return true
            }
        }
        if (event.button() == 0) {
            if (editing != null && editor.visible && editor.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick)
            for ((rect, action) in targets) {
                if (rect.contains(event.x(), event.y())) {
                    if (editing != null) commitEdit()
                    action()
                    return true
                }
            }
            if (editing != null) commitEdit()
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (adding) {
            addScroll = max(0, addScroll - scrollY.toInt())
            return true
        }
        if (list(panel()).contains(mouseX, mouseY)) {
            if (editing != null) commitEdit()
            scroll = max(0, scroll - (scrollY * ROW_H).toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (adding && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closePicker()
            return true
        }
        if (editing != null) {
            when (event.key()) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> { commitEdit(); return true }
                GLFW.GLFW_KEY_ESCAPE -> { editing = null; editor.visible = false; setFocused(search); return true }
            }
        } else if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun removed() {
        if (editing != null) commitEdit()
        ModConfig.save()
        super.removed()
    }

    private fun click() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private companion object {
        /** Remembered for the game session. */
        var query = ""
        var scroll = 0
        var sortColumn = Column.PER_HOUR
        var descending = true

        const val ROW_H = 20
        const val MAX_RATE = 1_000_000.0
        val FALLBACK: ItemStack by lazy { ItemStack(Items.PRISMARINE_SHARD) }
    }
}
