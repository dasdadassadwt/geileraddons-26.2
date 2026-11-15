package geiler.addons.client.waypoint

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * The "Waypoint Library" tree of the Waypoints settings page: island > group > waypoint, each with a switch on
 * the right, built the way the Macro Library is. The Click GUI edits small shadow settings; [sync] copies them
 * back to the saved model, so the model stays the single source of truth.
 */
object WaypointLibrary {
    const val ANY_LABEL = "Any island"
    private const val NAME_LENGTH = 48
    private const val MAX_GROUPS = 500
    private const val MAX_WAYPOINTS_PER_GROUP = 2000
    private const val PREFIX = "§6[GeilerAddons] "

    /** Receives one short line per library edit; the module points it at its diagnostics. */
    @JvmStatic
    var trace: (String) -> Unit = {}

    private class GroupControls(g: WaypointGroup) {
        val enabled = BooleanSetting("Group Enabled", g.enabled)
        val name = TextSetting("Group Name", "Name", g.name, NAME_LENGTH)
        val showText = BooleanSetting("Show Text", g.showText)
        val ordered = BooleanSetting("Ordered Route", "Ordered", g.ordered)
        val throughWalls = BooleanSetting("Through Walls", g.throughWalls)
        val type = ChoiceSetting(
            "Type", g.type.takeIf { id -> WaypointType.entries.any { it.id == id } } ?: WaypointType.WAYPOINT.id,
            WaypointType.entries.associate { it.id to it.label },
            *WaypointType.entries.map { it.id }.toTypedArray(),
        )
        val island = ChoiceSetting("Island", islandLabel(g.island), *islandChoices(g.island).toTypedArray())
    }

