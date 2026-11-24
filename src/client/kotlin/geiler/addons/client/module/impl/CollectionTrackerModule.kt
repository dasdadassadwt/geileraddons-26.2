package geiler.addons.client.module.impl

import geiler.addons.client.collection.CollectionPricing
import geiler.addons.client.collection.CollectionTracker
import geiler.addons.client.collection.CollectionTrackerStore
import geiler.addons.client.collection.InventoryGains
import geiler.addons.client.collection.SackChangeParser
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.gui.FolderManagerScreen
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.gui.ItemPickerScreen
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.pricing.SkyblockItemIds
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.util.Collections
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Supplier

/**
 * Multi-instance collection tracker. Each tracker counts its items from two sources, as SkyHanni's `ItemAddManager`
 * does: the per-item rows in the hover text of Hypixel's 30 s sack summary, and items appearing in the player's own
 * inventory while no container is open. Rates divide by active time; profit uses the downloaded Bazaar / lowest BIN
 * data and is always an estimate.
 */
class CollectionTrackerModule private constructor(private val s: Settings) : Module(
    "Collection Tracker",
    "Counts chosen items from sacks and your inventory: totals, items per hour, session time and estimated profit, per tracker.",
    Category.MISCELLANEOUS,
    false,
    s.create, s.folders, s.resetSessions,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private val trackers = ArrayList<CollectionTracker>()
    private val folders = FolderTree()
    private val huds = HashMap<Int, TrackerHud>()
    private val gains = InventoryGains()
    private var loaded = false
    private var nextId = 1
    private var lastSavedJson: String? = null
    private var tickCount = 0L
    private var lastSackScreenMs = 0L
    private var nameIndex: Map<String, String> = emptyMap()
    private var nameIndexSource: Any? = null

    // Diagnostics counters, reset per status line.
    private var sackMessages = 0
    private var sackIgnored = 0
    private var sackUnresolved = 0
    private var sackTruncated = 0
    private var inventoryGains = 0

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = CollectionTrackerStore.load() ?: return
        folders.restore(saved.folders)
        for (entry in saved.trackers.take(CollectionTrackerStore.MAX_TRACKERS)) {
            if (trackers.any { it.id == entry.id }) continue
            add(CollectionTrackerStore.fromSaved(entry))
        }
        nextId = (trackers.maxOfOrNull { it.id } ?: 0) + 1
        lastSavedJson = CollectionTrackerStore.json(CollectionTrackerStore.toSaved(trackers, folders))
    }

    private fun add(tracker: CollectionTracker) {
        trackers.add(tracker)
        val hud = huds.getOrPut(tracker.id) { TrackerHud(tracker.id) }
        HudManager.register(hud, 0.02f, 0.3f)
    }

    private fun create() {
        ensureLoaded()
        if (trackers.size >= CollectionTrackerStore.MAX_TRACKERS) return
        add(CollectionTracker(nextId++, "Tracker ${trackers.size + 1}"))
        saveNow()
    }

    private fun delete(tracker: CollectionTracker) {
        trackers.remove(tracker)
        saveNow()
    }

    fun trackers(): List<CollectionTracker> {
        ensureLoaded()
        return Collections.unmodifiableList(trackers)
    }

    override fun groups(): List<SettingGroup> {
        ensureLoaded()
        val groups = ArrayList<SettingGroup>()
        groups.add(SettingGroup(null, s.create, s.folders, s.resetSessions))
        val library = ArrayList<SettingGroup>()
        for (folder in folders.childrenOf(null)) library.add(folderGroup(folder))
        for (tracker in trackers) if (!folders.contains(tracker.folderId)) library.add(trackerGroup(tracker))
        groups.add(SettingGroup("Trackers", null, false, emptyList(), library, false, "collection-trackers"))
        return groups
    }

    private fun folderGroup(folder: FolderTree.Folder): SettingGroup {
        val children = ArrayList<SettingGroup>()
        for (child in folders.childrenOf(folder.id)) children.add(folderGroup(child))
        for (tracker in trackers) if (tracker.folderId == folder.id) children.add(trackerGroup(tracker))
        return SettingGroup(folder.name, null, false, emptyList(), children, false, "collection-folder:${folder.id}")
    }

    private fun trackerGroup(t: CollectionTracker): SettingGroup {
        val items = ModuleAction("Add Items (${t.items.size})", "Search the item list and tick the items this tracker counts.",
            Runnable { openPicker(t) })
        val resetSession = ModuleAction("Reset Session", "Clear this tracker's session counts and time.", Runnable { t.stats.resetSession() })
        val resetTotal = ModuleAction("Reset Totals", "Clear this tracker's all-time counts and time.", Runnable {
            t.stats.resetTotal()
            saveNow()
        })
        val delete = ModuleAction("Delete Tracker", "Remove this tracker and its totals.", Runnable { delete(t) })
        val group = SettingGroup("${t.name.value()} (${t.items.size} items)", t.enabled, t.name, items, t.showTotals, t.showRates,
            t.showProfit, t.showItemValues, t.priceMode, t.idleSeconds, t.background, resetSession, resetTotal, delete)
        val islands = SettingGroup("Islands", t.islandFilter, true, t.islands.values.toList<Setting>(), emptyList())
            .keyed("collection-islands:${t.id}")
        return group.containing(islands).keyed("collection-tracker:${t.id}")
    }

    override fun settingTooltip(setting: Setting): String? = when (setting.name()) {
        "Price Mode" -> "Instant Sell: top Bazaar buy order. Sell Offer: lowest Bazaar sell order. Lowest BIN: cheapest auction. " +
            "Bazaar, else BIN: instant sell, or lowest BIN for items not on the Bazaar. All values are estimates."
        "Show Totals" -> "Off shows this session (since the game started or the last reset); on shows all-time totals."
        "Island Filter" -> "Only count and show on the ticked islands. Off: every island."
        "Idle Hide" -> "The HUD hides, and time stops counting, this long after the last tracked item arrived."
        else -> null
    }

    private fun openPicker(t: CollectionTracker) {
        val mc = Minecraft.getInstance()
        mc.gui.setScreen(ItemPickerScreen(mc.gui.screen(), "Items for ${t.name.value()}", t.items,
            CollectionTrackerStore.MAX_ITEMS) { picked ->
            t.items.clear()
            t.items.addAll(picked)
            diagnostics.event("ITEMS_SET tracker=${t.id} count=${picked.size}")
            saveNow()
        })
    }

    private fun openFolders() {
        ensureLoaded()
        val mc = Minecraft.getInstance()
        mc.gui.setScreen(FolderManagerScreen(mc.gui.screen(), "Collection Trackers", folders,
            Supplier { trackers.map { FolderManagerScreen.Entry(it.id.toString(), it.name.value(), it.folderId) } },
            BiConsumer { entryId, folderId -> trackers.firstOrNull { it.id.toString() == entryId }?.folderId = folderId },
            Runnable { saveNow() }))
    }

    private fun resetAllSessions() {
        trackers.forEach { it.stats.resetSession() }
    }

    override fun onEnable() {
        ensureLoaded()
        gains.suppress(System.currentTimeMillis())
        diagnostics.enable("trackers=${trackers.size} enabled=${trackers.count { it.enabled.value() }}")
    }

    override fun onDisable() {
        PriceDisplayModule.INSTANCE.setCollectionDemand(false)
        saveIfChanged()
        diagnostics.disable()
    }

    fun onWorldChanged() {
        gains.suppress(System.currentTimeMillis())
    }

    fun tick() {
        if (!isEnabled()) return
        ensureLoaded()
        diagnostics.advance()
        tickCount++
        val mc = Minecraft.getInstance()
        val now = System.currentTimeMillis()
        val island = HypixelModApi.currentIsland()
        val wanted = trackers.any { it.enabled.value() && it.items.isNotEmpty() && it.allowedOn(island) }
        if (wanted && tickCount % INVENTORY_POLL_TICKS == 0L) scanInventory(mc, now)
        for (tracker in trackers) tracker.stats.tick(tracker.active(island, now), now)
        PriceDisplayModule.INSTANCE.setCollectionDemand(trackers.any { it.enabled.value() && it.showProfit.value() })
        if (tickCount % SAVE_CHECK_TICKS == 0L) saveIfChanged()
        diagnostics.statusOnChange(200L) {
            "island=$island trackers=${trackers.size} active=${trackers.count { it.active(island, now) }} " +
                "sackMessages=$sackMessages sackIgnored=$sackIgnored sackUnresolved=$sackUnresolved sackTruncated=$sackTruncated " +
                "inventoryGains=$inventoryGains itemList=${ItemListRepository.compiled != null}"
        }
    }

    private fun scanInventory(mc: Minecraft, now: Long) {
        val player = mc.player ?: return
        val screen = mc.gui.screen()
        if (screen is AbstractContainerScreen<*>) {
            if (ChatText.plain(screen.title.string).contains("Sack")) lastSackScreenMs = now
            gains.suppress(now)
            return
        }
        if (screen != null && screen !is ChatScreen) {
            gains.suppress(now)
            return
        }
        val tracked = HashSet<String>()
        trackers.forEach { if (it.enabled.value()) tracked.addAll(it.items) }
        val counts = HashMap<String, Long>()
        val inventory = player.inventory
        for (slot in 0 until PLAYER_SLOTS) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: continue
            val id = SkyblockItemIds.neuName(tag)
            if (id in tracked) counts.merge(id, stack.count.toLong(), Long::plus)
        }
        for ((id, amount) in gains.observe(counts, now)) {
            inventoryGains++
            dispatch(id, amount, now)
        }
    }

    /** Raw inbound game chat; only the sack summary matters, and its rows live in the hover text. */
    fun onChat(message: Component) {
        if (!isEnabled()) return
        val plain = ChatText.plain(message.string).trim()
        if (!SackChangeParser.isSackMessage(plain)) return
        ensureLoaded()
        sackMessages++
        val now = System.currentTimeMillis()
        // Moving items into a sack by hand also reports "+N"; SkyHanni ignores sack changes in and 10 s after a sack menu.
        if (now - lastSackScreenMs < SACK_MENU_GRACE_MS) {
            sackIgnored++
            return
        }
        val result = SackChangeParser.parseHover(hoverText(message))
        if (result.truncated) sackTruncated++
        for (change in result.changes) {
            if (change.delta <= 0) continue
            val id = itemId(change.itemName)
            if (id == null) {
                sackUnresolved++
                continue
            }
            dispatch(id, change.delta.toLong(), now)
        }
    }

    private fun hoverText(message: Component): String = message.toFlatList().mapNotNull {
        (it.style.hoverEvent as? HoverEvent.ShowText)?.value()?.string
    }.joinToString("\n") { ChatText.plain(it) }

    /** Item id for a display name, from the loaded item list (exact name, case-insensitive). */
    private fun itemId(name: String): String? {
        val compiled = ItemListRepository.compiled
        if (compiled == null) {
            ItemListRepository.request()
            return null
        }
        if (compiled !== nameIndexSource) {
            val index = HashMap<String, String>()
            for (entry in compiled.entries) index.putIfAbsent(entry.plainName.lowercase(Locale.ROOT), entry.id)
            nameIndex = index
            nameIndexSource = compiled
        }
        return nameIndex[name.lowercase(Locale.ROOT)]
    }

    private fun dispatch(id: String, amount: Long, now: Long) {
        val island = HypixelModApi.currentIsland()
        for (tracker in trackers) {
            if (!tracker.enabled.value() || id !in tracker.items || !tracker.allowedOn(island)) continue
            tracker.stats.record(id, amount)
            tracker.lastGainMs = now
        }
    }

    private fun saveIfChanged() {
        if (!loaded) return
        val json = CollectionTrackerStore.json(CollectionTrackerStore.toSaved(trackers, folders))
        if (json == lastSavedJson) return
        lastSavedJson = json
        CollectionTrackerStore.write(json)
    }

    private fun saveNow() = saveIfChanged()

    /** Called at game exit so the last totals reach disk. */
    fun persistNow() = saveIfChanged()

    /** One tracker's HUD; hidden unless the tracker is active (allowed island, recent tracked gain). */
    private inner class TrackerHud(private val trackerId: Int) : HudElement {
        private var cacheMs = 0L
        private var cache: List<Row> = emptyList()

        private fun tracker(): CollectionTracker? = trackers.firstOrNull { it.id == trackerId }

        override fun id(): String = "collection_tracker_$trackerId"
        override fun displayName(): String = "Collection: ${tracker()?.name?.value() ?: trackerId}"
        override fun module(): Module = this@CollectionTrackerModule
        override fun editorListed(): Boolean = tracker() != null
        override fun visible(): Boolean {
            val t = tracker() ?: return false
            return isEnabled() && t.items.isNotEmpty() && t.active(HypixelModApi.currentIsland(), System.currentTimeMillis())
        }

        override fun previewMode(): String = if (visible()) "live" else "sample"
        override fun width(font: Font?): Int = widthOf(font!!, rows())
        override fun height(font: Font?): Int = heightOf(rows())
        override fun previewWidth(font: Font?): Int = widthOf(font!!, if (visible()) rows() else SAMPLE)
        override fun previewHeight(font: Font?): Int = heightOf(if (visible()) rows() else SAMPLE)
        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, rows())
        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
            draw(graphics, font, x, y, if (visible()) rows() else SAMPLE)

        private fun rows(): List<Row> {
            val now = System.currentTimeMillis()
            if (now - cacheMs < 250L) return cache
            cacheMs = now
            cache = tracker()?.let { buildRows(it) } ?: emptyList()
            return cache
        }

        private fun background(): Boolean = tracker()?.background?.value() ?: true

        private fun widthOf(font: Font, rows: List<Row>): Int =
            (rows.maxOfOrNull { font.width(it.text) + if (it.itemId != null) ICON + 2 else 0 } ?: 0) + 8

        private fun heightOf(rows: List<Row>): Int = rows.size * ROW_H + 8

        private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, rows: List<Row>) {
            if (rows.isEmpty()) return
            if (background()) GuiTheme.roundedRect(graphics, x, y, widthOf(font, rows), heightOf(rows), 3, GuiTheme.PANEL_TOP)
            val compiled = ItemListRepository.compiled
            var top = y + 4
            for (row in rows) {
                var left = x + 4
                if (row.itemId != null) {
                    val entry = compiled?.byId?.get(row.itemId)
                    if (entry != null) {
                        graphics.pose().pushMatrix()
                        graphics.pose().translate(left.toFloat(), top.toFloat())
                        graphics.pose().scale(ICON / 16f, ICON / 16f)
                        graphics.item(compiled.stacks.get(entry), 0, 0)
                        graphics.pose().popMatrix()
                    }
                    left += ICON + 2
                }
                graphics.text(font, row.text, left, top + 1, row.argb)
                top += ROW_H
            }
        }
    }

    private class Row(val text: String, val argb: Int, val itemId: String? = null)

    private fun buildRows(t: CollectionTracker): List<Row> {
        val totals = t.showTotals.value()
        val prices = PriceDisplayModule.INSTANCE.prices()
        val mode = t.priceMode()
        val compiled = ItemListRepository.compiled
        val rows = ArrayList<Row>()
        rows.add(Row("${t.name.value()} (${if (totals) "Total" else "Session"})", GuiTheme.TEXT_WARN))
        var earnings = 0.0
        var missing = 0
        for (id in t.items) {
            val count = t.stats.count(id, totals)
            val name = compiled?.byId?.get(id)?.plainName ?: id
            val text = StringBuilder("$name: ${"%,d".format(count)}")
            if (t.showRates.value()) t.stats.perHour(id, totals)?.let { text.append("  ${CollectionPricing.coins(it)}/h") }
            if (t.showProfit.value()) {
                val quote = CollectionPricing.quote(id, mode, prices.bazaar, prices.lowestBin)
                val unit = quote.price
                if (unit == null) {
                    missing++
                    if (t.showItemValues.value()) text.append("  no price")
                } else {
                    earnings += unit * count
                    if (t.showItemValues.value()) text.append("  @${CollectionPricing.coins(unit)} = ${CollectionPricing.coins(unit * count)}")
                }
            }
            rows.add(Row(text.toString(), 0xFFFFFFFF.toInt(), id))
        }
        val activeMs = t.stats.activeMs(totals)
        rows.add(Row("Time: ${CollectionPricing.duration(activeMs)}", GuiTheme.TEXT_SECONDARY))
        if (t.showProfit.value()) {
            val age = when (mode) {
                CollectionTracker.PriceMode.LOWEST_BIN -> PriceDisplayModule.INSTANCE.lowestBinAgeMillis()
                else -> PriceDisplayModule.INSTANCE.bazaarAgeMillis()
            }
            val stale = age == null || age > CollectionPricing.STALE_MS
            val color = if (stale) 0xFFAAAAAA.toInt() else 0xFF55FF55.toInt()
            val suffix = when {
                age == null -> " (no price data yet)"
                stale -> " (stale prices)"
                else -> " (est., ${mode.label})"
            }
            rows.add(Row("Earnings: ${CollectionPricing.coins(earnings)}$suffix", color))
            CollectionStatsRate.coinsPerHour(earnings, activeMs)?.let { rows.add(Row("≈ ${CollectionPricing.coins(it)}/h", color)) }
            if (missing > 0) rows.add(Row("$missing item(s) without a price", 0xFFAAAAAA.toInt()))
        }
        return rows
    }

    private object CollectionStatsRate {
        fun coinsPerHour(coins: Double, activeMs: Long): Double? =
            geiler.addons.client.collection.CollectionStats.perHour(coins, activeMs)
    }

    private class Settings {
        val create = ModuleAction("Create Tracker", "Add a new tracker; open it below to name it and add items.", Runnable { INSTANCE.create() })
        val folders = ModuleAction("Manage Folders", "Organize trackers into nested folders.", Runnable { INSTANCE.openFolders() })
        val resetSessions = ModuleAction("Reset All Sessions", "Clear every tracker's session counts and time.", Runnable { INSTANCE.resetAllSessions() })
    }

    companion object {
        private const val PLAYER_SLOTS = 36
        private const val INVENTORY_POLL_TICKS = 5L
        private const val SAVE_CHECK_TICKS = 100L
        private const val SACK_MENU_GRACE_MS = 10_000L
        private const val ICON = 10
        private const val ROW_H = 11
        private val SAMPLE = listOf(
            Row("Hearthstone Tracker (Session)", GuiTheme.TEXT_WARN),
            Row("Enchanted Hay Bale: 1,284  2.6k/h  @1.3k = 1.67M", 0xFFFFFFFF.toInt()),
            Row("Time: 29m 41s", 0xFFAAAAAA.toInt()),
            Row("Earnings: 1.67M (est., Instant Sell)", 0xFF55FF55.toInt()),
            Row("≈ 3.38M/h", 0xFF55FF55.toInt()),
        )

        @JvmField
        val INSTANCE = CollectionTrackerModule(Settings())
    }
}
