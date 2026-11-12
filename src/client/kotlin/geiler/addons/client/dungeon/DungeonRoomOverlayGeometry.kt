package geiler.addons.client.dungeon

import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.jvm.JvmRecord

/** Java-callable map geometry and draw-plan API, implemented in Kotlin. */
class DungeonRoomOverlayGeometry private constructor() {
    companion object {
        @JvmStatic
        fun scan(
            colors: ByteArray?,
            calibration: DungeonMapGeometry.Calibration?,
            anchorRoom: DungeonMapGeometry.RoomGeometry?,
            anchorCell: DungeonGridRules.Cell?,
        ): Snapshot = GeometryCore.scan(colors, calibration, anchorRoom, anchorCell)

        @JvmStatic
        fun scan(
            colors: ByteArray?,
            calibration: DungeonMapGeometry.Calibration?,
            anchorRoom: DungeonMapGeometry.RoomGeometry?,
            anchorCell: DungeonGridRules.Cell?,
            observedDoors: List<ObservedDoor?>?,
        ): Snapshot = GeometryCore.scan(colors, calibration, anchorRoom, anchorCell, observedDoors)

        @JvmStatic
        fun scan(
            colors: ByteArray?,
            calibration: DungeonMapGeometry.Calibration?,
            anchorRoom: DungeonMapGeometry.RoomGeometry?,
            anchorCell: DungeonGridRules.Cell?,
            observedDoors: List<ObservedDoor?>?,
            playerMarkers: List<PlayerMarker?>?,
        ): Snapshot = GeometryCore.scan(colors, calibration, anchorRoom, anchorCell, observedDoors, playerMarkers)

        @JvmStatic
        fun renderPlan(snapshot: Snapshot?): RenderPlan = GeometryCore.renderPlan(snapshot)

        @JvmStatic
        fun renderPlan(snapshot: Snapshot?, revealUnopenedRooms: Boolean): RenderPlan =
            GeometryCore.renderPlan(snapshot, revealUnopenedRooms)

        @JvmStatic
        fun hasVerifiedUnopenedMetadata(snapshot: Snapshot?): Boolean =
            GeometryCore.hasVerifiedUnopenedMetadata(snapshot)

        @JvmStatic
        fun visible(visibility: RoomVisibility?, revealUnopenedRooms: Boolean): Boolean =
            GeometryCore.visible(visibility, revealUnopenedRooms)
    }

    class Cell(
        x: Int,
        z: Int,
        type: DungeonMapGeometry.RoomType?,
        roomId: String?,
        visibility: RoomVisibility?,
    ) {
        private val xValue = x
        private val zValue = z
        private val typeValue = type
        private val roomIdValue = roomId
        private val visibilityValue = visibility ?: RoomVisibility.UNKNOWN

        constructor(x: Int, z: Int, type: DungeonMapGeometry.RoomType?, roomId: String?) :
            this(x, z, type, roomId, RoomVisibility.OBSERVED)

        fun x(): Int = xValue
        fun z(): Int = zValue
        fun type(): DungeonMapGeometry.RoomType? = typeValue
        fun roomId(): String? = roomIdValue
        fun visibility(): RoomVisibility = visibilityValue

        override fun equals(other: Any?): Boolean = other is Cell &&
            recordEquals(
                arrayOf<Any?>(xValue, zValue, typeValue, roomIdValue, visibilityValue),
                arrayOf<Any?>(other.xValue, other.zValue, other.typeValue, other.roomIdValue, other.visibilityValue),
            )

        override fun hashCode(): Int = recordHash(xValue, zValue, typeValue, roomIdValue, visibilityValue)

        override fun toString(): String = recordToString(
            "Cell", "x" to xValue, "z" to zValue, "type" to typeValue,
            "roomId" to roomIdValue, "visibility" to visibilityValue,
        )
    }

