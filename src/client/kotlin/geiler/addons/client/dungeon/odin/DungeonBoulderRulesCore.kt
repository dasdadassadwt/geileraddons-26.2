package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashSet

/** Pure loaded-observation matching behind the Java Boulder DTO and static API. */
internal object DungeonBoulderRulesCore {
    private const val ROTATION_COUNT = 4
    private const val SIGNATURE_Y = 65
    private const val SIGNATURE_LENGTH = 42

    private data class Sample(val x: Int, val z: Int)
    private data class Match(val rotation: Int, val targets: List<DungeonBoulderCatalog.Target>)

    private val samples: List<Sample> = buildList(SIGNATURE_LENGTH) {
        for (z in 24 downTo 9 step 3) for (x in 24 downTo 6 step 3) add(Sample(x, z))
    }

    fun shouldRenderTarget(selection: String?, activePuzzleName: String?): Boolean =
        selection == "BOULDER" || selection == "AUTO" && activePuzzleName == "Boulder"

    fun shouldAdvanceInteraction(mainHand: Boolean, dungeonClearPhase: Boolean): Boolean =
        mainHand && dungeonClearPhase

    fun probePositions(roomBaseX: Int, roomBaseZ: Int): List<DungeonBoulderRules.WorldPosition> {
        val positions = LinkedHashSet<DungeonBoulderRules.WorldPosition>()
        for (rotation in 0 until ROTATION_COUNT) for (sample in samples) {
            positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, sample.x, SIGNATURE_Y, sample.z))
        }
        return java.util.List.copyOf(positions)
    }

    fun probePositions(
        roomBaseX: Int,
        roomBaseZ: Int,
        rotation: Int,
    ): List<DungeonBoulderRules.WorldPosition> {
        if (rotation !in 0 until ROTATION_COUNT) return java.util.List.of()
        val positions = ArrayList<DungeonBoulderRules.WorldPosition>(samples.size)
        for (sample in samples) {
            positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, sample.x, SIGNATURE_Y, sample.z))
        }
        return java.util.List.copyOf(positions)
    }

    fun detectAtRotation(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonBoulderCatalog.CatalogResult?,
        observations: List<DungeonBoulderRules.ObservedBlock?>?,
        completeLoadedScan: Boolean,
        rotation: Int,
    ): DungeonBoulderRules.Detection {
        if (!completeLoadedScan) return unknown(DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN)
        if (catalog == null || !catalog.available()) return unknown(DungeonBoulderRules.UnknownReason.CATALOG_UNAVAILABLE)
        if (rotation !in 0 until ROTATION_COUNT) return unknown(DungeonBoulderRules.UnknownReason.AMBIGUOUS_ORIENTATION)

        val isAir = HashMap<DungeonBoulderRules.WorldPosition, Boolean>()
        for (observation in observations.orEmpty()) {
            val position = observation?.position() ?: return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
            val air = observation.isAir() ?: return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
            if (isAir.putIfAbsent(position, air) != null) return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
        }

        val signature = StringBuilder(SIGNATURE_LENGTH)
        for (sample in samples) {
            val position = worldPosition(roomBaseX, roomBaseZ, rotation, sample.x, SIGNATURE_Y, sample.z)
            val air = isAir[position] ?: return unknown(DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN)
            signature.append(if (air) '0' else '1')
        }

        val signatureValue = signature.toString()
        val route = catalog.targets(signatureValue)
        if (route.isEmpty()) return unknown(DungeonBoulderRules.UnknownReason.NO_CATALOG_MATCH)
        val targets = route.map { target ->
            DungeonBoulderRules.Target(
                worldPosition(roomBaseX, roomBaseZ, rotation, target.renderX(), SIGNATURE_Y, target.renderZ()),
                worldPosition(roomBaseX, roomBaseZ, rotation, target.clickX(), SIGNATURE_Y, target.clickZ()),
            )
        }
        return DungeonBoulderRules.Detection(rotation, signatureValue, targets, null)
    }

    fun detect(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonBoulderCatalog.CatalogResult?,
        observations: List<DungeonBoulderRules.ObservedBlock?>?,
        completeLoadedScan: Boolean,
    ): DungeonBoulderRules.Detection {
        if (!completeLoadedScan) return unknown(DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN)
        if (catalog == null || !catalog.available()) return unknown(DungeonBoulderRules.UnknownReason.CATALOG_UNAVAILABLE)

        val isAir = HashMap<DungeonBoulderRules.WorldPosition, Boolean>()
        for (observation in observations.orEmpty()) {
            val position = observation?.position() ?: return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
            val air = observation.isAir() ?: return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
            if (isAir.putIfAbsent(position, air) != null) return unknown(DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION)
        }
        for (required in probePositions(roomBaseX, roomBaseZ)) {
            if (!isAir.containsKey(required)) return unknown(DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN)
        }

        val matches = ArrayList<Match>()
        for (rotation in 0 until ROTATION_COUNT) {
            val signature = StringBuilder(SIGNATURE_LENGTH)
            for (sample in samples) {
                val position = worldPosition(roomBaseX, roomBaseZ, rotation, sample.x, SIGNATURE_Y, sample.z)
                signature.append(if (isAir[position] == true) '0' else '1')
            }
            val route = catalog.targets(signature.toString())
            if (route.isNotEmpty()) matches.add(Match(rotation, route))
        }
        if (matches.isEmpty()) return unknown(DungeonBoulderRules.UnknownReason.NO_CATALOG_MATCH)
        if (matches.size != 1) return unknown(DungeonBoulderRules.UnknownReason.AMBIGUOUS_ORIENTATION)

        val match = matches.first()
        val targets = match.targets.map { target ->
            DungeonBoulderRules.Target(
                worldPosition(roomBaseX, roomBaseZ, match.rotation, target.renderX(), SIGNATURE_Y, target.renderZ()),
                worldPosition(roomBaseX, roomBaseZ, match.rotation, target.clickX(), SIGNATURE_Y, target.clickZ()),
            )
        }
        return DungeonBoulderRules.Detection(
            match.rotation,
            signatureAt(roomBaseX, roomBaseZ, match.rotation, isAir),
            targets,
            null,
        )
    }

    fun renderTargets(detection: DungeonBoulderRules.Detection): List<DungeonBoulderRules.WorldPosition> =
        java.util.List.copyOf(detection.targets().mapNotNull { it?.renderPosition() })

    fun known(detection: DungeonBoulderRules.Detection): Boolean =
        detection.unknownReason() == null && detection.rotation() >= 0 &&
            detection.signature() != null && detection.targets().isNotEmpty()

    fun unknown(reason: DungeonBoulderRules.UnknownReason): DungeonBoulderRules.Detection =
        DungeonBoulderRules.Detection(-1, null, java.util.List.of(), reason)

    private fun signatureAt(
        baseX: Int,
        baseZ: Int,
        rotation: Int,
        isAir: Map<DungeonBoulderRules.WorldPosition, Boolean>,
    ): String {
        val signature = StringBuilder(SIGNATURE_LENGTH)
        for (sample in samples) {
            val position = worldPosition(baseX, baseZ, rotation, sample.x, SIGNATURE_Y, sample.z)
            signature.append(if (isAir[position] == true) '0' else '1')
        }
        return signature.toString()
    }

    private fun worldPosition(
        roomBaseX: Int,
        roomBaseZ: Int,
        rotation: Int,
        x: Int,
        y: Int,
        z: Int,
    ): DungeonBoulderRules.WorldPosition {
        val rotated = DungeonGridRules.toWorld(rotation, x, z)
        return DungeonBoulderRules.WorldPosition(roomBaseX + rotated.x, y, roomBaseZ + rotated.z)
    }
}
