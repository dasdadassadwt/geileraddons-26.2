package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Stable Java API for the Kotlin-backed pure TP Maze catalog and recommendation rules. */
@JvmSuppressWildcards
class DungeonTpMazeRules private constructor() {
    enum class UnknownReason { INCOMPLETE_SCAN, CATALOG_UNAVAILABLE, INVALID_OBSERVATION, NO_CATALOG_MATCH, AMBIGUOUS_ORIENTATION }

    @JvmRecord
    data class WorldPosition(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "WorldPosition[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Position(val x: Double, val y: Double, val z: Double) {
        fun finite(): Boolean = DungeonTpMazeRulesCore.isFinite(x, y, z)
        override fun toString(): String = "Position[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Bounds(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
        fun valid(): Boolean = DungeonTpMazeRulesCore.isValidBounds(minX, minY, minZ, maxX, maxY, maxZ)
        override fun toString(): String =
            "Bounds[minX=$minX, minY=$minY, minZ=$minZ, maxX=$maxX, maxY=$maxY, maxZ=$maxZ]"
    }

    @JvmRecord
    data class ObservedBlock(val position: WorldPosition?, val isPortal: Boolean?) {
        override fun toString(): String = "ObservedBlock[position=$position, isPortal=$isPortal]"
    }

    @JvmRecord
    data class Pad(val id: Int, val position: WorldPosition?) {
        override fun toString(): String = "Pad[id=$id, position=$position]"
    }

    class Detection(rotation: Int, pads: List<Pad?>?, unknownReason: UnknownReason?) {
        private val rotationValue = rotation
        private val padsValue: List<Pad> = java.util.List.copyOf((pads ?: emptyList()).map { it!! })
        private val unknownReasonValue = unknownReason

        fun rotation(): Int = rotationValue
        fun pads(): List<Pad> = padsValue
        fun unknownReason(): UnknownReason? = unknownReasonValue
        fun known(): Boolean = DungeonTpMazeRulesCore.isKnown(unknownReasonValue, rotationValue, padsValue.size)

        override fun equals(other: Any?): Boolean =
            this === other || (other is Detection && rotationValue == other.rotationValue &&
                padsValue == other.padsValue && unknownReasonValue == other.unknownReasonValue)