    class Room(
        id: String?,
        name: String?,
        type: DungeonMapGeometry.RoomType?,
        footprint: List<Cell>?,
        exits: List<ObservedExit>?,
        exitsKnown: Boolean,
        footprintKnown: Boolean,
        visibility: RoomVisibility?,
        maxSecrets: Int,
        foundSecrets: Int,
        checkmark: String?,
    ) {
        private val idValue = id
        private val nameValue = if (name == null || javaStringIsBlank(name)) "Unknown" else name
        private val typeValue = type ?: DungeonMapGeometry.RoomType.UNKNOWN
        private val footprintValue = immutableCopy(footprint)
        private val exitsValue = immutableCopy(exits)
        private val exitsKnownValue = exitsKnown
        private val footprintKnownValue = footprintKnown
        private val visibilityValue = visibility ?: RoomVisibility.UNKNOWN
        private val maxSecretsValue = maxSecrets.coerceAtLeast(0)
        private val foundSecretsValue =
            if (maxSecretsValue <= 0 || foundSecrets < 0) -1 else minOf(maxSecretsValue, foundSecrets)
        private val checkmarkValue = checkmark ?: ""

        constructor(
            id: String?, name: String?, type: DungeonMapGeometry.RoomType?, footprint: List<Cell>?,
            exits: List<ObservedExit>?, exitsKnown: Boolean, footprintKnown: Boolean,
            visibility: RoomVisibility?, maxSecrets: Int, checkmark: String?,
        ) : this(id, name, type, footprint, exits, exitsKnown, footprintKnown, visibility, maxSecrets, -1, checkmark)

        constructor(
            id: String?, name: String?, type: DungeonMapGeometry.RoomType?, footprint: List<Cell>?,
            exits: List<ObservedExit>?, exitsKnown: Boolean, footprintKnown: Boolean,
        ) : this(
            id, name, type, footprint, exits, exitsKnown, footprintKnown,
            RoomVisibility.OBSERVED, 0, -1, "",
        )

        constructor(
            id: String?, name: String?, type: DungeonMapGeometry.RoomType?, footprint: List<Cell>?,
            exits: List<ObservedExit>?, exitsKnown: Boolean, footprintKnown: Boolean, visibility: RoomVisibility?,
        ) : this(id, name, type, footprint, exits, exitsKnown, footprintKnown, visibility, 0, -1, "")

        fun id(): String? = idValue
        fun name(): String = nameValue
        fun type(): DungeonMapGeometry.RoomType = typeValue
        fun footprint(): List<Cell> = footprintValue
        fun exits(): List<ObservedExit> = exitsValue
        fun exitsKnown(): Boolean = exitsKnownValue
        fun footprintKnown(): Boolean = footprintKnownValue
        fun visibility(): RoomVisibility = visibilityValue
        fun maxSecrets(): Int = maxSecretsValue
        fun foundSecrets(): Int = foundSecretsValue
        fun checkmark(): String = checkmarkValue

        fun withFoundSecrets(found: Int): Room = Room(
            idValue, nameValue, typeValue, footprintValue, exitsValue, exitsKnownValue, footprintKnownValue,
            visibilityValue, maxSecretsValue, found, checkmarkValue,
        )

        override fun equals(other: Any?): Boolean = other is Room &&
            recordEquals(
                arrayOf<Any?>(
                    idValue, nameValue, typeValue, footprintValue, exitsValue, exitsKnownValue,
                    footprintKnownValue, visibilityValue, maxSecretsValue, foundSecretsValue, checkmarkValue,
                ),
                arrayOf<Any?>(
                    other.idValue, other.nameValue, other.typeValue, other.footprintValue, other.exitsValue,
                    other.exitsKnownValue, other.footprintKnownValue, other.visibilityValue, other.maxSecretsValue,
                    other.foundSecretsValue, other.checkmarkValue,
                ),
            )

        override fun hashCode(): Int = recordHash(
            idValue, nameValue, typeValue, footprintValue, exitsValue, exitsKnownValue,
            footprintKnownValue, visibilityValue, maxSecretsValue, foundSecretsValue, checkmarkValue,
        )

        override fun toString(): String = recordToString(
            "Room", "id" to idValue, "name" to nameValue, "type" to typeValue,
            "footprint" to footprintValue, "exits" to exitsValue, "exitsKnown" to exitsKnownValue,
            "footprintKnown" to footprintKnownValue, "visibility" to visibilityValue,
            "maxSecrets" to maxSecretsValue, "foundSecrets" to foundSecretsValue, "checkmark" to checkmarkValue,
        )
    }

    class ObservedDoor(firstCell: Int, secondCell: Int, kind: DoorKind?) {
        private val firstCellValue = firstCell
        private val secondCellValue = secondCell
        private val kindValue = kind ?: DoorKind.NORMAL
        constructor(firstCell: Int, secondCell: Int) : this(firstCell, secondCell, DoorKind.NORMAL)
        fun firstCell(): Int = firstCellValue
        fun secondCell(): Int = secondCellValue
        fun kind(): DoorKind = kindValue
        override fun equals(other: Any?): Boolean = other is ObservedDoor &&
            recordEquals(arrayOf<Any?>(firstCellValue, secondCellValue, kindValue),
                arrayOf<Any?>(other.firstCellValue, other.secondCellValue, other.kindValue))
        override fun hashCode(): Int = recordHash(firstCellValue, secondCellValue, kindValue)
        override fun toString(): String =
            recordToString("ObservedDoor", "firstCell" to firstCellValue, "secondCell" to secondCellValue, "kind" to kindValue)
    }

