package geiler.addons.client.dungeon

import java.util.HashSet
import java.util.regex.Pattern

/** Room-relative marker transformation and IDs for the pinned Devonian coordinate resource. */
object SecretWaypointRoomCatalogLogic {
    private val INSTANCE_ID = Pattern.compile(
        "^catalog:room:(Entrance|F[1-7]|M[1-7]):([0-5]),([0-5]):devonian:([a-z0-9-]{1,64}):(chest|item|essence|bat|redstone|lever):([0-9]{1,3})$",
    )
    private val TEMPLATE_ID = Pattern.compile(
        "devonian:[a-z0-9-]{1,64}:(chest|item|essence|bat|redstone|lever):[0-9]{1,3}",
    )
    private val TEMPLATE_PART_SEPARATOR = Pattern.compile(":")
    private const val DEFAULT_COLOR = 0xFF5FFF87.toInt()

    /**
     * Converts only the exact named room's canonical coordinates. Invalid coordinates outside the
     * detected physical footprint are dropped instead of being attached to a neighboring room.
     */
    @JvmStatic
    fun materialize(
        floor: String?,
        roomCells: List<DungeonGridRules.Cell?>?,
        roomName: String?,
        orientation: DungeonRoomOrientationRules.Candidate?,
        templates: List<DevonianDungeonWaypointCatalog.Marker?>?,
    ): List<SecretWaypointStore.Waypoint> {
        val parsedFloor = DungeonFloor.parse(floor)
        if (parsedFloor == null || roomCells == null || roomCells.isEmpty() || roomName == null ||
            orientation == null || templates == null || templates.isEmpty()
        ) {
            return java.util.List.of()
        }

        var anchor: DungeonGridRules.Cell? = null
        for (cell in roomCells) {
            if (cell == null) continue
            val currentAnchor = anchor
            if (currentAnchor == null || cell.componentX < currentAnchor.componentX ||
                (cell.componentX == currentAnchor.componentX &&
                    cell.componentZ < currentAnchor.componentZ)
            ) {
                anchor = cell
            }
        }
        val roomAnchor = anchor ?: return java.util.List.of()

        val physicalCells = HashSet<String>()
        for (cell in roomCells) {
            if (cell != null) physicalCells.add(cellId(cell.componentX, cell.componentZ))
        }

        val result = ArrayList<SecretWaypointStore.Waypoint>()
        for (template in templates) {
            if (template == null || !template.roomName.equals(roomName, ignoreCase = true)) continue
            val position = DungeonRoomOrientationRules.toWorld(
                orientation,
                template.x,
                template.y,
                template.z,
            ) ?: continue
            val targetCell = DungeonGridRules.cellAt(position.x, position.z) ?: continue
            if (!physicalCells.contains(cellId(targetCell.componentX, targetCell.componentZ))) continue
            val markerId = instanceMarkerId(
                parsedFloor.displayName(),
                roomAnchor.componentX,
                roomAnchor.componentZ,
                template.id,
            ) ?: continue
            val roomId = cellId(targetCell.componentX, targetCell.componentZ)
            result.add(
                SecretWaypointStore.Waypoint(
                    markerId,
                    parsedFloor.displayName(),
                    roomId,
                    categoryName(template.category),
                    position.x,
                    position.y,
                    position.z,
                    DEFAULT_COLOR,
                ),
            )
            if (result.size >= ClientJsonFile.MAX_ENTRIES) break
        }
        return java.util.List.copyOf(result)
    }

    @JvmStatic
    fun instanceMarkerId(floor: String?, anchorX: Int, anchorZ: Int, templateId: String?): String? {
        val parsedFloor = DungeonFloor.parse(floor)
        if (parsedFloor == null || anchorX < 0 || anchorX >= 6 || anchorZ < 0 || anchorZ >= 6 ||
            templateId == null || !TEMPLATE_ID.matcher(templateId).matches()
        ) {
            return null
        }
        val id = "catalog:room:${parsedFloor.displayName()}:$anchorX,$anchorZ:$templateId"
        return if (isRoomInstanceMarkerId(id)) id else null
    }

    @JvmStatic
    fun isRoomInstanceMarkerId(id: String?): Boolean = id != null && INSTANCE_ID.matcher(id).matches()

    @JvmStatic
    fun roomInstanceIdentity(id: String?): SecretWaypointRoomCatalog.RoomInstanceIdentity? {
        if (id == null) return null
        val matcher = INSTANCE_ID.matcher(id)
        if (!matcher.matches()) return null
        val floor = DungeonFloor.parse(matcher.group(1)) ?: return null
        return SecretWaypointRoomCatalog.RoomInstanceIdentity(
            floor,
            cellId(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))),
            "devonian:${matcher.group(4)}:${matcher.group(5)}:${matcher.group(6)}",
        )
    }

    /** Category encoded in a bundled marker ID, retained when a local override changes its label. */
    @JvmStatic
    fun markerCategory(id: String?): String? {
        val identity = roomInstanceIdentity(id) ?: return null
        val parts = TEMPLATE_PART_SEPARATOR.split(identity.templateRecordId())
        return if (parts.size == 4) parts[2] else null
    }

    private fun categoryName(category: String?): String {
        val nonNullCategory = java.util.Objects.requireNonNull(category)!!
        return when (nonNullCategory) {
            "chest" -> "Chest"
            "item" -> "Item"
            "essence" -> "Essence"
            "bat" -> "Bat"
            "redstone" -> "Redstone Key"
            "lever" -> "Lever"
            else -> "Secret"
        }
    }

    private fun cellId(x: Int, z: Int): String = "cell:$x,$z"
}