        override fun hashCode(): Int {
            var result = rotationValue
            result = 31 * result + padsValue.hashCode()
            result = 31 * result + (unknownReasonValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Detection[rotation=$rotationValue, pads=$padsValue, unknownReason=$unknownReasonValue]"
    }

    companion object {
        private const val ROTATION_COUNT = 4
        const val PAD_COUNT = 30
        const val INTERIOR_PAD_COUNT = 28
        const val END_PAD_ID = 29
        const val START_PAD_ID = 30

        @JvmStatic
        fun probePositions(roomBaseX: Int, roomBaseZ: Int, catalog: DungeonTpMazeCatalog.CatalogResult?): List<WorldPosition> =
            DungeonTpMazeRulesCore.probePositions(roomBaseX, roomBaseZ, catalog)

        /** Requires every portal coordinate in every possible rotation to be loaded and uniquely oriented. */
        @JvmStatic
        fun detect(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonTpMazeCatalog.CatalogResult?,
            observations: List<ObservedBlock?>?,
            completeLoadedScan: Boolean,
        ): Detection = DungeonTpMazeRulesCore.detect(roomBaseX, roomBaseZ, catalog, observations, completeLoadedScan)

        @JvmStatic
        fun pads(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonTpMazeCatalog.CatalogResult?,
            rotation: Int,
        ): List<Pad> = DungeonTpMazeRulesCore.pads(roomBaseX, roomBaseZ, catalog, rotation)

        /** Odin seeds its candidate set from all pads only when the prior view-filtered set is empty. */
        @JvmStatic
        fun seedViewCandidates(pads: List<Pad?>?, existingCandidates: Set<Int>?): Set<Int> =
            DungeonTpMazeRulesCore.seedViewCandidates(pads, existingCandidates)

        /** Odin only processes position corrections on the maze's half-block grid at Y=69.5. */
        @JvmStatic
        fun isTeleportLanding(position: Position?): Boolean = DungeonTpMazeRulesCore.isTeleportLanding(position)

        /** Returns catalog pad IDs whose unit-block bounds intersect the supplied world-space bounds. */
        @JvmStatic
        fun padsIntersectingBounds(pads: List<Pad?>?, bounds: Bounds?): Set<Int> =
            DungeonTpMazeRulesCore.padsIntersectingBounds(pads, bounds)

        /**
         * Applies the reference solver's last-unused-pad inference only when the complete canonical pad
         * list is present and exactly one of the 28 interior pads has not been observed. Catalog pads 29
         * and 30 are the fixed end and start pads; missing teleport observations therefore remain unknown.
         */
        @JvmStatic
        fun uniqueFinalPad(pads: List<Pad?>?, visitedIds: Set<Int>?): Pad? =
            DungeonTpMazeRulesCore.uniqueFinalPad(pads, visitedIds)

        /** Odin-parity mode has no final-pad inference; the optional extension remains explicit. */
        @JvmStatic
        fun finalPadSuggestion(extensionEnabled: Boolean, pads: List<Pad?>?, visitedIds: Set<Int>?): Pad? =
            DungeonTpMazeRulesCore.finalPadSuggestion(extensionEnabled, pads, visitedIds)

        /**
         * Intersects a shrinking set of possible portals with the view ray observed at a teleport
         * correction. This is a geometric sightline test; it does not claim block-occlusion visibility.
         */
        @JvmStatic
        fun retainViewCandidates(
            pads: List<Pad?>?,
            candidates: Set<Int>?,
            visitedIds: Set<Int>?,
            viewpoint: Position?,
            yaw: Float,
            pitch: Float,
            maxDistance: Double,
        ): Set<Int> = DungeonTpMazeRulesCore.retainViewCandidates(
            pads,
            candidates,
            visitedIds,
            viewpoint,
            yaw,
            pitch,
            maxDistance,
        )

        /** Same as the base view test, with the currently occupied player-box pads excluded. */
        @JvmStatic
        fun retainViewCandidates(
            pads: List<Pad?>?,
            candidates: Set<Int>?,
            visitedIds: Set<Int>?,
            occupiedPadIds: Set<Int>?,
            viewpoint: Position?,
            yaw: Float,
            pitch: Float,
            maxDistance: Double,
        ): Set<Int> = DungeonTpMazeRulesCore.retainViewCandidates(
            pads,
            candidates,
            visitedIds,
            occupiedPadIds,
            viewpoint,
            yaw,
            pitch,
            maxDistance,
        )

        /** Chooses an unvisited portal in this four-pad group, preferring catalog-order sightline matches. */
        @JvmStatic
        fun chooseNextPad(pads: List<Pad?>?, currentId: Int, visitedIds: Set<Int>?, yaw: Float): Pad? =
            DungeonTpMazeRulesCore.chooseNextPad(pads, currentId, visitedIds, yaw)

        @JvmStatic
        fun chooseNextPad(
            pads: List<Pad?>?,
            currentId: Int,
            visitedIds: Set<Int>?,
            viewCandidates: Set<Int>?,
            yaw: Float,
        ): Pad? = DungeonTpMazeRulesCore.chooseNextPad(pads, currentId, visitedIds, viewCandidates, yaw)

        /** Uses the packet's landing position for the yaw fallback, matching Odin's correction handler. */
        @JvmStatic
        fun chooseNextPad(
            pads: List<Pad?>?,
            currentId: Int,
            visitedIds: Set<Int>?,
            viewCandidates: Set<Int>?,
            yaw: Float,
            headingOrigin: Position?,
        ): Pad? = DungeonTpMazeRulesCore.chooseNextPad(pads, currentId, visitedIds, viewCandidates, yaw, headingOrigin)
    }
}

internal fun DungeonTpMazeRules.WorldPosition.x(): Int = x
internal fun DungeonTpMazeRules.WorldPosition.y(): Int = y
internal fun DungeonTpMazeRules.WorldPosition.z(): Int = z
internal fun DungeonTpMazeRules.Position.x(): Double = x
internal fun DungeonTpMazeRules.Position.y(): Double = y
internal fun DungeonTpMazeRules.Position.z(): Double = z
internal fun DungeonTpMazeRules.Bounds.minX(): Double = minX
internal fun DungeonTpMazeRules.Bounds.minY(): Double = minY
internal fun DungeonTpMazeRules.Bounds.minZ(): Double = minZ
internal fun DungeonTpMazeRules.Bounds.maxX(): Double = maxX
internal fun DungeonTpMazeRules.Bounds.maxY(): Double = maxY
internal fun DungeonTpMazeRules.Bounds.maxZ(): Double = maxZ
internal fun DungeonTpMazeRules.ObservedBlock.position(): DungeonTpMazeRules.WorldPosition? = position
internal fun DungeonTpMazeRules.ObservedBlock.isPortal(): Boolean? = isPortal
internal fun DungeonTpMazeRules.Pad.id(): Int = id
internal fun DungeonTpMazeRules.Pad.position(): DungeonTpMazeRules.WorldPosition? = position