    class ObservedExit(cell: Cell?, direction: Direction?, kind: DoorKind?) {
        private val cellValue = cell
        private val directionValue = direction
        private val kindValue = kind ?: DoorKind.NORMAL
        constructor(cell: Cell?, direction: Direction?) : this(cell, direction, DoorKind.NORMAL)
        fun cell(): Cell? = cellValue
        fun direction(): Direction? = directionValue
        fun kind(): DoorKind = kindValue
        override fun equals(other: Any?): Boolean = other is ObservedExit &&
            recordEquals(arrayOf<Any?>(cellValue, directionValue, kindValue),
                arrayOf<Any?>(other.cellValue, other.directionValue, other.kindValue))
        override fun hashCode(): Int = recordHash(cellValue, directionValue, kindValue)
        override fun toString(): String =
            recordToString("ObservedExit", "cell" to cellValue, "direction" to directionValue, "kind" to kindValue)
    }

    class PlayerMarker(
        x: Int, z: Int, name: String?, localPlayer: Boolean,
        offsetX: Double, offsetZ: Double, dungeonClass: String?, yawDegrees: Float,
    ) {
        private val xValue = x
        private val zValue = z
        private val nameValue = if (name == null || javaStringIsBlank(name)) "Player" else javaStringStrip(name)
        private val localPlayerValue = localPlayer
        private val offsetXValue = if (offsetX.isFinite()) offsetX.coerceIn(0.0, 1.0) else 0.5
        private val offsetZValue = if (offsetZ.isFinite()) offsetZ.coerceIn(0.0, 1.0) else 0.5
        private val dungeonClassValue = if (dungeonClass == null) "" else javaStringStrip(dungeonClass)
        private val yawDegreesValue = if (yawDegrees.isFinite()) yawDegrees else 0.0f
        constructor(x: Int, z: Int, name: String?, localPlayer: Boolean) :
            this(x, z, name, localPlayer, 0.5, 0.5, "", 0.0f)
        constructor(
            x: Int, z: Int, name: String?, localPlayer: Boolean, offsetX: Double, offsetZ: Double, dungeonClass: String?,
        ) : this(x, z, name, localPlayer, offsetX, offsetZ, dungeonClass, 0.0f)
        fun x(): Int = xValue
        fun z(): Int = zValue
        fun name(): String = nameValue
        fun localPlayer(): Boolean = localPlayerValue
        fun offsetX(): Double = offsetXValue
        fun offsetZ(): Double = offsetZValue
        fun dungeonClass(): String = dungeonClassValue
        fun yawDegrees(): Float = yawDegreesValue
        override fun equals(other: Any?): Boolean = other is PlayerMarker &&
            recordEquals(
                arrayOf<Any?>(xValue, zValue, nameValue, localPlayerValue, offsetXValue, offsetZValue, dungeonClassValue, yawDegreesValue),
                arrayOf<Any?>(other.xValue, other.zValue, other.nameValue, other.localPlayerValue, other.offsetXValue,
                    other.offsetZValue, other.dungeonClassValue, other.yawDegreesValue),
            )
        override fun hashCode(): Int = recordHash(
            xValue, zValue, nameValue, localPlayerValue, offsetXValue, offsetZValue, dungeonClassValue, yawDegreesValue,
        )
        override fun toString(): String = recordToString(
            "PlayerMarker", "x" to xValue, "z" to zValue, "name" to nameValue, "localPlayer" to localPlayerValue,
            "offsetX" to offsetXValue, "offsetZ" to offsetZValue, "dungeonClass" to dungeonClassValue,
            "yawDegrees" to yawDegreesValue,
        )
    }

