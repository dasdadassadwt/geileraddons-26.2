package geiler.addons.client.waypoint

import geiler.addons.client.location.Island

/** How a waypoint is drawn; the five kinds and their ids follow Skyblocker's waypoint types. */
enum class WaypointType(
    @JvmField val id: String,
    @JvmField val label: String,
    @JvmField val beam: Boolean,
    @JvmField val fill: Boolean,
    @JvmField val outline: Boolean,
) {
    WAYPOINT("waypoint", "Waypoint", true, true, false),
    OUTLINED_WAYPOINT("outlined_waypoint", "Outlined Waypoint", true, true, true),
    HIGHLIGHT("highlight", "Highlight", false, true, false),
    OUTLINED_HIGHLIGHT("outlined_highlight", "Outlined Highlight", false, true, true),
    OUTLINE("outline", "Outline", false, false, true);

    companion object {
        @JvmStatic
        fun fromId(id: String?): WaypointType = entries.firstOrNull { it.id == id } ?: WAYPOINT
    }
}

/** One saved waypoint. All fields have defaults so Gson can build it without Unsafe. */
class WaypointEntry(
    var name: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
    var rgb: Int = 0x00FF00,
    var alpha: Float = 0.5f,
    var enabled: Boolean = true,
) {
    fun copy(): WaypointEntry = WaypointEntry(name, x, y, z, rgb, alpha, enabled)
}

/**
 * A named list of waypoints for one island. [island] is the Hypixel mode string (the same id Skyblocker's
 * share strings use, for example `mining_3`), or empty for "every island".
 */
class WaypointGroup(
    var name: String = "New Group",
    var island: String = "",
    var ordered: Boolean = false,
    var throughWalls: Boolean = true,
    var type: String = WaypointType.WAYPOINT.id,
    var waypoints: MutableList<WaypointEntry> = ArrayList(),
    var enabled: Boolean = true,
    var showText: Boolean = true,
) {
    /** Index of the last reached waypoint of an ordered route; runtime only. */
    @Transient
    var currentIndex: Int = 0

    fun waypointType(): WaypointType = WaypointType.fromId(type)

    fun appliesTo(current: Island): Boolean = island.isEmpty() || (current.mode() != null && current.mode() == island)

    fun deepCopy(): WaypointGroup {
        val copy = WaypointGroup(name, island, ordered, throughWalls, type, ArrayList(waypoints.map { it.copy() }), enabled, showText)
        copy.currentIndex = currentIndex
        return copy
    }
}
