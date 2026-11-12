package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import java.util.List.copyOf
import kotlin.jvm.JvmRecord

/** Java-facing entrypoints and values for the Kotlin dungeon doorway tracker. */
class DungeonDoorTracker private constructor() {
    companion object {
        @JvmStatic
        fun tick(client: Minecraft?) = DungeonDoorTrackerLogic.tick(client)

        @JvmStatic
        fun doors(): List<Door> = DungeonDoorTrackerLogic.doors()

        /** Requests one bounded exact-edge rescan when its samples are already loaded; never loads chunks. */
        @JvmStatic
        fun requestPromptRescan(level: ClientLevel?, firstCell: Int, secondCell: Int): PromptScanResult =
            DungeonDoorTrackerLogic.requestPromptRescan(level, firstCell, secondCell)

        /** Pure prompt-scan gate used by the client API and deterministic offline checks. */
        @JvmStatic
        fun promptScanDecision(
            trackerReady: Boolean,
            samplesLoaded: Boolean,
            alreadyQueued: Boolean,
            lastScanTick: Long,
            currentTick: Long,
        ): PromptScanResult = DungeonDoorTrackerLogic.promptScanDecision(
            trackerReady, samplesLoaded, alreadyQueued, lastScanTick, currentTick,
        )

        /** Bounded Start-room evidence for the automatic Glass Doors trace; this does not select targets. */
        @JvmStatic
        fun startDiagnosticsSummary(): String = DungeonDoorTrackerLogic.startDiagnosticsSummary()

        /** Monotonic identity for the active run, used only to reject stale temporary captures. */
        @JvmStatic
        fun dungeonSessionGeneration(): Long = DungeonDoorTrackerLogic.dungeonSessionGeneration()

        /** Returns the calibrated Entrance cell and world-grid origin for a temporary diagnostic capture. */
        @JvmStatic
        fun startDoorCaptureFrame(level: ClientLevel?): StartDoorCaptureFrame =
            DungeonDoorTrackerLogic.startDoorCaptureFrame(level)

        /** First player pose after dungeon context and the player's current chunk were ready. */
        @JvmStatic
        fun firstLoadedPlayer(): SpawnReference? = DungeonDoorTrackerLogic.firstLoadedPlayer()

        /** Called after a server block update has been applied on the client thread. */
        @JvmStatic
        fun onBlockChange(position: BlockPos?) = DungeonDoorTrackerLogic.onBlockChange(position)

        /** Returns Wither doors in route order, falling back to nearby observed doors if the graph is incomplete. */
        @JvmStatic
        fun orderedWitherDoors(playerPosition: BlockPos?, maximum: Int): List<Door> =
            DungeonDoorTrackerLogic.orderedWitherDoors(playerPosition, maximum)

        /** A loaded Wither doorway remains a target even before its route is known. */
        @JvmStatic
        fun nextWitherDoor(playerPosition: BlockPos?): Door? = DungeonDoorTrackerLogic.nextWitherDoor(playerPosition)

        /** The single Blood room entrance, chosen from the loaded snapshot nearest to the player. */
        @JvmStatic
        fun nextBloodDoor(playerPosition: BlockPos?): Door? = DungeonDoorTrackerLogic.nextBloodDoor(playerPosition)

        /** True when the door borders the room currently occupied by the player. */
        @JvmStatic
        fun touchesCell(door: Door?, cell: DungeonGridRules.Cell?): Boolean =
            DungeonDoorTrackerLogic.touchesCell(door, cell)

        @JvmStatic
        fun promptEdgeIndex(firstCell: Int, secondCell: Int): Int =
            DungeonDoorTrackerLogic.promptEdgeIndex(firstCell, secondCell)

        /** Pure spawn-forward distance gate retained for package-level Java checks. */
        @JvmStatic
        fun isSpawnStartForwardDistanceInRange(forwardDistance: Double): Boolean =
            DungeonDoorTrackerLogic.isSpawnStartForwardDistanceInRange(forwardDistance)

        /** Keeps standard Wither/Blood bounds at 3-by-4-by-3 around the detected edge plane. */
        @JvmStatic
        fun centeredDoor(type: Type?, firstCell: Int, secondCell: Int, planeCenter: BlockPos?): Door =
            DungeonDoorTrackerLogic.centeredDoor(type, firstCell, secondCell, planeCenter)

        @JvmStatic
        fun centeredDoor(
            type: Type?,
            firstCell: Int,
            secondCell: Int,
            planeCenter: BlockPos?,
            sourcePositions: List<BlockPos?>?,
        ): Door = DungeonDoorTrackerLogic.centeredDoor(type, firstCell, secondCell, planeCenter, sourcePositions)

        @JvmStatic
        fun centeredDoor(
            type: Type?,
            firstCell: Int,
            secondCell: Int,
            planeCenter: BlockPos?,
            sourcePositions: List<BlockPos?>?,
            sourceDiagnostics: String?,
        ): Door = DungeonDoorTrackerLogic.centeredDoor(
            type, firstCell, secondCell, planeCenter, sourcePositions, sourceDiagnostics,
        )
    }