    class Snapshot(
        available: Boolean, cells: List<Cell>?, rooms: List<Room>?, playerCell: DungeonGridRules.Cell?,
        players: List<PlayerMarker?>?, reason: String?,
        predictions: List<geiler.addons.client.dungeon.odin.OdinSpecialColumnPredictor.Prediction>?,
    ) {
        private val availableValue = available
        private val cellsValue = immutableCopy(cells)
        private val roomsValue = immutableCopy(rooms)
        private val playerCellValue = playerCell
        private val playersValue = immutableCopy(players)
        private val reasonValue = reason
        private val predictionsValue = immutableCopy(predictions)
        constructor(available: Boolean, cells: List<Cell>?, rooms: List<Room>?,
            playerCell: DungeonGridRules.Cell?, reason: String?) :
            this(available, cells, rooms, playerCell, emptyList(), reason, emptyList())
        constructor(available: Boolean, cells: List<Cell>?, rooms: List<Room>?,
            playerCell: DungeonGridRules.Cell?, players: List<PlayerMarker?>?, reason: String?) :
            this(available, cells, rooms, playerCell, players, reason, emptyList())
        fun available(): Boolean = availableValue
        fun cells(): List<Cell> = cellsValue
        fun rooms(): List<Room> = roomsValue
        fun playerCell(): DungeonGridRules.Cell? = playerCellValue
        fun players(): List<PlayerMarker> = playersValue
        fun reason(): String? = reasonValue
        fun predictions(): List<geiler.addons.client.dungeon.odin.OdinSpecialColumnPredictor.Prediction> = predictionsValue
        override fun equals(other: Any?): Boolean = other is Snapshot &&
            recordEquals(
                arrayOf<Any?>(availableValue, cellsValue, roomsValue, playerCellValue, playersValue, reasonValue, predictionsValue),
                arrayOf<Any?>(other.availableValue, other.cellsValue, other.roomsValue, other.playerCellValue,
                    other.playersValue, other.reasonValue, other.predictionsValue),
            )
        override fun hashCode(): Int = recordHash(
            availableValue, cellsValue, roomsValue, playerCellValue, playersValue, reasonValue, predictionsValue,
        )
        override fun toString(): String = recordToString(
            "Snapshot", "available" to availableValue, "cells" to cellsValue, "rooms" to roomsValue,
            "playerCell" to playerCellValue, "players" to playersValue, "reason" to reasonValue,
            "predictions" to predictionsValue,
        )
        companion object {
            @JvmStatic
            fun unavailable(reason: String?): Snapshot =
                Snapshot(false, emptyList(), emptyList(), null, emptyList(), reason, emptyList())
        }
    }

    @JvmRecord
    data class CellEdge(val x: Int, val z: Int, val edge: Edge) {
        override fun toString(): String = recordToString("CellEdge", "x" to x, "z" to z, "edge" to edge)
    }

    class ExitMarker(cell: Cell?, direction: Direction?, kind: DoorKind?) {
        private val cellValue = cell
        private val directionValue = direction
        private val kindValue = kind ?: DoorKind.NORMAL
        constructor(cell: Cell?, direction: Direction?) : this(cell, direction, DoorKind.NORMAL)
        fun cell(): Cell? = cellValue
        fun direction(): Direction? = directionValue
        fun kind(): DoorKind = kindValue
        override fun equals(other: Any?): Boolean = other is ExitMarker &&
            recordEquals(arrayOf<Any?>(cellValue, directionValue, kindValue),
                arrayOf<Any?>(other.cellValue, other.directionValue, other.kindValue))
        override fun hashCode(): Int = recordHash(cellValue, directionValue, kindValue)
        override fun toString(): String =
            recordToString("ExitMarker", "cell" to cellValue, "direction" to directionValue, "kind" to kindValue)
    }

    @JvmRecord
    data class DoorHint(val x: Int, val z: Int) {
        override fun toString(): String = recordToString("DoorHint", "x" to x, "z" to z)
    }

    class RenderPlan(
        visibleCells: List<Cell>?, footprintEdges: List<CellEdge>?, exits: List<ExitMarker>?,
        unopenedDoorHints: List<DoorHint>?, doorHintCandidates: Int, doorHintsAlreadyVisible: Int,
        doorHintsOutsideGrid: Int, doorHintsSuppressedByReveal: Int,
    ) {
        private val visibleCellsValue = immutableCopy(visibleCells)
        private val footprintEdgesValue = immutableCopy(footprintEdges)
        private val exitsValue = immutableCopy(exits)
        private val unopenedDoorHintsValue = immutableCopy(unopenedDoorHints)
        private val doorHintCandidatesValue = doorHintCandidates.coerceIn(0, 144)
        private val doorHintsAlreadyVisibleValue = doorHintsAlreadyVisible.coerceIn(0, doorHintCandidatesValue)
        private val doorHintsOutsideGridValue = doorHintsOutsideGrid.coerceIn(0, doorHintCandidatesValue)
        private val doorHintsSuppressedByRevealValue = doorHintsSuppressedByReveal.coerceIn(0, doorHintCandidatesValue)
        constructor(visibleCells: List<Cell>?, footprintEdges: List<CellEdge>?, exits: List<ExitMarker>?) :
            this(visibleCells, footprintEdges, exits, emptyList(), 0, 0, 0, 0)
        fun visibleCells(): List<Cell> = visibleCellsValue
        fun footprintEdges(): List<CellEdge> = footprintEdgesValue
        fun exits(): List<ExitMarker> = exitsValue
        fun unopenedDoorHints(): List<DoorHint> = unopenedDoorHintsValue
        fun doorHintCandidates(): Int = doorHintCandidatesValue
        fun doorHintsAlreadyVisible(): Int = doorHintsAlreadyVisibleValue
        fun doorHintsOutsideGrid(): Int = doorHintsOutsideGridValue
        fun doorHintsSuppressedByReveal(): Int = doorHintsSuppressedByRevealValue
        override fun equals(other: Any?): Boolean = other is RenderPlan &&
            recordEquals(
                arrayOf<Any?>(visibleCellsValue, footprintEdgesValue, exitsValue, unopenedDoorHintsValue,
                    doorHintCandidatesValue, doorHintsAlreadyVisibleValue, doorHintsOutsideGridValue,
                    doorHintsSuppressedByRevealValue),
                arrayOf<Any?>(other.visibleCellsValue, other.footprintEdgesValue, other.exitsValue,
                    other.unopenedDoorHintsValue, other.doorHintCandidatesValue, other.doorHintsAlreadyVisibleValue,
                    other.doorHintsOutsideGridValue, other.doorHintsSuppressedByRevealValue),
            )
        override fun hashCode(): Int = recordHash(
            visibleCellsValue, footprintEdgesValue, exitsValue, unopenedDoorHintsValue,
            doorHintCandidatesValue, doorHintsAlreadyVisibleValue, doorHintsOutsideGridValue,
            doorHintsSuppressedByRevealValue,
        )
        override fun toString(): String = recordToString(
            "RenderPlan", "visibleCells" to visibleCellsValue, "footprintEdges" to footprintEdgesValue,
            "exits" to exitsValue, "unopenedDoorHints" to unopenedDoorHintsValue,
            "doorHintCandidates" to doorHintCandidatesValue, "doorHintsAlreadyVisible" to doorHintsAlreadyVisibleValue,
            "doorHintsOutsideGrid" to doorHintsOutsideGridValue,
            "doorHintsSuppressedByReveal" to doorHintsSuppressedByRevealValue,
        )
    }

