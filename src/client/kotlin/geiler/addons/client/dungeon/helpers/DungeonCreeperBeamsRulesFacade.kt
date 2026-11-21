package geiler.addons.client.dungeon.helpers

import java.util.List as JavaList
import java.util.Set as JavaSet
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Java-facing value types and static entrypoints for the Kotlin Creeper Beams rules. */
class DungeonCreeperBeamsRules private constructor() {
    companion object {
        @JvmField
        val REQUIRED_MIN_PAIRS: Int = 5

        @JvmField
        val MAX_CATALOG_PAIRS: Int = 32

        @JvmStatic
        fun isCreeperBeamsRoom(roomName: String?): Boolean = DungeonCreeperBeamsRulesLogic.isCreeperBeamsRoom(roomName)

        @JvmStatic
        fun catalogWorldPositions(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
        ): List<WorldPosition> = DungeonCreeperBeamsRulesLogic.catalogWorldPositions(roomBaseX, roomBaseZ, catalog)

        @JvmStatic
        fun detect(
            roomVolumeLoaded: Boolean,
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
            observedBlocks: List<ObservedBlock?>?,
        ): Detection = DungeonCreeperBeamsRulesLogic.detect(roomVolumeLoaded, roomBaseX, roomBaseZ, catalog, observedBlocks)

        @JvmStatic
        fun detectResolvedRotation(
            roomVolumeLoaded: Boolean,
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
            observedBlocks: List<ObservedBlock?>?,
            resolvedRotation: Int,
        ): Detection = DungeonCreeperBeamsRulesLogic.detectResolvedRotation(
            roomVolumeLoaded, roomBaseX, roomBaseZ, catalog, observedBlocks, resolvedRotation,
        )

        @JvmStatic
        fun guidanceLines(detection: Detection?, roomBaseX: Int, roomBaseZ: Int): List<String> =
            DungeonCreeperBeamsRulesLogic.guidanceLines(detection, roomBaseX, roomBaseZ)

        @JvmStatic
        fun endpoints(detection: Detection?): Set<Endpoint> = DungeonCreeperBeamsRulesLogic.endpoints(detection)

        @JvmStatic
        fun catalogPairsForRotation(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
            rotation: Int,
        ): List<Pair> = DungeonCreeperBeamsRulesLogic.catalogPairsForRotation(roomBaseX, roomBaseZ, catalog, rotation)

        /** Per frozen pair: true once it left the active detection, so surviving pairs keep their index and color. */
        @JvmStatic
        fun stablePairProgress(frozen: List<Pair>, detection: Detection?): List<Boolean> =
            DungeonCreeperBeamsRulesLogic.stablePairProgress(frozen, detection)

        @JvmStatic
        fun containsEndpoint(detection: Detection?, position: WorldPosition?): Boolean =
            DungeonCreeperBeamsRulesLogic.containsEndpoint(detection, position)
    }

    enum class UnknownReason {
        UNLOADED_ROOM_VOLUME,
        CATALOG_UNAVAILABLE,
        INVALID_CATALOG,
        INCOMPLETE_ENDPOINT_OBSERVATIONS,
        DUPLICATE_OR_INVALID_OBSERVATION,
        NO_CATALOG_MATCH,
        AMBIGUOUS_ORIENTATION,
    }

    enum class BlockKind { SEA_LANTERN, PRISMARINE, OTHER }

    @JvmRecord
    data class WorldPosition(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "WorldPosition[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class ObservedBlock(val position: WorldPosition?, val blockKind: BlockKind?) {
        override fun toString(): String = "ObservedBlock[position=$position, blockKind=$blockKind]"
    }

    @JvmRecord
    data class Endpoint(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "Endpoint[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Pair(val first: Endpoint?, val second: Endpoint?) {
        override fun toString(): String = "Pair[first=$first, second=$second]"
    }

    @JvmRecord
    private data class OrientationCandidate(val rotation: Int, val pairs: List<Pair>) {
        override fun toString(): String = "OrientationCandidate[rotation=$rotation, pairs=$pairs]"
    }

    /** A value class because its former compact record constructor snapshots and normalizes its list. */
    class Detection(rotation: Int, pairs: List<Pair?>?, unknownReason: UnknownReason?) {
        private val rotationValue = rotation
        private val pairsValue: List<Pair> = JavaList.copyOf((pairs ?: JavaList.of()).map { it!! })
        private val unknownReasonValue = unknownReason

        fun rotation(): Int = rotationValue
        fun pairs(): List<Pair> = pairsValue
        fun unknownReason(): UnknownReason? = unknownReasonValue
        fun known(): Boolean = unknownReasonValue == null && rotationValue >= 0

        override fun equals(other: Any?): Boolean = other is Detection && rotationValue == other.rotationValue &&
            pairsValue == other.pairsValue && unknownReasonValue == other.unknownReasonValue

        override fun hashCode(): Int {
            var result = rotationValue
            result = 31 * result + pairsValue.hashCode()
            result = 31 * result + (unknownReasonValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Detection[rotation=$rotationValue, pairs=$pairsValue, unknownReason=$unknownReasonValue]"
    }
}