    @JvmRecord
    data class StartDoorCaptureFrame(
        val ready: Boolean,
        val rejectionReason: String?,
        val runGeneration: Long,
        val mapId: String?,
        val entranceCell: Int,
        val entranceCellX: Int,
        val entranceCellZ: Int,
        val entranceBaseX: Int,
        val entranceBaseZ: Int,
        val diagnosticSummary: String?,
    )

    enum class PromptScanResult { QUEUED, ALREADY_QUEUED, RECENTLY_SCANNED, EDGE_UNLOADED, TRACKER_NOT_READY, INVALID_EDGE }

    enum class Type { NORMAL, GLASS, START, WITHER, BLOOD }

    /** Immutable door observation with the former record's constructor and accessor behavior. */
    class Door(
        private val typeValue: Type?,
        private val firstCellValue: Int,
        private val secondCellValue: Int,
        private val xValue: Int,
        private val yValue: Int,
        private val zValue: Int,
        private val widthValue: Int,
        private val heightValue: Int,
        private val depthValue: Int,
        sourcePositions: List<BlockPos?>?,
        sourceDiagnostics: String?,
    ) {
        private val sourcePositionsValue: List<BlockPos> = copyOf((sourcePositions ?: emptyList()).map { it!! })
        private val sourceDiagnosticsValue: String = sourceDiagnostics ?: ""

        constructor(
            type: Type?, firstCell: Int, secondCell: Int, x: Int, y: Int, z: Int,
            width: Int, height: Int, depth: Int, sourcePositions: List<BlockPos?>?,
        ) : this(type, firstCell, secondCell, x, y, z, width, height, depth, sourcePositions, "")

        constructor(
            type: Type?, firstCell: Int, secondCell: Int, x: Int, y: Int, z: Int,
            width: Int, height: Int, depth: Int,
        ) : this(type, firstCell, secondCell, x, y, z, width, height, depth, emptyList(), "")

        fun type(): Type? = typeValue
        fun firstCell(): Int = firstCellValue
        fun secondCell(): Int = secondCellValue
        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue
        fun width(): Int = widthValue
        fun height(): Int = heightValue
        fun depth(): Int = depthValue
        fun sourcePositions(): List<BlockPos> = sourcePositionsValue
        fun sourceDiagnostics(): String = sourceDiagnosticsValue

        fun identity(): DoorIdentity = DoorIdentity(firstCellValue, secondCellValue)

        fun withSourcePositions(positions: List<BlockPos?>?): Door =
            Door(typeValue, firstCellValue, secondCellValue, xValue, yValue, zValue, widthValue, heightValue,
                depthValue, positions, sourceDiagnosticsValue)

        fun withSourcePositions(positions: List<BlockPos?>?, diagnostics: String?): Door =
            Door(typeValue, firstCellValue, secondCellValue, xValue, yValue, zValue, widthValue, heightValue,
                depthValue, positions, diagnostics)

        fun centerX(): Double = xValue + widthValue / 2.0
        fun centerY(): Double = yValue + heightValue / 2.0
        fun centerZ(): Double = zValue + depthValue / 2.0

        override fun equals(other: Any?): Boolean = this === other || other is Door &&
            typeValue == other.typeValue && firstCellValue == other.firstCellValue &&
            secondCellValue == other.secondCellValue && xValue == other.xValue && yValue == other.yValue &&
            zValue == other.zValue && widthValue == other.widthValue && heightValue == other.heightValue &&
            depthValue == other.depthValue && sourcePositionsValue == other.sourcePositionsValue &&
            sourceDiagnosticsValue == other.sourceDiagnosticsValue

        override fun hashCode(): Int {
            var result = typeValue?.hashCode() ?: 0
            result = 31 * result + firstCellValue
            result = 31 * result + secondCellValue
            result = 31 * result + xValue
            result = 31 * result + yValue
            result = 31 * result + zValue
            result = 31 * result + widthValue
            result = 31 * result + heightValue
            result = 31 * result + depthValue
            result = 31 * result + sourcePositionsValue.hashCode()
            result = 31 * result + sourceDiagnosticsValue.hashCode()
            return result
        }

        override fun toString(): String =
            "Door[type=$typeValue, firstCell=$firstCellValue, secondCell=$secondCellValue, x=$xValue, y=$yValue, " +
                "z=$zValue, width=$widthValue, height=$heightValue, depth=$depthValue, " +
                "sourcePositions=$sourcePositionsValue, sourceDiagnostics=$sourceDiagnosticsValue]"
    }

    @JvmRecord
    data class DoorIdentity(val firstCell: Int, val secondCell: Int)

    @JvmRecord
    data class SpawnReference(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val blockPosition: BlockPos?,
        val tick: Long,
    )
}