    enum class Direction(private val dxValue: Int, private val dzValue: Int) {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        fun dx(): Int = dxValue
        fun dz(): Int = dzValue
    }
    enum class Edge { NORTH, EAST, SOUTH, WEST }
    enum class DoorKind { NORMAL, GLASS, START, WITHER, BLOOD, FAIRY, UNKNOWN }
    enum class RoomVisibility { OBSERVED, VERIFIED_UNOPENED, UNKNOWN }
}

private fun <T : Any> immutableCopy(values: List<T?>?): List<T> {
    @Suppress("UNCHECKED_CAST")
    return java.util.List.copyOf(values ?: emptyList<T?>()) as List<T>
}
private fun recordEquals(first: Array<out Any?>, second: Array<out Any?>): Boolean = first.contentEquals(second)
private fun recordHash(vararg values: Any?): Int =
    values.fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
private fun recordToString(type: String, vararg components: Pair<String, Any?>): String =
    components.joinToString(prefix = "$type[", postfix = "]") { (name, value) -> "$name=$value" }
private fun javaStringIsBlank(value: String): Boolean =
    value.codePoints().allMatch { codePoint -> Character.isWhitespace(codePoint) }
private fun javaStringStrip(value: String): String {
    var start = 0
    var end = value.length
    while (start < end) {
        val codePoint = value.codePointAt(start)
        if (!Character.isWhitespace(codePoint)) break
        start += Character.charCount(codePoint)
    }
    while (start < end) {
        val codePoint = value.codePointBefore(end)
        if (!Character.isWhitespace(codePoint)) break
        end -= Character.charCount(codePoint)
    }
    return value.substring(start, end)
}

private object GeometryCore {
    private const val GRID_SIZE = 6

    fun scan(
        colors: ByteArray?,
        calibration: DungeonMapGeometry.Calibration?,
        anchorRoom: DungeonMapGeometry.RoomGeometry?,
        anchorCell: DungeonGridRules.Cell?,
    ): DungeonRoomOverlayGeometry.Snapshot = scan(colors, calibration, anchorRoom, anchorCell, emptyList(), emptyList())

    fun scan(
        colors: ByteArray?,
        calibration: DungeonMapGeometry.Calibration?,
        anchorRoom: DungeonMapGeometry.RoomGeometry?,
        anchorCell: DungeonGridRules.Cell?,
        observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor?>?,
    ): DungeonRoomOverlayGeometry.Snapshot =
        scan(colors, calibration, anchorRoom, anchorCell, observedDoors, emptyList())

