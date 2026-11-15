package geiler.addons.client.waypoint

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.location.Island
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The saved waypoint groups and which islands are switched off. Client-thread owned; saves are written
 * off-thread through [ClientJsonFile].
 *
 * The file is `{"version":2,"disabledIslands":[...],"groups":[...]}`. A bare array (the first format) still loads.
 * An island is its Hypixel mode id; the empty id is the "Any island" bucket.
 */
object WaypointStore {
    const val MAX_DISABLED_ISLANDS = 64
    private const val MAX_ISLAND_ID_LENGTH = 64

    /** Parsed file content. */
    class Data(val groups: List<WaypointGroup>, val disabledIslands: Set<String>)

    private class FileV2 {
        var version: Int = 2
        var disabledIslands: List<String?>? = ArrayList()
        var groups: List<WaypointGroup?>? = ArrayList()
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<ArrayList<WaypointGroup>>() {}.type
    private val groups = ArrayList<WaypointGroup>()
    private val disabledIslands = LinkedHashSet<String>()
    private var loaded = false

    /** One line about the last load, for the module diagnostics. */
    @JvmStatic
    var loadSummary: String = "not loaded"
        private set

    private fun path(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("waypoints.json")

    @JvmStatic
    @JvmOverloads
    fun toJson(list: List<WaypointGroup>, disabled: Collection<String> = emptyList()): String {
        val file = FileV2()
        file.disabledIslands = disabled.toList()
        file.groups = list
        return gson.toJson(file)
    }

    /** Broken, empty or oversized content loads as no groups rather than throwing. */
    @JvmStatic
    fun fromJson(json: String?): List<WaypointGroup> = parse(json).groups

    @JvmStatic
    fun parse(json: String?): Data {
        if (json.isNullOrBlank() || json.length > ClientJsonFile.MAX_FILE_BYTES) return Data(emptyList(), emptySet())
        return try {
            val root = JsonParser.parseString(json)
            if (root.isJsonArray) {
                val parsed: List<WaypointGroup?>? = gson.fromJson(json, listType)
                Data(clean(parsed), emptySet())
            } else if (root.isJsonObject) {
                val file = gson.fromJson(json, FileV2::class.java)
                val islands = LinkedHashSet<String>()
                for (id in file?.disabledIslands.orEmpty()) {
                    if (id != null && id.length <= MAX_ISLAND_ID_LENGTH && islands.size < MAX_DISABLED_ISLANDS) islands.add(id)
                }
                Data(clean(file?.groups), islands)
            } else {
                Data(emptyList(), emptySet())
            }
        } catch (_: RuntimeException) {
            Data(emptyList(), emptySet())
        }
    }

    private fun clean(parsed: List<WaypointGroup?>?): List<WaypointGroup> =
        parsed.orEmpty().filterNotNull().onEach { group ->
            @Suppress("SENSELESS_COMPARISON")
            if (group.waypoints == null) group.waypoints = ArrayList()
            group.waypoints = ArrayList(group.waypoints.filterNotNull())
        }

    /** Groups of [list] that draw on [island], skipping switched-off groups and islands. */
    @JvmStatic
    fun applicable(list: List<WaypointGroup>, disabled: Set<String>, island: Island): List<WaypointGroup> =
        list.filter { it.enabled && !disabled.contains(it.island) && it.appliesTo(island) }

    @JvmStatic
    fun all(): List<WaypointGroup> {
        ensureLoaded()
        return groups
    }

    /** Groups that apply to [island], in saved order. */
    @JvmStatic
    fun groupsFor(island: Island): List<WaypointGroup> = applicable(all(), disabledIslands, island)

    @JvmStatic
    fun isIslandEnabled(mode: String): Boolean {
        ensureLoaded()
        return !disabledIslands.contains(mode)
    }

    /** Returns true when the state changed. */
    @JvmStatic
    fun setIslandEnabled(mode: String, enabled: Boolean): Boolean {
        ensureLoaded()
        val changed = if (enabled) disabledIslands.remove(mode) else
            disabledIslands.size < MAX_DISABLED_ISLANDS && disabledIslands.add(mode)
        if (changed) save()
        return changed
    }

    @JvmStatic
    fun add(group: WaypointGroup) {
        ensureLoaded()
        groups.add(group)
        save()
    }

    @JvmStatic
    fun remove(group: WaypointGroup) {
        ensureLoaded()
        groups.remove(group)
        save()
    }

    @JvmStatic
    fun replaceAll(list: List<WaypointGroup>) {
        groups.clear()
        groups.addAll(list)
        loaded = true
        save()
    }

    /** Sets the label switch of every group once; used by the migration of the old global Show Names setting. */
    @JvmStatic
    fun setAllShowText(show: Boolean) {
        ensureLoaded()
        groups.forEach { it.showText = show }
        save()
    }

    @JvmStatic
    fun save() {
        ClientJsonFile.writeAsync(path(), toJson(groups, disabledIslands))
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        groups.clear()
        disabledIslands.clear()
        val file = path()
        if (!Files.isRegularFile(file)) {
            loadSummary = "no file"
            return
        }
        try {
            if (Files.size(file) > ClientJsonFile.MAX_FILE_BYTES) {
                loadSummary = "file too large, ignored"
                return
            }
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            val data = parse(text)
            groups.addAll(data.groups)
            disabledIslands.addAll(data.disabledIslands)
            loadSummary = "${if (text.trimStart().startsWith("[")) "legacy" else "v2"} groups=${groups.size} disabledIslands=${disabledIslands.size}"
        } catch (_: java.io.IOException) {
            // An unreadable file starts empty; the next save rewrites it.
            loadSummary = "unreadable, started empty"
        }
    }
}