    private class EntryControls(w: WaypointEntry) {
        val enabled = BooleanSetting("Waypoint Enabled", w.enabled)
        val name = TextSetting("Waypoint Name", "Name", w.name, NAME_LENGTH)
        val position = TextSetting("Waypoint Position", "Position (x, y, z)", "${w.x}, ${w.y}, ${w.z}", 40)
        val color = ColorSetting(
            "Waypoint Color", "Color",
            (w.rgb shr 16) and 0xFF, (w.rgb shr 8) and 0xFF, w.rgb and 0xFF, (w.alpha * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    private val groupControls = IdentityHashMap<WaypointGroup, GroupControls>()
    private val entryControls = IdentityHashMap<WaypointEntry, EntryControls>()
    private val islandToggles = HashMap<String, BooleanSetting>()
    private val ids = IdentityHashMap<Any, Int>()

    // ---------- pure helpers ----------

    /** "x, y, z" with commas or spaces; null when it is not exactly three whole numbers. */
    @JvmStatic
    fun parsePosition(text: String?): IntArray? {
        if (text == null) return null
        val parts = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val out = IntArray(3)
        for (i in 0..2) out[i] = parts[i].toIntOrNull() ?: return null
        return out
    }

    /** Island ids in library order: Any island, the island the player is on, then every other island once. */
    @JvmStatic
    fun islandKeys(current: Island): List<String> {
        val keys = LinkedHashSet<String>()
        keys.add("")
        current.mode()?.let { keys.add(it) }
        for (island in Island.entries) island.mode()?.let { keys.add(it) }
        return keys.toList()
    }

    @JvmStatic
    fun islandLabel(key: String): String {
        if (key.isEmpty()) return ANY_LABEL
        val island = Island.fromMode(key)
        return if (island == Island.OTHER) key else island.label()
    }

    private fun islandChoices(extraKey: String): List<String> {
        val labels = LinkedHashSet<String>()
        labels.add(ANY_LABEL)
        for (island in Island.entries) if (island.selectable()) labels.add(island.label())
        labels.add(islandLabel(extraKey))
        return labels.toList()
    }

    private fun modeForLabel(label: String, fallback: String): String {
        if (label == ANY_LABEL) return ""
        for (island in Island.entries) if (island.selectable() && island.label() == label) return island.mode().orEmpty()
        return fallback
    }

    private fun idOf(obj: Any): Int = ids.getOrPut(obj) { ids.size + 1 }

    private fun say(message: String) = PlayerAlerts.chat(PREFIX + message)

    // ---------- model <-> settings ----------

    private fun controls(g: WaypointGroup) = groupControls.getOrPut(g) { GroupControls(g) }

    private fun controls(w: WaypointEntry) = entryControls.getOrPut(w) { EntryControls(w) }

    private fun islandToggle(key: String) = islandToggles.getOrPut(key) { BooleanSetting("Island Enabled", WaypointStore.isIslandEnabled(key)) }

    /** Copies edited shadow settings into the saved model and saves once if anything changed. Cheap while nothing is open. */
    @JvmStatic
    fun sync() {
        var changed = false
        for ((g, c) in groupControls) {
            if (g.enabled != c.enabled.value()) { g.enabled = c.enabled.value(); changed = true }
            val name = c.name.value().trim()
            if (name.isNotEmpty() && name != g.name) { g.name = name; changed = true }
            if (g.showText != c.showText.value()) { g.showText = c.showText.value(); changed = true }
            if (g.ordered != c.ordered.value()) { g.ordered = c.ordered.value(); g.currentIndex = 0; changed = true }
            if (g.throughWalls != c.throughWalls.value()) { g.throughWalls = c.throughWalls.value(); changed = true }
            if (g.type != c.type.value()) { g.type = c.type.value(); changed = true }
            val mode = modeForLabel(c.island.value(), g.island)
            if (mode != g.island) { g.island = mode; changed = true }
        }
        for ((w, c) in entryControls) {
            if (w.enabled != c.enabled.value()) { w.enabled = c.enabled.value(); changed = true }
            val name = c.name.value().trim()
            if (name != w.name) { w.name = name; changed = true }
            val pos = parsePosition(c.position.value())
            if (pos != null && (pos[0] != w.x || pos[1] != w.y || pos[2] != w.z)) {
                w.x = pos[0]; w.y = pos[1]; w.z = pos[2]; changed = true
            }
            val rgb = (c.color.red() shl 16) or (c.color.green() shl 8) or c.color.blue()
            if (rgb != w.rgb) { w.rgb = rgb; changed = true }
            if (c.color.alpha() != (w.alpha * 255f).roundToInt().coerceIn(0, 255)) { w.alpha = c.color.alpha() / 255f; changed = true }
        }
        for ((key, toggle) in islandToggles) {
            if (WaypointStore.isIslandEnabled(key) != toggle.value()) {
                WaypointStore.setIslandEnabled(key, toggle.value())
                trace("ISLAND island=${key.ifEmpty { "any" }} enabled=${toggle.value()}")
            }
        }
        if (changed) {
            WaypointStore.save()
            trace("EDIT saved groups=${WaypointStore.all().size}")
        }
    }

    // ---------- tree ----------

    /** The whole library, rebuilt on every settings layout; shadow settings live on between rebuilds. */
    @JvmStatic
    fun build(): SettingGroup {
        sync()
        val all = WaypointStore.all()
        val liveEntries = java.util.Collections.newSetFromMap(IdentityHashMap<WaypointEntry, Boolean>())
        all.forEach { g -> liveEntries.addAll(g.waypoints) }
        val liveGroups = java.util.Collections.newSetFromMap(IdentityHashMap<WaypointGroup, Boolean>())
        liveGroups.addAll(all)
        groupControls.keys.retainAll(liveGroups)
        entryControls.keys.retainAll(liveEntries)

        val current = HypixelModApi.currentIsland()
        val keys = LinkedHashSet(islandKeys(current))
        all.forEach { keys.add(it.island) }
        val byIsland = all.groupBy { it.island }
        val islands = keys.map { islandGroup(it, byIsland[it].orEmpty(), current) }
        val importAction = ModuleAction(
            "Import From Clipboard", "Adds the groups of a Skyblocker waypoint share string as new groups.",
            Runnable { importClipboard() },
        )
        val exportAction = ModuleAction(
            "Export All Groups", "Copies every group to the clipboard as a Skyblocker share string.",
            Runnable { export(WaypointStore.all().toList()) },
        )
        return SettingGroup("Waypoint Library", null, false, listOf(importAction, exportAction), islands, false, "wp-library")
    }

    private fun islandGroup(key: String, groups: List<WaypointGroup>, current: Island): SettingGroup {
        val here = key.isNotEmpty() && key == current.mode()
        val title = buildString {
            append(islandLabel(key))
            if (here) append(" • here")
            if (groups.isNotEmpty()) append(" • ").append(groups.size)
        }
        val newGroup = ModuleAction(
            "New Group", "Creates an empty waypoint group on ${islandLabel(key)}.",
            Runnable { newGroup(key) },
        )
        return SettingGroup(title, islandToggle(key), true, listOf(newGroup), groups.map(::groupTree), false, "wp-island:$key")
    }

    private fun groupTree(g: WaypointGroup): SettingGroup {
        val c = controls(g)
        val id = idOf(g)
        val export = ModuleAction("Export Group", "Copies this group to the clipboard as a Skyblocker share string.", Runnable { export(listOf(g)) })
        val delete = ModuleAction("Delete Group", "Removes this group and all of its waypoints.", Runnable { deleteGroup(g) })
        val options = SettingGroup.folded("Options", c.name, c.showText, c.ordered, c.throughWalls, c.type, c.island, export, delete)
            .keyed("wp-opts:$id")
        val addHere = ModuleAction("Add Waypoint At My Position", "Adds a waypoint at your feet to this group.", Runnable { addHere(g) })
        val children = ArrayList<SettingGroup>(g.waypoints.size + 1)
        children.add(options)
        for (w in g.waypoints) children.add(entryTree(g, w))
        return SettingGroup("${g.name} • ${g.waypoints.size}", c.enabled, true, listOf(addHere), children, false, "wp-group:$id")
    }

    private fun entryTree(g: WaypointGroup, w: WaypointEntry): SettingGroup {
        val c = controls(w)
        val delete = ModuleAction("Delete Waypoint", "Removes this waypoint from its group.", Runnable { deleteEntry(g, w) })
        val title = "${w.name.ifBlank { "Waypoint" }} • ${w.x}, ${w.y}, ${w.z}"
        return SettingGroup(title, c.enabled, true, listOf(c.name, c.position, c.color, delete), emptyList(), false, "wp-entry:${idOf(w)}")
    }

    // ---------- actions ----------

    private fun newGroup(island: String) {
        sync()
        val all = WaypointStore.all()
        if (all.size >= MAX_GROUPS) return say("Group limit of $MAX_GROUPS reached.")
        val taken = all.filter { it.island == island }.size
        WaypointStore.add(WaypointGroup(name = "Group ${taken + 1}", island = island))
        trace("NEW_GROUP island=${island.ifEmpty { "any" }} total=${all.size}")
    }

    private fun addHere(g: WaypointGroup) {
        sync()
        val player = Minecraft.getInstance().player ?: return say("Join a world first.")
        if (g.waypoints.size >= MAX_WAYPOINTS_PER_GROUP) return say("Waypoint limit of $MAX_WAYPOINTS_PER_GROUP reached.")
        val pos = player.blockPosition()
        g.waypoints.add(WaypointEntry("Waypoint ${g.waypoints.size + 1}", pos.x, pos.y, pos.z))
        WaypointStore.save()
        trace("ADD group=${g.name.take(24)} x=${pos.x} y=${pos.y} z=${pos.z} count=${g.waypoints.size}")
    }

    private fun deleteGroup(g: WaypointGroup) {
        sync()
        WaypointStore.remove(g)
        trace("DELETE_GROUP name=${g.name.take(24)} waypoints=${g.waypoints.size}")
    }

    private fun deleteEntry(g: WaypointGroup, w: WaypointEntry) {
        sync()
        g.waypoints.remove(w)
        WaypointStore.save()
        trace("DELETE_WAYPOINT group=${g.name.take(24)} count=${g.waypoints.size}")
    }

    private fun importClipboard() {
        sync()
        val result = WaypointShare.decode(Minecraft.getInstance().keyboardHandler.clipboard)
        if (result.error != null) return say("§c${result.error}")
        val room = MAX_GROUPS - WaypointStore.all().size
        result.groups.take(room.coerceAtLeast(0)).forEach { WaypointStore.add(it) }
        trace("IMPORT groups=${result.groups.size} waypoints=${result.groups.sumOf { it.waypoints.size }}")
        say("Imported ${result.groups.size} group(s) with ${result.groups.sumOf { it.waypoints.size }} waypoint(s).")
    }

    private fun export(groups: List<WaypointGroup>) {
        sync()
        if (groups.isEmpty()) return say("Nothing to export.")
        Minecraft.getInstance().keyboardHandler.clipboard = WaypointShare.encode(groups)
        trace("EXPORT groups=${groups.size}")
        say("Copied ${groups.size} group(s) to the clipboard (Skyblocker format).")
    }
}