    /** Includes named player markers that were already present in the loaded client level. */
    fun scan(
        colors: ByteArray?,
        calibration: DungeonMapGeometry.Calibration?,
        anchorRoom: DungeonMapGeometry.RoomGeometry?,
        anchorCell: DungeonGridRules.Cell?,
        observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor?>?,
        playerMarkers: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
    ): DungeonRoomOverlayGeometry.Snapshot {
        if (!valid(colors) || calibration == null || !calibration.calibrated || anchorRoom == null ||
            !anchorRoom.calibrated() || anchorRoom.currentMapCell() == null || anchorCell == null
        ) return DungeonRoomOverlayGeometry.Snapshot.unavailable("map geometry is not calibrated")

        val mapColors = colors!!
        val stride = calibration.roomPixelSize + 4
        val anchorMapCell = anchorRoom.currentMapCell()
            ?: return DungeonRoomOverlayGeometry.Snapshot.unavailable("anchor map cell is not resolved")
        val rooms = LinkedHashMap<String, DungeonRoomOverlayGeometry.Room>()
        val cells = ArrayList<DungeonRoomOverlayGeometry.Cell>(GRID_SIZE * GRID_SIZE)
        for (z in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val dx = x - anchorCell.componentX
                val dz = z - anchorCell.componentZ
                val mapCell = DungeonMapGeometry.MapPoint(anchorMapCell.x + dx * stride, anchorMapCell.z + dz * stride)
                val type = DungeonMapGeometry.RoomType.fromColor(colorAt(mapColors, mapCell.x, mapCell.z))
                if (type == DungeonMapGeometry.RoomType.UNKNOWN) continue

                val footprint = if (type == DungeonMapGeometry.RoomType.ROOM) {
                    footprint(mapColors, mapCell, stride, calibration.roomPixelSize, x, z)
                } else {
                    Footprint(listOf(DungeonRoomOverlayGeometry.Cell(x, z, type, null)), true)
                }
                val roomCells = footprint.cells.map { cell ->
                    DungeonRoomOverlayGeometry.Cell(cell.x(), cell.z(), type, null, DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED)
                }
                val id = if (footprint.known) key(roomCells) else "ambiguous:$x,$z"
                if (!rooms.containsKey(id)) {
                    rooms[id] = DungeonRoomOverlayGeometry.Room(
                        id, "Unknown", type, roomCells, emptyList(), false, footprint.known,
                        DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED,
                    )
                }
                cells.add(DungeonRoomOverlayGeometry.Cell(x, z, type, id, DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED))
            }
        }

