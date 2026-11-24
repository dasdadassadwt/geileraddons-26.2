package geiler.addons.client.collection

import geiler.addons.client.collections.FolderTree
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.location.Island
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** Saves trackers, their folders and their all-time totals to `config/geileraddons/collection-trackers.json`. */
object CollectionTrackerStore {
    class SavedTracker {
        @JvmField var id: Int = 0
        @JvmField var name: String = "Tracker"
        @JvmField var folderId: String? = null
        @JvmField var enabled: Boolean = true
        @JvmField var items: MutableList<String> = ArrayList()
        @JvmField var islandFilter: Boolean = false
        @JvmField var islands: MutableList<String> = ArrayList()
        @JvmField var priceMode: String = CollectionTracker.PriceMode.INSTANT_SELL.label
        @JvmField var showTotals: Boolean = false
        @JvmField var showRates: Boolean = true
        @JvmField var showProfit: Boolean = true
        @JvmField var showItemValues: Boolean = false
        @JvmField var idleSeconds: Float = 120f
        @JvmField var background: Boolean = true
        @JvmField var totalCounts: MutableMap<String, Long> = LinkedHashMap()
        @JvmField var totalActiveMs: Long = 0L
    }

    class Saved {
        @JvmField var folders: MutableList<FolderTree.Folder> = ArrayList()
        @JvmField var trackers: MutableList<SavedTracker> = ArrayList()
    }

    @JvmStatic
    fun toSaved(trackers: List<CollectionTracker>, folders: FolderTree): Saved = Saved().also { saved ->
        saved.folders.addAll(folders.folders())
        for (tracker in trackers) saved.trackers.add(SavedTracker().apply {
            id = tracker.id
            name = tracker.name.value()
            folderId = tracker.folderId
            enabled = tracker.enabled.value()
            items = ArrayList(tracker.items)
            islandFilter = tracker.islandFilter.value()
            islands = tracker.selectedIslands().map { it.name }.toMutableList()
            priceMode = tracker.priceMode.value()
            showTotals = tracker.showTotals.value()
            showRates = tracker.showRates.value()
            showProfit = tracker.showProfit.value()
            showItemValues = tracker.showItemValues.value()
            idleSeconds = tracker.idleSeconds.value()
            background = tracker.background.value()
            totalCounts = LinkedHashMap(tracker.stats.totals())
            totalActiveMs = tracker.stats.totalActiveMs
        })
    }

    @JvmStatic
    fun fromSaved(saved: SavedTracker): CollectionTracker = CollectionTracker(saved.id, saved.name).also { t ->
        t.folderId = saved.folderId
        t.enabled.setValue(saved.enabled)
        saved.items?.forEach { if (it.isNotBlank() && it !in t.items && t.items.size < MAX_ITEMS) t.items.add(it) }
        t.islandFilter.setValue(saved.islandFilter)
        saved.islands?.forEach { name -> Island.values().firstOrNull { it.name == name }?.let { t.islands[it]?.setValue(true) } }
        t.priceMode.setValue(saved.priceMode)
        t.showTotals.setValue(saved.showTotals)
        t.showRates.setValue(saved.showRates)
        t.showProfit.setValue(saved.showProfit)
        t.showItemValues.setValue(saved.showItemValues)
        t.idleSeconds.setValue(saved.idleSeconds)
        t.background.setValue(saved.background)
        t.stats.restoreTotal(saved.totalCounts, saved.totalActiveMs)
    }

    @JvmStatic
    fun load(): Saved? = path()?.let { ClientJsonFile.read(it, Saved::class.java) }

    @JvmStatic
    fun json(saved: Saved): String = ClientJsonFile.GSON.toJson(saved)

    @JvmStatic
    fun write(json: String) {
        path()?.let { ClientJsonFile.writeAsync(it, json) }
    }

    private fun path(): Path? = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")?.resolve("collection-trackers.json")
    } catch (_: NullPointerException) {
        null
    }

    const val MAX_ITEMS = 64
    const val MAX_TRACKERS = 64
}
