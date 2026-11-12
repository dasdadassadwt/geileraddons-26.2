package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.odin.OdinSpecialColumnPredictor
import java.util.ArrayList

/** Deterministic Water Board screenshot fixture used by the production map renderer. */
object DungeonRoomOverlayPreview {
    private val SAMPLE = createSampleSnapshot()

    @JvmStatic
    fun sampleSnapshot(): DungeonRoomOverlayGeometry.Snapshot = SAMPLE

    private fun createSampleSnapshot(): DungeonRoomOverlayGeometry.Snapshot {
        val observed = DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
        val cells = ArrayList<DungeonRoomOverlayGeometry.Cell>()
        val rooms = ArrayList<DungeonRoomOverlayGeometry.Room>()
        val redBlue = footprint(cells, "red-blue", DungeonMapGeometry.RoomType.ROOM, observed, 0, 0, 0, 1, 0, 2)
        val purpleFlags = footprint(cells, "purple-flags", DungeonMapGeometry.RoomType.ROOM, observed, 1, 0, 2, 0)
        val waterBoard = footprint(cells, "water-board", DungeonMapGeometry.RoomType.PUZZLE, observed, 3, 0)
        val higherBlaze = footprint(cells, "higher-blaze", DungeonMapGeometry.RoomType.ROOM, observed, 4, 0)
        val pedestal = footprint(cells, "pedestal", DungeonMapGeometry.RoomType.ROOM, observed, 5, 0, 5, 1)
        val pipes = footprint(cells, "pipes", DungeonMapGeometry.RoomType.ROOM, observed, 1, 1, 2, 1, 3, 1, 4, 1)
        val newTrap = footprint(cells, "new-trap", DungeonMapGeometry.RoomType.TRAP, observed, 1, 2)
        val threeWeirdos = footprint(cells, "three-weirdos", DungeonMapGeometry.RoomType.PUZZLE, observed, 2, 2)
        val icePath = footprint(cells, "ice-path", DungeonMapGeometry.RoomType.PUZZLE, observed, 3, 2)
        val bangers = footprint(cells, "bangers", DungeonMapGeometry.RoomType.ROOM, observed, 0, 3)
        val prisonCell = footprint(cells, "prison-cell", DungeonMapGeometry.RoomType.ROOM, observed, 1, 3)
        val doubleDiamond = footprint(cells, "double-diamond", DungeonMapGeometry.RoomType.ROOM, observed, 2, 3)
        val dip = footprint(cells, "dip", DungeonMapGeometry.RoomType.ROOM, observed, 3, 3)
        val diagonal = footprint(cells, "diagonal", DungeonMapGeometry.RoomType.ROOM, observed, 4, 3, 4, 4)
        val mossy = footprint(cells, "mossy", DungeonMapGeometry.RoomType.ROOM, observed, 5, 2, 5, 3, 5, 4, 5, 5)
        val jumpingSkulls = footprint(cells, "jumping-skulls", DungeonMapGeometry.RoomType.ROOM, observed, 0, 4)
        val rails = footprint(cells, "rails", DungeonMapGeometry.RoomType.ROOM, observed, 2, 4, 3, 4, 2, 5, 3, 5)
        val blood = footprint(cells, "blood", DungeonMapGeometry.RoomType.BLOOD, observed, 0, 5)
        val kingMidas = footprint(cells, "king-midas", DungeonMapGeometry.RoomType.CHAMPION, observed, 1, 5)
        val entrance = footprint(cells, "entrance", DungeonMapGeometry.RoomType.ENTRANCE, observed, 4, 5)

        rooms.add(room("red-blue", "Red Blue", DungeonMapGeometry.RoomType.ROOM, redBlue,
            listOf(exit(redBlue.first(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.NORMAL)), 4, 0, "NONE", observed))
        rooms.add(room("purple-flags", "Purple Flags", DungeonMapGeometry.RoomType.ROOM, purpleFlags,
            listOf(exit(purpleFlags.first(), DungeonRoomOverlayGeometry.Direction.SOUTH, DungeonRoomOverlayGeometry.DoorKind.GLASS)), 5, 0, "NONE", observed))
        rooms.add(room("water-board", "Water Board", DungeonMapGeometry.RoomType.PUZZLE, waterBoard,
            listOf(exit(waterBoard.first(), DungeonRoomOverlayGeometry.Direction.SOUTH, DungeonRoomOverlayGeometry.DoorKind.WITHER)), -1, -1, "WHITE", observed))
        rooms.add(room("higher-blaze", "Higher Blaze", DungeonMapGeometry.RoomType.ROOM, higherBlaze,
            emptyList(), 1, 0, "NONE", observed))
        rooms.add(room("pedestal", "Pedestal", DungeonMapGeometry.RoomType.ROOM, pedestal,
            listOf(exit(pedestal.last(), DungeonRoomOverlayGeometry.Direction.SOUTH, DungeonRoomOverlayGeometry.DoorKind.NORMAL)), 5, 0, "NONE", observed))
        rooms.add(room("pipes", "Pipes", DungeonMapGeometry.RoomType.ROOM, pipes,
            listOf(exit(pipes.last(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.BLOOD)), 7, 0, "NONE", observed))
        rooms.add(room("new-trap", "New Trap", DungeonMapGeometry.RoomType.TRAP, newTrap,
            listOf(exit(newTrap.first(), DungeonRoomOverlayGeometry.Direction.SOUTH, DungeonRoomOverlayGeometry.DoorKind.START)), 3, 0, "WHITE", observed))
        rooms.add(room("three-weirdos", "Three Weirdos", DungeonMapGeometry.RoomType.PUZZLE, threeWeirdos,
            emptyList(), -1, -1, "WHITE", observed))
        rooms.add(room("ice-path", "Ice Path", DungeonMapGeometry.RoomType.PUZZLE, icePath,
            emptyList(), -1, -1, "WHITE", observed))
        rooms.add(room("bangers", "Bangers", DungeonMapGeometry.RoomType.ROOM, bangers,
            listOf(exit(bangers.first(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.GLASS)), 1, 0, "NONE", observed))
        rooms.add(room("prison-cell", "Prison Cell", DungeonMapGeometry.RoomType.ROOM, prisonCell,
            listOf(exit(prisonCell.first(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.NORMAL)), 1, 0, "NONE", observed))
        rooms.add(room("double-diamond", "Double Diamond", DungeonMapGeometry.RoomType.ROOM, doubleDiamond,
            listOf(exit(doubleDiamond.first(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.FAIRY)), 3, 0, "NONE", observed))
        rooms.add(room("dip", "Dip", DungeonMapGeometry.RoomType.ROOM, dip,
            listOf(exit(dip.first(), DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.UNKNOWN)), 2, 0, "NONE", observed))
        rooms.add(room("diagonal", "Diagonal", DungeonMapGeometry.RoomType.ROOM, diagonal,
            emptyList(), 4, 0, "NONE", observed))
        rooms.add(room("mossy", "Mossy", DungeonMapGeometry.RoomType.ROOM, mossy,
            emptyList(), 4, 0, "NONE", observed))
        rooms.add(room("jumping-skulls", "Jumping Skulls", DungeonMapGeometry.RoomType.ROOM, jumpingSkulls,
            emptyList(), 1, 0, "NONE", observed))
        rooms.add(room("rails", "Rails", DungeonMapGeometry.RoomType.ROOM, rails,
            listOf(exit(rails.first(), DungeonRoomOverlayGeometry.Direction.NORTH, DungeonRoomOverlayGeometry.DoorKind.BLOOD)), 9, 0, "NONE", observed))
        rooms.add(room("blood", "Blood", DungeonMapGeometry.RoomType.BLOOD, blood,
            emptyList(), 0, -1, "RED", observed))
        rooms.add(room("king-midas", "King Midas", DungeonMapGeometry.RoomType.CHAMPION, kingMidas,
            emptyList(), -1, -1, "GREEN", observed))
        rooms.add(room("entrance", "Entrance", DungeonMapGeometry.RoomType.ENTRANCE, entrance,
            listOf(exit(entrance.first(), DungeonRoomOverlayGeometry.Direction.NORTH, DungeonRoomOverlayGeometry.DoorKind.FAIRY)), 0, -1, "GREEN", observed))

        // Keep this connected cell unresolved so the production renderer demonstrates its neutral
        // question-mark hint without assigning a predicted type to the room.
        val predictions = listOf(OdinSpecialColumnPredictor.Prediction(4, 2,
            listOf(DungeonMapGeometry.RoomType.PUZZLE, DungeonMapGeometry.RoomType.TRAP)))
        return DungeonRoomOverlayGeometry.Snapshot(true, cells, rooms,
            DungeonGridRules.Cell(0, 0, DungeonGridRules.GRID_MIN, DungeonGridRules.GRID_MIN),
            listOf(DungeonRoomOverlayGeometry.PlayerMarker(0, 0, "Preview", true, 0.62, 0.26, "Mage", 0.0f)),
            "Water Board map preview sample", predictions)
    }

    private fun footprint(
        allCells: MutableList<DungeonRoomOverlayGeometry.Cell>,
        roomId: String,
        type: DungeonMapGeometry.RoomType,
        visibility: DungeonRoomOverlayGeometry.RoomVisibility,
        vararg coordinates: Int,
    ): List<DungeonRoomOverlayGeometry.Cell> {
        require(coordinates.isNotEmpty() && coordinates.size % 2 == 0) {
            "room footprint must contain coordinate pairs"
        }
        val result = ArrayList<DungeonRoomOverlayGeometry.Cell>(coordinates.size / 2)
        var index = 0
        while (index < coordinates.size) {
            val cell = DungeonRoomOverlayGeometry.Cell(coordinates[index], coordinates[index + 1], type, roomId, visibility)
            allCells.add(cell)
            result.add(cell)
            index += 2
        }
        return java.util.List.copyOf(result)
    }

    private fun exit(
        cell: DungeonRoomOverlayGeometry.Cell,
        direction: DungeonRoomOverlayGeometry.Direction,
        kind: DungeonRoomOverlayGeometry.DoorKind,
    ) = DungeonRoomOverlayGeometry.ObservedExit(cell, direction, kind)

    private fun room(
        id: String,
        name: String,
        type: DungeonMapGeometry.RoomType,
        footprint: List<DungeonRoomOverlayGeometry.Cell>,
        exits: List<DungeonRoomOverlayGeometry.ObservedExit>,
        maxSecrets: Int,
        foundSecrets: Int,
        checkmark: String,
        visibility: DungeonRoomOverlayGeometry.RoomVisibility,
    ) = DungeonRoomOverlayGeometry.Room(id, name, type, footprint, exits,
        exits.isNotEmpty(), true, visibility, maxSecrets, foundSecrets, checkmark)
}