        val doors = observedDoors ?: emptyList()
        val roomsWithExits = rooms.values.map { room -> withObservedExits(room, doors) }
        return DungeonRoomOverlayGeometry.Snapshot(
            true,
            cells,
            roomsWithExits,
            anchorCell,
            playerMarkers ?: emptyList(),
            "Room names and complete exits are not resolved by this overlay",
        )
    }

    fun renderPlan(snapshot: DungeonRoomOverlayGeometry.Snapshot?): DungeonRoomOverlayGeometry.RenderPlan =
        renderPlan(snapshot, false)

    /** Reveal-off follows opened-room visibility; reveal-on adds verified unopened cells. */
    fun renderPlan(
        snapshot: DungeonRoomOverlayGeometry.Snapshot?,
        revealUnopenedRooms: Boolean,
    ): DungeonRoomOverlayGeometry.RenderPlan {
        if (snapshot == null || !snapshot.available()) {
            return DungeonRoomOverlayGeometry.RenderPlan(emptyList(), emptyList(), emptyList())
        }
        val currentId = currentRoomId(snapshot)
        val visibleCells = snapshot.cells().filter { cell ->
            currentId != null && currentId == cell.roomId() || visible(cell.visibility(), revealUnopenedRooms)
        }
        val visiblePositions = LinkedHashSet<GridPosition>()
        for (cell in visibleCells) visiblePositions.add(position(cell))
        val edges = LinkedHashSet<DungeonRoomOverlayGeometry.CellEdge>()
        val exits = LinkedHashSet<DungeonRoomOverlayGeometry.ExitMarker>()
        for (room in snapshot.rooms()) {
            if (room.footprint().none { visiblePositions.contains(position(it)) }) continue
            if (room.footprint().isEmpty()) continue
            val footprint = LinkedHashSet<GridPosition>()
            for (cell in room.footprint()) footprint.add(GridPosition(cell.x(), cell.z()))
            // Door evidence is rendered from a visible occupied endpoint only.
            for (exit in room.exits()) {
                val exitCell = exit.cell()
                if (exitCell != null && footprint.contains(position(exitCell)) &&
                    visiblePositions.contains(position(exitCell))
                ) exits.add(DungeonRoomOverlayGeometry.ExitMarker(exitCell, exit.direction(), exit.kind()))
            }
            // Exact partial-core cells may keep attached doors, but do not imply a full footprint.
            if (!room.footprintKnown() || isPartialCoreRoom(room)) continue
            for (cell in room.footprint()) {
                if (!visiblePositions.contains(position(cell))) continue
                for (direction in DungeonRoomOverlayGeometry.Direction.values()) {
                    val dx = deltaX(direction)
                    val dz = deltaZ(direction)
                    if (!footprint.contains(GridPosition(cell.x() + dx, cell.z() + dz))) {
                        edges.add(DungeonRoomOverlayGeometry.CellEdge(cell.x(), cell.z(), edgeFor(direction)))
                    }
                }
            }
        }
        val hints = projectUnopenedDoorHints(exits.toList(), visiblePositions, revealUnopenedRooms)
        return DungeonRoomOverlayGeometry.RenderPlan(
            visibleCells, edges.toList(), exits.toList(), hints.hints, hints.candidates,
            hints.alreadyVisible, hints.outsideGrid, hints.suppressedByReveal,
        )
    }

    fun hasVerifiedUnopenedMetadata(snapshot: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        snapshot != null && snapshot.available() &&
            (snapshot.cells().any { it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED } ||
                snapshot.rooms().any { it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED })

    /** Classifies observed cells and optionally verified unopened cells. */
    fun visible(
        visibility: DungeonRoomOverlayGeometry.RoomVisibility?,
        revealUnopenedRooms: Boolean,
    ): Boolean = visibility == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED ||
        (revealUnopenedRooms && visibility == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED)

    private fun projectUnopenedDoorHints(
        exits: List<DungeonRoomOverlayGeometry.ExitMarker?>,
        visiblePositions: Set<GridPosition>,
        revealUnopenedRooms: Boolean,
    ): DoorHintProjection {
        val hints = LinkedHashSet<DungeonRoomOverlayGeometry.DoorHint>()
        var candidates = 0
        var alreadyVisible = 0
        var outsideGrid = 0
        var suppressedByReveal = 0
        for (exit in exits) {
            if (exit == null) continue
            val cell = exit.cell() ?: continue
            val direction = exit.direction() ?: continue
            candidates++
            if (revealUnopenedRooms) {
                suppressedByReveal++
                continue
            }
            val targetX = cell.x() + deltaX(direction)
            val targetZ = cell.z() + deltaZ(direction)
            if (!insideGrid(targetX, targetZ)) {
                outsideGrid++
                continue
            }
            if (visiblePositions.contains(GridPosition(targetX, targetZ))) {
                alreadyVisible++
                continue
            }
            hints.add(DungeonRoomOverlayGeometry.DoorHint(targetX, targetZ))
        }
        return DoorHintProjection(hints.toList(), candidates, alreadyVisible, outsideGrid, suppressedByReveal)
    }

    private fun isPartialCoreRoom(room: DungeonRoomOverlayGeometry.Room?): Boolean {
        val id = room?.id()
        return id != null && id.startsWith("partial-core:")
    }

    private fun currentRoomId(snapshot: DungeonRoomOverlayGeometry.Snapshot): String? {
        val playerCell = snapshot.playerCell() ?: return null
        return snapshot.cells().firstOrNull { cell ->
            cell.x() == playerCell.componentX && cell.z() == playerCell.componentZ &&
                cell.visibility() != DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN &&
                !cell.roomId().isNullOrBlank()
        }?.roomId()
    }

    private fun withObservedExits(
        room: DungeonRoomOverlayGeometry.Room,
        doors: List<DungeonRoomOverlayGeometry.ObservedDoor?>,
    ): DungeonRoomOverlayGeometry.Room {
        if (!room.footprintKnown() || room.footprint().isEmpty()) return room
        val footprint = LinkedHashSet<GridPosition>()
        for (cell in room.footprint()) footprint.add(GridPosition(cell.x(), cell.z()))
        val exits = LinkedHashSet<DungeonRoomOverlayGeometry.ObservedExit>()
        for (door in doors) {
            if (door == null) continue
            val first = fromDoorIndex(door.firstCell()) ?: continue
            val second = fromDoorIndex(door.secondCell()) ?: continue
            if (footprint.contains(position(first)) && !footprint.contains(position(second))) {
                addDirection(exits, first, second, door.kind())
            }
            if (footprint.contains(position(second)) && !footprint.contains(position(first))) {
                addDirection(exits, second, first, door.kind())
            }
        }
        return DungeonRoomOverlayGeometry.Room(
            room.id(), room.name(), room.type(), room.footprint(), exits.toList(), false, true,
            room.visibility(), room.maxSecrets(), room.foundSecrets(), room.checkmark(),
        )
    }

    private fun fromDoorIndex(index: Int): DungeonRoomOverlayGeometry.Cell? =
        if (index < 0 || index >= GRID_SIZE * GRID_SIZE) null
        else DungeonRoomOverlayGeometry.Cell(index % GRID_SIZE, index / GRID_SIZE, null, null)

    private fun addDirection(
        exits: MutableSet<DungeonRoomOverlayGeometry.ObservedExit>,
        from: DungeonRoomOverlayGeometry.Cell,
        to: DungeonRoomOverlayGeometry.Cell,
        kind: DungeonRoomOverlayGeometry.DoorKind?,
    ) {
        val dx = to.x() - from.x()
        val dz = to.z() - from.z()
        if (kotlin.math.abs(dx) + kotlin.math.abs(dz) != 1) return
        val direction = when {
            dx > 0 -> DungeonRoomOverlayGeometry.Direction.EAST
            dx < 0 -> DungeonRoomOverlayGeometry.Direction.WEST
            dz > 0 -> DungeonRoomOverlayGeometry.Direction.SOUTH
            else -> DungeonRoomOverlayGeometry.Direction.NORTH
        }
        exits.add(DungeonRoomOverlayGeometry.ObservedExit(from, direction, kind))
    }

    private fun position(cell: DungeonRoomOverlayGeometry.Cell): GridPosition = GridPosition(cell.x(), cell.z())

    private fun footprint(
        colors: ByteArray,
        start: DungeonMapGeometry.MapPoint,
        stride: Int,
        roomPixelSize: Int,
        physicalX: Int,
        physicalZ: Int,
    ): Footprint {
        val offsets = DungeonMapGeometry.connectedRoomOffsets(
            colors, start, stride, roomPixelSize, DungeonMapGeometry.RoomType.ROOM.mapColor().toByte(),
        )
        if (offsets.isEmpty() || !supportedShape(offsets) || offsets.none { it.x == 0 && it.z == 0 }) {
            return Footprint(emptyList(), false)
        }
        val cells = ArrayList<DungeonRoomOverlayGeometry.Cell>(offsets.size)
        for (offset in offsets) {
            val x = physicalX + offset.x
            val z = physicalZ + offset.z
            if (!insideGrid(x, z)) return Footprint(emptyList(), false)
            cells.add(DungeonRoomOverlayGeometry.Cell(x, z, DungeonMapGeometry.RoomType.ROOM, null))
        }
        if (cells.map { "${it.x()},${it.z()}" }.toSet().size != cells.size) return Footprint(emptyList(), false)
        return Footprint(java.util.List.copyOf(cells), true)
    }

    private fun supportedShape(offsets: List<DungeonMapGeometry.CellOffset>): Boolean =
        DungeonMapGeometry.shape(offsets) in setOf("1x1", "1x2", "1x3", "1x4", "2x2", "L")

    private fun key(cells: List<DungeonRoomOverlayGeometry.Cell>): String = cells
        .sortedWith(compareBy<DungeonRoomOverlayGeometry.Cell> { it.x() }.thenBy { it.z() })
        .joinToString(";") { "${it.x()},${it.z()}" }
        .ifEmpty { "unknown" }

    private fun edgeFor(direction: DungeonRoomOverlayGeometry.Direction): DungeonRoomOverlayGeometry.Edge = when (direction) {
        DungeonRoomOverlayGeometry.Direction.NORTH -> DungeonRoomOverlayGeometry.Edge.NORTH
        DungeonRoomOverlayGeometry.Direction.EAST -> DungeonRoomOverlayGeometry.Edge.EAST
        DungeonRoomOverlayGeometry.Direction.SOUTH -> DungeonRoomOverlayGeometry.Edge.SOUTH
        DungeonRoomOverlayGeometry.Direction.WEST -> DungeonRoomOverlayGeometry.Edge.WEST
    }

    private fun deltaX(direction: DungeonRoomOverlayGeometry.Direction): Int = when (direction) {
        DungeonRoomOverlayGeometry.Direction.NORTH, DungeonRoomOverlayGeometry.Direction.SOUTH -> 0
        DungeonRoomOverlayGeometry.Direction.EAST -> 1
        DungeonRoomOverlayGeometry.Direction.WEST -> -1
    }

    private fun deltaZ(direction: DungeonRoomOverlayGeometry.Direction): Int = when (direction) {
        DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.Direction.WEST -> 0
        DungeonRoomOverlayGeometry.Direction.NORTH -> -1
        DungeonRoomOverlayGeometry.Direction.SOUTH -> 1
    }

    private fun valid(colors: ByteArray?): Boolean =
        colors != null && colors.size >= DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE

    private fun colorAt(colors: ByteArray, x: Int, z: Int): Int =
        if (x < 0 || z < 0 || x >= DungeonMapGeometry.MAP_SIZE || z >= DungeonMapGeometry.MAP_SIZE) -1
        else colors[x + (z shl 7)].toInt() and 0xFF

    private fun insideGrid(x: Int, z: Int): Boolean = x >= 0 && z >= 0 && x < GRID_SIZE && z < GRID_SIZE

    private data class Footprint(val cells: List<DungeonRoomOverlayGeometry.Cell>, val known: Boolean)
    private data class GridPosition(val x: Int, val z: Int)
    private data class DoorHintProjection(
        val hints: List<DungeonRoomOverlayGeometry.DoorHint>,
        val candidates: Int,
        val alreadyVisible: Int,
        val outsideGrid: Int,
        val suppressedByReveal: Int,
    )

}


