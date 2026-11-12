package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashSet

/** Pure catalog matching and display recommendation logic behind the stable Java DTO ABI. */
internal object DungeonTpMazeRulesCore {
    private const val ROTATION_COUNT = 4

    fun probePositions(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonTpMazeCatalog.CatalogResult?,
    ): List<DungeonTpMazeRules.WorldPosition> {
        if (catalog == null || !catalog.available() || catalog.pads().size != DungeonTpMazeRules.PAD_COUNT)
            return java.util.List.of()
        val positions = LinkedHashSet<DungeonTpMazeRules.WorldPosition>()
        for (rotation in 0 until ROTATION_COUNT) {
            for (point in catalog.pads()) positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, point))
        }
        return java.util.List.copyOf(positions)
    }

    /** Requires every portal coordinate in every possible rotation to be loaded and uniquely oriented. */
    fun detect(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonTpMazeCatalog.CatalogResult?,
        observations: List<DungeonTpMazeRules.ObservedBlock?>?,
        completeLoadedScan: Boolean,
    ): DungeonTpMazeRules.Detection {
        if (!completeLoadedScan) return unknown(DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN)
        if (catalog == null || !catalog.available() || catalog.pads().size != DungeonTpMazeRules.PAD_COUNT)
            return unknown(DungeonTpMazeRules.UnknownReason.CATALOG_UNAVAILABLE)

        val isPortal = HashMap<DungeonTpMazeRules.WorldPosition, Boolean>()
        if (observations != null) {
            for (observation in observations) {
                if (observation == null) return unknown(DungeonTpMazeRules.UnknownReason.INVALID_OBSERVATION)
                val position = observation.position ?: return unknown(DungeonTpMazeRules.UnknownReason.INVALID_OBSERVATION)
                val portal = observation.isPortal ?: return unknown(DungeonTpMazeRules.UnknownReason.INVALID_OBSERVATION)
                if (isPortal.putIfAbsent(position, portal) != null)
                    return unknown(DungeonTpMazeRules.UnknownReason.INVALID_OBSERVATION)
            }
        }
        for (required in probePositions(roomBaseX, roomBaseZ, catalog)) {
            if (!isPortal.containsKey(required)) return unknown(DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN)
        }

        val matches = ArrayList<Int>()
        for (rotation in 0 until ROTATION_COUNT) {
            val allPads = catalog.pads().stream().allMatch { point ->
                java.lang.Boolean.TRUE.equals(isPortal[worldPosition(roomBaseX, roomBaseZ, rotation, point)])
            }
            if (allPads) matches.add(rotation)
        }
        if (matches.isEmpty()) return unknown(DungeonTpMazeRules.UnknownReason.NO_CATALOG_MATCH)
        if (matches.size != 1) return unknown(DungeonTpMazeRules.UnknownReason.AMBIGUOUS_ORIENTATION)
        val rotation = matches[0]
        return DungeonTpMazeRules.Detection(rotation, pads(roomBaseX, roomBaseZ, catalog, rotation), null)
    }

    fun pads(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonTpMazeCatalog.CatalogResult?,
        rotation: Int,
    ): List<DungeonTpMazeRules.Pad> {
        if (catalog == null || !catalog.available() || rotation < 0 || rotation >= ROTATION_COUNT)
            return java.util.List.of()
        val pads = ArrayList<DungeonTpMazeRules.Pad>(catalog.pads().size)
        for (index in catalog.pads().indices) {
            val position = worldPosition(roomBaseX, roomBaseZ, rotation, catalog.pads()[index])
            pads.add(DungeonTpMazeRules.Pad(index + 1, position))
        }
        return java.util.List.copyOf(pads)
    }

    /** Odin seeds its candidate set from all pads only when the prior view-filtered set is empty. */
    fun seedViewCandidates(
        pads: List<DungeonTpMazeRules.Pad?>?,
        existingCandidates: Set<Int>?,
    ): Set<Int> {
        if (existingCandidates != null && existingCandidates.isNotEmpty()) return java.util.Set.copyOf(existingCandidates)
        if (pads == null || pads.isEmpty()) return java.util.Set.of()
        val seeded = LinkedHashSet<Int>()
        for (pad in pads) if (pad != null && pad.position != null) seeded.add(pad.id)
        return java.util.Set.copyOf(seeded)
    }

    /** Odin only processes position corrections on the maze's half-block grid at Y=69.5. */
    fun isTeleportLanding(position: DungeonTpMazeRules.Position?): Boolean =
        position != null && position.finite() && position.y == 69.5 &&
            position.x % 0.5 == 0.0 && position.z % 0.5 == 0.0

    /** Returns catalog pad IDs whose unit-block bounds intersect the supplied world-space bounds. */
    fun padsIntersectingBounds(
        pads: List<DungeonTpMazeRules.Pad?>?,
        bounds: DungeonTpMazeRules.Bounds?,
    ): Set<Int> {
        if (pads == null || bounds == null || !bounds.valid()) return java.util.Set.of()
        val intersecting = LinkedHashSet<Int>()
        for (pad in pads) {
            if (pad == null) continue
            val position = pad.position ?: continue
            if (position.x + 1.0 > bounds.minX && position.x < bounds.maxX &&
                position.y + 1.0 > bounds.minY && position.y < bounds.maxY &&
                position.z + 1.0 > bounds.minZ && position.z < bounds.maxZ
            ) intersecting.add(pad.id)
        }
        return java.util.Set.copyOf(intersecting)
    }

    /** Returns a final interior pad only when all 30 canonical IDs are uniquely present. */
    fun uniqueFinalPad(
        pads: List<DungeonTpMazeRules.Pad?>?,
        visitedIds: Set<Int>?,
    ): DungeonTpMazeRules.Pad? {
        if (pads == null || pads.size != DungeonTpMazeRules.PAD_COUNT) return null
        val byId = HashMap<Int, DungeonTpMazeRules.Pad>()
        for (pad in pads) {
            if (pad == null || pad.id < 1 || pad.id > DungeonTpMazeRules.PAD_COUNT || pad.position == null ||
                byId.putIfAbsent(pad.id, pad) != null) return null
        }
        if (byId.size != DungeonTpMazeRules.PAD_COUNT) return null
        val visited = visitedIds ?: java.util.Set.of()
        var remaining: DungeonTpMazeRules.Pad? = null
        for (id in 1..DungeonTpMazeRules.INTERIOR_PAD_COUNT) {
            if (visited.contains(id)) continue
            if (remaining != null) return null
            remaining = byId[id]
        }
        return remaining
    }

    /** Odin-parity mode has no final-pad inference; the optional extension remains explicit. */
    fun finalPadSuggestion(
        extensionEnabled: Boolean,
        pads: List<DungeonTpMazeRules.Pad?>?,
        visitedIds: Set<Int>?,
    ): DungeonTpMazeRules.Pad? = if (extensionEnabled) uniqueFinalPad(pads, visitedIds) else null

    /** Geometric sightline intersection only; it does not claim block-occlusion visibility. */
    fun retainViewCandidates(
        pads: List<DungeonTpMazeRules.Pad?>?,
        candidates: Set<Int>?,
        visitedIds: Set<Int>?,
        viewpoint: DungeonTpMazeRules.Position?,
        yaw: Float,
        pitch: Float,
        maxDistance: Double,
    ): Set<Int> {
        if (pads == null || candidates == null || candidates.isEmpty() || viewpoint == null ||
            !viewpoint.finite() || !yaw.isFinite() || !pitch.isFinite() || !maxDistance.isFinite() || maxDistance <= 0.0
        ) return java.util.Set.of()
        val visited = visitedIds ?: java.util.Set.of()
        val visible = LinkedHashSet<Int>()
        for (pad in pads) {
            if (pad == null || pad.position == null || !candidates.contains(pad.id) || visited.contains(pad.id)) continue
            if (viewRayIntersects(viewpoint, yaw, pitch, pad.position, maxDistance)) visible.add(pad.id)
        }
        return java.util.Set.copyOf(visible)
    }

    /** Same as the base view test, with the currently occupied player-box pads excluded. */
    fun retainViewCandidates(
        pads: List<DungeonTpMazeRules.Pad?>?,
        candidates: Set<Int>?,
        visitedIds: Set<Int>?,
        occupiedPadIds: Set<Int>?,
        viewpoint: DungeonTpMazeRules.Position?,
        yaw: Float,
        pitch: Float,
        maxDistance: Double,
    ): Set<Int> {
        if (occupiedPadIds == null || occupiedPadIds.isEmpty())
            return retainViewCandidates(pads, candidates, visitedIds, viewpoint, yaw, pitch, maxDistance)
        val excluded = HashSet(visitedIds ?: java.util.Set.of())
        excluded.addAll(occupiedPadIds)
        return retainViewCandidates(pads, candidates, excluded, viewpoint, yaw, pitch, maxDistance)
    }

    /** Chooses an unvisited portal in this four-pad group, preferring catalog-order sightline matches. */
    fun chooseNextPad(
        pads: List<DungeonTpMazeRules.Pad?>?,
        currentId: Int,
        visitedIds: Set<Int>?,
        yaw: Float,
    ): DungeonTpMazeRules.Pad? = chooseNextPad(pads, currentId, visitedIds, null, yaw, null)

    fun chooseNextPad(
        pads: List<DungeonTpMazeRules.Pad?>?,
        currentId: Int,
        visitedIds: Set<Int>?,
        viewCandidates: Set<Int>?,
        yaw: Float,
    ): DungeonTpMazeRules.Pad? = chooseNextPad(pads, currentId, visitedIds, viewCandidates, yaw, null)

    /** Uses the packet's landing position for the yaw fallback, matching Odin's correction handler. */
    fun chooseNextPad(
        pads: List<DungeonTpMazeRules.Pad?>?,
        currentId: Int,
        visitedIds: Set<Int>?,
        viewCandidates: Set<Int>?,
        yaw: Float,
        headingOrigin: DungeonTpMazeRules.Position?,
    ): DungeonTpMazeRules.Pad? {
        if (pads == null || currentId < 1 || currentId > DungeonTpMazeRules.INTERIOR_PAD_COUNT || !yaw.isFinite())
            return null
        var current: DungeonTpMazeRules.Pad? = null
        for (pad in pads) {
            if (pad != null && pad.id == currentId) {
                current = pad
                break
            }
        }
        if (current == null) return null

        val visited = HashSet(visitedIds ?: java.util.Set.of())
        val firstId = ((currentId - 1) / 4) * 4 + 1
        val lastId = firstId + 3
        val candidates = ArrayList<DungeonTpMazeRules.Pad>()
        for (pad in pads) {
            if (pad != null && pad.position != null && pad.id >= firstId && pad.id <= lastId &&
                pad.id != currentId && !visited.contains(pad.id)) candidates.add(pad)
        }
        for (pad in candidates) {
            if (viewCandidates != null && viewCandidates.contains(pad.id)) return pad
        }

        val origin = if (headingOrigin != null && headingOrigin.finite()) headingOrigin else {
            val position = current.position ?: return null
            DungeonTpMazeRules.Position(position.x + 0.5, position.y + 0.5, position.z + 0.5)
        }
        return candidates.minWithOrNull(Comparator { first, second ->
            val firstPosition = first.position ?: return@Comparator 0
            val secondPosition = second.position ?: return@Comparator 0
            val firstYawDifference = yawDifference(yaw, targetYaw(origin, firstPosition))
            val secondYawDifference = yawDifference(yaw, targetYaw(origin, secondPosition))
            val byYaw = java.lang.Double.compare(firstYawDifference, secondYawDifference)
            if (byYaw != 0) byYaw else Integer.compare(first.id, second.id)
        })
    }

    fun isFinite(x: Double, y: Double, z: Double): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite()

    fun isValidBounds(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): Boolean = minX.isFinite() && minY.isFinite() && minZ.isFinite() &&
        maxX.isFinite() && maxY.isFinite() && maxZ.isFinite() &&
        maxX > minX && maxY > minY && maxZ > minZ

    fun isKnown(unknownReason: DungeonTpMazeRules.UnknownReason?, rotation: Int, padCount: Int): Boolean =
        unknownReason == null && rotation >= 0 && padCount == DungeonTpMazeRules.PAD_COUNT

    fun unknown(reason: DungeonTpMazeRules.UnknownReason?): DungeonTpMazeRules.Detection =
        DungeonTpMazeRules.Detection(-1, java.util.List.of(), reason)

    fun worldPosition(
        roomBaseX: Int,
        roomBaseZ: Int,
        rotation: Int,
        point: DungeonTpMazeCatalog.Point,
    ): DungeonTpMazeRules.WorldPosition {
        val rotated = DungeonGridRules.toWorld(rotation, point.x, point.z)
        return DungeonTpMazeRules.WorldPosition(roomBaseX + rotated.x, point.y, roomBaseZ + rotated.z)
    }

    private fun viewRayIntersects(
        origin: DungeonTpMazeRules.Position,
        yaw: Float,
        pitch: Float,
        pad: DungeonTpMazeRules.WorldPosition?,
        maxDistance: Double,
    ): Boolean {
        if (pad == null) return false
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val horizontal = Math.cos(pitchRadians)
        val dx = -Math.sin(yawRadians) * horizontal
        val dy = -Math.sin(pitchRadians)
        val dz = Math.cos(yawRadians) * horizontal
        val interval = doubleArrayOf(0.0, maxDistance)
        return intersectAxis(origin.x, dx, pad.x - 0.75, pad.x + 1.75, interval) &&
            intersectAxis(origin.y, dy, pad.y.toDouble(), pad.y + 4.0, interval) &&
            intersectAxis(origin.z, dz, pad.z - 0.75, pad.z + 1.75, interval) && interval[1] >= interval[0]
    }

    private fun intersectAxis(
        origin: Double,
        direction: Double,
        minimum: Double,
        maximum: Double,
        interval: DoubleArray,
    ): Boolean {
        if (Math.abs(direction) < 1.0e-10) return origin >= minimum && origin <= maximum
        var first = (minimum - origin) / direction
        var second = (maximum - origin) / direction
        if (first > second) {
            val swap = first
            first = second
            second = swap
        }
        interval[0] = Math.max(interval[0], first)
        interval[1] = Math.min(interval[1], second)
        return interval[1] >= interval[0]
    }

    private fun targetYaw(from: DungeonTpMazeRules.Position, to: DungeonTpMazeRules.WorldPosition): Float =
        (Math.toDegrees(Math.atan2(to.z + 0.5 - from.z, to.x + 0.5 - from.x)) - 90.0).toFloat()

    private fun yawDifference(from: Float, to: Float): Double {
        var difference = (to - from) % 360.0f
        if (difference >= 180.0f) difference -= 360.0f
        if (difference < -180.0f) difference += 360.0f
        return kotlin.math.abs(difference.toDouble())
    }
}
