package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry
import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.TreeMap

/** Run-local positive map evidence retention behind the existing Java call surface. */
object DungeonRoomOverlaySnapshotRetention {
    @JvmStatic
    fun merge(
        current: DungeonRoomOverlayGeometry.Snapshot?,
        previous: DungeonRoomOverlayGeometry.Snapshot?,
    ): DungeonRoomOverlayGeometry.Snapshot? {
        if (current == null || !current.available()) return previous
        if (previous == null || !previous.available()) return current

        val cellsByTile = TreeMap<Int, DungeonRoomOverlayGeometry.Cell>()
        for (cell in previous.cells()) putCell(cellsByTile, cell)
        for (cell in current.cells()) {
            if (cell == null) continue
            val tile = tile(cell)
            val old = cellsByTile[tile]
            cellsByTile[tile] = if (old == null) cell else mergeCell(cell, old)
        }

        val roomsById = LinkedHashMap<String?, DungeonRoomOverlayGeometry.Room>()
        for (room in current.rooms()) {
            if (room != null) roomsById[room.id()] = room
        }
        for (old in previous.rooms()) {
            if (old == null) continue
            var latest = roomsById[old.id()]
            if (latest == null) latest = roomsById.values.firstOrNull { candidate -> sameFootprint(candidate, old) }
            if (isPartialCoreRoom(old)) {
                if (latest != null) {
                    roomsById[latest.id()] = mergeRoom(latest, old)
                } else {
                    val overlapping = roomsById.values.firstOrNull { candidate -> overlapsFootprint(candidate, old) }
                    if (overlapping != null) {
                        roomsById[overlapping.id()] = mergePartialCoreEvidence(overlapping, old)
                    } else if (footprintRetained(old, cellsByTile)) {
                        roomsById[old.id()] = old
                    }
                }
                continue
            }
            if (latest != null) roomsById[latest.id()] = mergeRoom(latest, old)
            else if (footprintRetained(old, cellsByTile)) roomsById[old.id()] = old
        }

        val cells = ArrayList(cellsByTile.values)
        cells.sortWith(Comparator.comparingInt<DungeonRoomOverlayGeometry.Cell> { it.z() }
            .thenComparingInt { it.x() })
        return DungeonRoomOverlayGeometry.Snapshot(
            true, cells, java.util.List.copyOf(roomsById.values), current.playerCell(),
            current.players(), current.reason(), current.predictions(),
        )
    }

    private fun mergeCell(
        current: DungeonRoomOverlayGeometry.Cell,
        previous: DungeonRoomOverlayGeometry.Cell,
    ): DungeonRoomOverlayGeometry.Cell {
        var visibility = current.visibility()
        if (previous.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED ||
            visibility == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
        ) {
            visibility = DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
        } else if (visibility == DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN) {
            visibility = previous.visibility()
        }
        return DungeonRoomOverlayGeometry.Cell(
            current.x(), current.z(), current.type() ?: previous.type(),
            current.roomId() ?: previous.roomId(), visibility,
        )
    }

    private fun mergeRoom(
        current: DungeonRoomOverlayGeometry.Room,
        previous: DungeonRoomOverlayGeometry.Room,
    ): DungeonRoomOverlayGeometry.Room {
        val compatible = compatible(current.type(), previous.type())
        val currentNamed = current.name() != null && !current.name().equals("Unknown", ignoreCase = true)
        val name = if (currentNamed) current.name()
        else if (compatible && previous.name() != null) previous.name() else "Unknown"
        val type = current.type() ?: previous.type()
        val footprint = if (current.footprintKnown()) current.footprint() else previous.footprint()
        val exits = LinkedHashSet(current.exits())
        exits.addAll(previous.exits())
        var visibility = current.visibility()
        if (visibility != DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED &&
            previous.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
        ) {
            visibility = DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
        }
        val checkmark = if (positiveCheckmark(current.checkmark())) current.checkmark() else previous.checkmark()
        val maxSecrets = if (current.maxSecrets() > 0) current.maxSecrets()
        else if (compatible) previous.maxSecrets() else current.maxSecrets()
        val foundSecrets = maxOf(current.foundSecrets(), if (compatible) previous.foundSecrets() else 0)
        return DungeonRoomOverlayGeometry.Room(
            current.id(), name, type, footprint, java.util.List.copyOf(exits),
            current.exitsKnown() || previous.exitsKnown(),
            current.footprintKnown() || previous.footprintKnown(), visibility,
            maxSecrets, foundSecrets, checkmark,
        )
    }

