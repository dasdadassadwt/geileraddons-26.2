package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonStats
import geiler.addons.client.dungeon.DungeonStatsService
import geiler.addons.client.dungeon.PartyFinderEntries
import geiler.addons.client.dungeon.PartyFinderListing
import geiler.addons.client.gui.ContainerSkin
import geiler.addons.client.gui.ContainerSkins
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.gui.TextFieldInput
import geiler.addons.client.gui.TextFieldModel
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ChestMenu
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Custom Party Finder: replaces the Party Finder chest menu with a scrolling list of parties (leader, floor, note,
 * members with class and level, requirements) plus each member's cached Catacombs stats. Behavior follows Skyblocker's
 * Party Finder screen; every button is a click on the real menu slot. The header names the floor being searched (a
 * click opens Search Settings); scrolling past the last party turns to the next page. The menus around it (Catacombs
 * Gate, Search Settings, floor and class pickers) get the generic themed card view.
 */
class CustomPartyFinderModule private constructor(private val memberStats: BooleanSetting) : Module(
    "Custom Party Finder",
    "Replaces the Party Finder menu with a searchable party list that also shows each member's dungeon stats.",
    Category.F7,
    false,
    memberStats,
), ContainerSkin {
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)
    private val search = TextFieldModel(32)
    private var searchFocused = false
    private var scroll = 0
    /** Drawn scroll position in rows, eased toward [scroll]. */
    private var scrollAnim = 0f
    private var pageNumber = 1
    private var lastPageClickMs = 0L
    private var lastContainer = -1
    private var bypassContainer = -1
    private var cacheKey = Long.MIN_VALUE
    private var page: PartyFinderEntries.Page? = null
    private var kuudra = false
    private var lastClickMs = 0L
    private var hovered: PartyFinderEntries.Party? = null
    private val stats = HashMap<String, DungeonStats?>()
    private val requested = HashSet<String>()
    private var joins = 0
    private var buttons = 0

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
    }

    private val targets = ArrayList<Pair<Rect, () -> Unit>>()

    override fun onEnable() {
        diagnostics.enable("memberStats=${memberStats.value()}")
    }

    override fun onDisable() {
        diagnostics.event("COUNTS joins=$joins buttons=$buttons lookups=${requested.size}")
        diagnostics.disable()
    }

    // ---- ownership --------------------------------------------------------------------------------------------

    override fun owns(screen: AbstractContainerScreen<*>): Boolean {
        if (!isEnabled() || screen.menu !is ChestMenu || screen.menu.containerId == bypassContainer) return false
        if (genericMenu(screen)) return true
        if (!PartyFinderEntries.isFinderTitle(screen.title.string)) return false
        refresh(screen)
        return !kuudra
    }

    private fun genericMenu(screen: AbstractContainerScreen<*>): Boolean {
        val rows = (screen.menu as ChestMenu).rowCount
        val back = screen.menu.slots.take(rows * 9).firstOrNull { SkyblockItems.realName(it.item) == "Go Back" }
            ?.let { SkyblockItems.loreLines(it.item).firstOrNull() }
        return PartyFinderEntries.isDungeonMenu(screen.title.string, back)
    }

    /** Re-reads the menu when its contents changed (the state id moves with every server update). */
    private fun refresh(screen: AbstractContainerScreen<*>) {
        val menu = screen.menu as ChestMenu
        val key = menu.containerId.toLong() * 1_000_003L + menu.stateId
        if (key == cacheKey) return
        cacheKey = key
        if (menu.containerId != lastContainer) {
            lastContainer = menu.containerId
            // A new menu that our own page click did not open is page 1 of a fresh search.
            if (System.currentTimeMillis() - lastPageClickMs > PAGE_MEMORY_MS) pageNumber = 1
            scroll = 0
            scrollAnim = 0f
        }
        val slots = (0 until min(menu.rowCount * 9, menu.slots.size)).map { i ->
            val stack = menu.slots[i].item
            PartyFinderEntries.SlotInfo(
                i,
                if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString(),
                if (stack.isEmpty) "" else SkyblockItems.realName(stack),
                SkyblockItems.loreLines(stack),
            )
        }
        kuudra = slots.any { s -> s.lore.any { it.contains("Kuudra") } && s.itemId != "minecraft:player_head" } ||
            slots.any { s -> s.itemId == "minecraft:player_head" && s.lore.any { it.trim().startsWith("Dungeon:") && it.contains("Kuudra") } }
        val read = PartyFinderEntries.page(slots, menu.rowCount)
        page = read
        diagnostics.event(
            "PAGE parties=${read.parties.size} joinable=${read.parties.count { it.joinable }} empty=${read.empty} kuudra=$kuudra " +
                "buttons=prev:${read.previous},next:${read.next},refresh:${read.refresh},settings:${read.settings},create:${read.create},delist:${read.delist}",
        )
    }

    // ---- drawing ----------------------------------------------------------------------------------------------

    private fun panel(screen: AbstractContainerScreen<*>) = Rect((screen.width - W) / 2, max(8, (screen.height - H) / 2), W, min(H, screen.height - 16))

    override fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        targets.clear()
        if (genericMenu(screen)) {
            graphics.fill(0, 0, screen.width, screen.height, GuiTheme.DIALOG_SHADE)
            val cards = geiler.addons.client.gui.GenericMenuGrid.draw(screen, graphics, screen.title.string, mouseX, mouseY)
            val (px, py, pw) = geiler.addons.client.gui.GenericMenuGrid.panel
            val v = Rect(px + pw - 52, py + 4, 44, 14)
            GuiTheme.roundedRect(graphics, v.x, v.y, v.w, v.h, 3, if (v.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
            graphics.centeredText(font, "Vanilla", v.x + v.w / 2, v.y + 3, GuiTheme.TEXT_PRIMARY)
            targets.add(v to { bypassContainer = screen.menu.containerId; diagnostics.event("VANILLA menu=${screen.title.string.take(30)}") })
            for (c in cards) targets.add(Rect(c.x, c.y, c.w, c.h) to { press(screen, c.slot, "menu:${screen.title.string.take(20)}") })
            return
        }
        val read = page ?: return
        hovered = null
        diagnostics.advance()
        val p = panel(screen)
        graphics.fill(0, 0, screen.width, screen.height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(graphics, p.x, p.y, p.w, p.h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "§lParty Finder", p.x + 8, p.y + 7, GuiTheme.TEXT_PRIMARY, false)
        // Top buttons.
        var bx = p.x + p.w - 8
        fun button(label: String, slot: Int, width: Int, onClick: (() -> Unit)? = null) {
            bx -= width
            val r = Rect(bx, p.y + 4, width, 14)
            bx -= 3
            val active = slot >= 0 || onClick != null
            val hover = active && r.contains(mouseX.toDouble(), mouseY.toDouble())
            GuiTheme.roundedRect(graphics, r.x, r.y, r.w, r.h, 3, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
            graphics.centeredText(font, label, r.x + r.w / 2, r.y + 3, if (active) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_MUTED)
            if (active) targets.add(r to (onClick ?: { press(screen, slot, label) }))
        }
        button("Vanilla", -1, 44) { bypassContainer = screen.menu.containerId; diagnostics.event("VANILLA") }
        button("▶", read.next, 14) { turnPage(screen, read.next, +1) }
        button("◀", read.previous, 14) { turnPage(screen, read.previous, -1) }
        button("⟳", read.refresh, 14)
        if (read.delist >= 0) button("De-list", read.delist, 44) else button("Create", read.create, 44)
        button("Settings", read.settings, 48)
        // The floor being searched, from the Search Settings button; a click opens the settings to change it. It gets
        // the room the buttons leave so the two never overlap.
        val floor = if (read.settings >= 0) PartyFinderEntries.searchedFloor(SkyblockItems.loreLines(screen.menu.slots[read.settings].item)) else ""
        val floorX = p.x + 8 + font.width("§lParty Finder") + 6
        val floorText = GuiTheme.fit(font, "§b" + floor.ifEmpty { "Floor ?" } + " §8· §7Page $pageNumber", max(0, bx - 4 - floorX - 6))
        val floorRect = Rect(floorX, p.y + 5, font.width(floorText) + 6, 12)
        if (floorRect.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.roundedRect(graphics, floorRect.x, floorRect.y, floorRect.w, floorRect.h, 3, GuiTheme.BUTTON_HOVER)
        graphics.text(font, floorText, floorRect.x + 3, floorRect.y + 2, GuiTheme.TEXT_PRIMARY, false)
        if (read.settings >= 0) targets.add(floorRect to { press(screen, read.settings, "floor") })
        // Search box.
        val box = Rect(p.x + 8, p.y + 22, p.w - 16, 14)
        GuiTheme.roundedRect(graphics, box.x, box.y, box.w, box.h, 3, GuiTheme.CARD_BG)
        if (search.text.isEmpty() && !searchFocused) graphics.text(font, "Search leader, member, note, floor...", box.x + 4, box.y + 3, GuiTheme.TEXT_MUTED, false)
        TextFieldInput.draw(graphics, font, search, box.x + 4, box.y + 3, box.w - 8, GuiTheme.TEXT_PRIMARY, 0xAA3366CC.toInt(), searchFocused)
        // Party list.
        val list = ArrayList<PartyFinderEntries.Party>()
        read.yourParty?.let(list::add)
        read.parties.filterTo(list) { it.slot != read.yourParty?.slot && PartyFinderEntries.matches(it, search.text) }
        val view = Rect(p.x + 8, p.y + 40, p.w - 16, p.h - 46)
        val visible = view.h / ROW
        scroll = scroll.coerceIn(0, max(0, list.size - visible))
        scrollAnim += (scroll - scrollAnim) * SCROLL_EASE
        if (kotlin.math.abs(scroll - scrollAnim) < 0.01f) scrollAnim = scroll.toFloat()
        if (read.empty || list.isEmpty()) {
            graphics.centeredText(font, if (read.empty) "No parties are listed." else "No party matches.", view.x + view.w / 2, view.y + 20, GuiTheme.TEXT_SECONDARY)
        }
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        val first = scrollAnim.toInt()
        for (i in first until min(list.size, first + visible + 1)) {
            val party = list[i]
            val r = Rect(view.x, view.y + ((i - scrollAnim) * ROW).toInt(), view.w, ROW - 3)
            val own = party === read.yourParty
            val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
            if (hover) hovered = party
            val bg = when {
                own -> GuiTheme.CATEGORY_SELECTED
                hover && party.joinable -> GuiTheme.CARD_BG_HOVER
                else -> GuiTheme.CARD_BG
            }
            GuiTheme.roundedRect(graphics, r.x, r.y, r.w, r.h, 4, bg)
            drawParty(graphics, party, r, own)
            if (party.joinable && !own && r.y >= view.y && r.y + r.h <= view.y + view.h) targets.add(r to { press(screen, party.slot, "join"); joins++ })
        }
        graphics.disableScissor()
        // Scroll progress: where the visible rows sit in the page's list.
        if (list.size > visible) {
            val thumbH = max(8, view.h * visible / list.size)
            val ty = view.y + ((view.h - thumbH) * (scrollAnim / max(1, list.size - visible))).toInt()
            graphics.fill(p.x + p.w - 5, view.y, p.x + p.w - 3, view.y + view.h, GuiTheme.CARD_BG)
            graphics.fill(p.x + p.w - 5, ty, p.x + p.w - 3, ty + thumbH, GuiTheme.SLIDER_FILL)
        }
        if (list.size > visible) graphics.text(font, "§8${scroll + 1}-${min(list.size, scroll + visible)} of ${list.size}", p.x + 8, p.y + p.h - 10, GuiTheme.TEXT_MUTED, false)
        hovered?.let { requestStats(it) }
    }

    private fun drawParty(graphics: GuiGraphicsExtractor, party: PartyFinderEntries.Party, r: Rect, own: Boolean) {
        val font = Minecraft.getInstance().font
        val floor = "§b" + (PartyFinderEntries.shortFloor("${party.dungeon} ${party.floor}") ?: "${party.dungeon.removePrefix("The ")} ${party.floor}")
        graphics.text(font, floor, r.x + r.w - 6 - font.width(floor), r.y + 4, GuiTheme.TEXT_PRIMARY, false)
        val title = GuiTheme.fit(font, (if (own) "§aYour party: " else "§e") + party.leader + "'s Party", r.w - 12 - font.width(floor) - 8)
        graphics.text(font, title, r.x + 6, r.y + 4, GuiTheme.TEXT_PRIMARY, false)
        val reason = party.blocked?.let { "§c" + GuiTheme.fit(font, it, r.w / 2) }
        reason?.let { graphics.text(font, it, r.x + r.w - 6 - font.width(it), r.y + 14, GuiTheme.TEXT_PRIMARY, false) }
        val req = buildString {
            if (party.classRequired > 0) append("Class ").append(party.classRequired)
            if (party.dungeonRequired > 0) { if (isNotEmpty()) append(" · "); append("Cata ").append(party.dungeonRequired) }
        }
        val noteRoom = r.w - 12 - (reason?.let { font.width(it) + 8 } ?: 0)
        val note = GuiTheme.fit(font, (if (party.note.isNotEmpty()) "§f" + party.note else "§8no note") + if (req.isNotEmpty()) "  §7Req: $req" else "", noteRoom)
        graphics.text(font, note, r.x + 6, r.y + 14, GuiTheme.TEXT_PRIMARY, false)
        val cell = (r.w - 12) / PartyFinderListing.MAX_MEMBERS
        for (i in 0 until PartyFinderListing.MAX_MEMBERS) {
            val x = r.x + 6 + i * cell
            val y = r.y + 26
            val member = party.members.getOrNull(i)
            if (member == null) {
                val open = party.openClasses().getOrNull(i - party.members.size)
                graphics.text(font, GuiTheme.fit(font, "§8Empty" + (if (open != null && i == party.members.size) " §7(${party.openClasses().joinToString("") { it.displayName().take(1) }})" else ""), cell - 4), x, y, GuiTheme.TEXT_MUTED, false)
                continue
            }
            val cls = member.dungeonClass?.displayName()?.take(1) ?: "?"
            graphics.text(font, GuiTheme.fit(font, member.name, cell - 4), x, y, GuiTheme.TEXT_PRIMARY, false)
            val level = "$cls ${member.level}"
            graphics.text(font, level, x, y + 10, PartyFinderListing.levelColor(member.level), false)
            if (memberStats.value()) {
                val s = stats[member.name.lowercase(Locale.ROOT)]
                val room = cell - 4 - font.width(level) - 4
                if (s != null && room > 0) graphics.text(font, GuiTheme.fit(font, "§7C${s.catacombsLevel()} §8${short(s.totalSecrets())}S", room), x + font.width(level) + 4, y + 10, GuiTheme.TEXT_SECONDARY, false)
            }
        }
    }

    private fun requestStats(party: PartyFinderEntries.Party) {
        if (!memberStats.value()) return
        for (member in party.members) {
            val key = member.name.lowercase(Locale.ROOT)
            if (!requested.add(key) || requested.size > MAX_LOOKUPS) continue
            DungeonStatsService.fetch(member.name) { result ->
                stats[key] = result.stats
                diagnostics.event("STATS ok=${result.available()} cached=${stats.size}")
            }
        }
    }

    private fun press(screen: AbstractContainerScreen<*>, slot: Int, what: String) {
        val now = System.currentTimeMillis()
        if (slot < 0 || now - lastClickMs < CLICK_GUARD_MS) return
        lastClickMs = now
        buttons++
        diagnostics.event("CLICK what=$what slot=$slot")
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        ContainerSkins.clickSlot(screen, slot)
    }

    // ---- input ------------------------------------------------------------------------------------------------

    override fun click(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        val p = panel(screen)
        val box = Rect(p.x + 8, p.y + 22, p.w - 16, 14)
        searchFocused = box.contains(event.x(), event.y())
        if (searchFocused) {
            TextFieldInput.mousePressed(search, Minecraft.getInstance().font, event.x(), event.y(), box.x + 4, box.w - 8, event.hasShiftDown())
            return true
        }
        if (event.button() != 0) return true
        targets.firstOrNull { it.first.contains(event.x(), event.y()) }?.second?.invoke()
        return true
    }

    override fun scroll(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, amount: Double): Boolean {
        if (genericMenu(screen)) return true
        val read = page
        val p = panel(screen)
        val visible = (p.h - 46) / ROW
        val count = (read?.parties?.count { it.slot != read.yourParty?.slot && PartyFinderEntries.matches(it, search.text) } ?: 0) +
            (if (read?.yourParty != null) 1 else 0)
        val maxScroll = max(0, count - visible)
        // Past the last party turns to the next page, above the first to the previous one.
        if (amount < 0 && scroll >= maxScroll && read != null && read.next >= 0) {
            turnPage(screen, read.next, +1)
            return true
        }
        if (amount > 0 && scroll <= 0 && read != null && read.previous >= 0) {
            turnPage(screen, read.previous, -1)
            return true
        }
        scroll = (scroll - amount.toInt()).coerceIn(0, maxScroll)
        return true
    }

    private fun turnPage(screen: AbstractContainerScreen<*>, slot: Int, delta: Int) {
        if (slot < 0 || System.currentTimeMillis() - lastClickMs < CLICK_GUARD_MS) return
        press(screen, slot, if (delta > 0) "next" else "previous")
        lastPageClickMs = System.currentTimeMillis()
        pageNumber = max(1, pageNumber + delta)
        diagnostics.event("PAGE page=$pageNumber")
    }

    override fun key(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        if (searchFocused) {
            if (TextFieldInput.handleKey(search, event, "pf-search") == TextFieldInput.Outcome.CHANGED) scroll = 0
            return true
        }
        // The hidden vanilla slots must not take hotbar, drop or swap keys; the inventory key still closes the menu.
        return !Minecraft.getInstance().options.keyInventory.matches(event)
    }

    override fun type(screen: AbstractContainerScreen<*>, event: CharacterEvent): Boolean {
        if (!searchFocused) return false
        if (TextFieldInput.handleChar(search, event, "pf-search") == TextFieldInput.Outcome.CHANGED) scroll = 0
        return true
    }

    private fun short(value: Long): String = if (value >= 1000) String.format(Locale.ROOT, "%.1fk", value / 1000.0) else value.toString()

    companion object {
        private const val W = 360
        private const val H = 250
        private const val ROW = 50
        private const val CLICK_GUARD_MS = 300L
        private const val PAGE_MEMORY_MS = 3_000L
        private const val SCROLL_EASE = 0.3f
        private const val MAX_LOOKUPS = 64

        @JvmField
        val INSTANCE = CustomPartyFinderModule(BooleanSetting("Member Stats", true))
    }
}