    private fun mergePartialCoreEvidence(
        current: DungeonRoomOverlayGeometry.Room,
        partial: DungeonRoomOverlayGeometry.Room,
    ): DungeonRoomOverlayGeometry.Room {
        val exits = LinkedHashSet(current.exits())
        exits.addAll(partial.exits())
        val visibility = if (partial.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED)
            DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED else current.visibility()
        return DungeonRoomOverlayGeometry.Room(
            current.id(), current.name(), current.type(), current.footprint(), java.util.List.copyOf(exits),
            current.exitsKnown() || partial.exitsKnown(), current.footprintKnown(), visibility,
            current.maxSecrets(), current.foundSecrets(), current.checkmark(),
        )
    }

    private fun footprintRetained(
        room: DungeonRoomOverlayGeometry.Room,
        cellsByTile: Map<Int, DungeonRoomOverlayGeometry.Cell>,
    ): Boolean {
        if (isPartialCoreRoom(room)) {
            if (room.footprint().size != 1) return false
            val partialCell = room.footprint().first()
            val retainedCell = cellsByTile[tile(partialCell)]
            return retainedCell != null && room.id() == retainedCell.roomId()
        }
        if (!room.footprintKnown() || room.footprint().isEmpty()) return false
        for (footprintCell in room.footprint()) {
            val cell = cellsByTile[tile(footprintCell)]
            if (cell == null || room.id() != cell.roomId()) return false
        }
        return true
    }

    private fun sameFootprint(
        first: DungeonRoomOverlayGeometry.Room?,
        second: DungeonRoomOverlayGeometry.Room?,
    ): Boolean {
        if (first == null || second == null || !first.footprintKnown() || !second.footprintKnown() ||
            first.footprint().isEmpty() || first.footprint().size != second.footprint().size
        ) return false
        val firstTiles = first.footprint().map(::tile).toSet()
        val secondTiles = second.footprint().map(::tile).toSet()
        return firstTiles == secondTiles
    }

    private fun overlapsFootprint(
        first: DungeonRoomOverlayGeometry.Room?,
        second: DungeonRoomOverlayGeometry.Room?,
    ): Boolean {
        if (first == null || second == null || first.footprint().isEmpty() || second.footprint().isEmpty())
            return false
        val firstTiles = first.footprint().map(::tile).toSet()
        return second.footprint().any { cell -> tile(cell) in firstTiles }
    }

    private fun isPartialCoreRoom(room: DungeonRoomOverlayGeometry.Room?): Boolean =
        room?.id()?.startsWith("partial-core:") == true

    private fun compatible(
        current: DungeonMapGeometry.RoomType?,
        previous: DungeonMapGeometry.RoomType?,
    ): Boolean {
        if (current == null || previous == null || current == DungeonMapGeometry.RoomType.UNKNOWN ||
            previous == DungeonMapGeometry.RoomType.UNKNOWN || current == previous
        ) return true
        if ((current == DungeonMapGeometry.RoomType.ROOM && previous == DungeonMapGeometry.RoomType.RARE) ||
            (current == DungeonMapGeometry.RoomType.RARE && previous == DungeonMapGeometry.RoomType.ROOM)
        ) return true
        return current.mapColor() == previous.mapColor()
    }

    private fun positiveCheckmark(checkmark: String?): Boolean =
        !checkmark.isNullOrBlank() && !checkmark.equals("UNDISCOVERED", ignoreCase = true)

    private fun tile(cell: DungeonRoomOverlayGeometry.Cell): Int = cell.x() + cell.z() * GRID_CELLS

    private fun putCell(
        cells: MutableMap<Int, DungeonRoomOverlayGeometry.Cell>,
        cell: DungeonRoomOverlayGeometry.Cell?,
    ) {
        if (cell != null && cell.x() in 0 until GRID_CELLS && cell.z() in 0 until GRID_CELLS) {
            cells.putIfAbsent(tile(cell), cell)
        }
    }

    private const val GRID_CELLS = 6
}
