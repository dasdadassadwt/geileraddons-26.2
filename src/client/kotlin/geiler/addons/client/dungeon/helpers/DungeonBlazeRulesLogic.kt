package geiler.addons.client.dungeon.helpers

import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.function.BiPredicate

/** Kotlin owner for the display-only Blaze target selection and ordering rules. */
object DungeonBlazeRulesLogic {
    @JvmStatic
    fun allRoomChunksLoaded(
        baseX: Int,
        baseZ: Int,
        roomSize: Int,
        isChunkLoaded: BiPredicate<Int, Int>?,
    ): Boolean {
        if (roomSize <= 0 || isChunkLoaded == null) return false
        val firstChunkX = Math.floorDiv(baseX, 16)
        val lastChunkX = Math.floorDiv(baseX + roomSize - 1, 16)
        val firstChunkZ = Math.floorDiv(baseZ, 16)
        val lastChunkZ = Math.floorDiv(baseZ + roomSize - 1, 16)
        for (chunkX in firstChunkX..lastChunkX) {
            for (chunkZ in firstChunkZ..lastChunkZ) {
                if (!isChunkLoaded.test(chunkX, chunkZ)) return false
            }
        }
        return true
    }

    /** A complete scan is required; resolved nameplates take precedence over direct entity candidates. */
    @JvmStatic
    fun orderTargets(
        observed: List<DungeonBlazeRules.Candidate?>?,
        roomComplete: Boolean,
        position: DungeonBlazeRules.Position?,
    ): List<DungeonBlazeRules.Candidate> {
        if (!roomComplete || observed.isNullOrEmpty()) return emptyList()

        val valid = observed.filterNotNull().filter { candidate ->
            val stableId = candidate.stableId()
            stableId != null && stableId.isNotBlank()
                && candidate.health() >= 0
                && candidate.alive()
                && candidate.loaded()
                && candidate.insideRoom()
                && candidate.source() != null
                && candidate.blazeBodyResolved()
        }
        if (valid.isEmpty()) return emptyList()

        val hasNameplate = valid.any {
            it.source() == DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE
        }
        val unique = HashMap<String, DungeonBlazeRules.Candidate>()
        val conflictingHealth = HashSet<String>()
        for (candidate in valid) {
            if (hasNameplate != (candidate.source() == DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE)) continue
            val stableId = candidate.stableId() ?: continue
            val previous = unique.putIfAbsent(stableId, candidate)
            if (previous != null && previous.health() != candidate.health()) conflictingHealth.add(stableId)
        }

        val targets = ArrayList(unique.values)
        targets.removeIf { it.stableId()?.let(conflictingHealth::contains) == true }
        val byStableId = Comparator<DungeonBlazeRules.Candidate> { left, right ->
            left.stableId().orEmpty().compareTo(right.stableId().orEmpty())
        }
        val order = when (position ?: DungeonBlazeRules.Position.UNSET) {
            DungeonBlazeRules.Position.LOWER ->
                Comparator.comparingInt<DungeonBlazeRules.Candidate> { it.health() }
                    .reversed().thenComparing(byStableId)
            DungeonBlazeRules.Position.HIGHER ->
                Comparator.comparingInt<DungeonBlazeRules.Candidate> { it.health() }
                    .thenComparing(byStableId)
            DungeonBlazeRules.Position.UNSET -> byStableId
        }
        // Java Stream.toList() was unmodifiable; keep that behavior for Java and Kotlin callers.
        return Collections.unmodifiableList(ArrayList(targets.sortedWith(order)))
    }

    @JvmStatic
    fun parsePosition(value: String?): DungeonBlazeRules.Position {
        if (value == null) return DungeonBlazeRules.Position.UNSET
        return when (value.trim { it <= ' ' }.uppercase(java.util.Locale.ROOT)) {
            "LOWER" -> DungeonBlazeRules.Position.LOWER
            "HIGHER" -> DungeonBlazeRules.Position.HIGHER
            else -> DungeonBlazeRules.Position.UNSET
        }
    }

    @JvmStatic
    fun positionForRoomName(roomName: String?): DungeonBlazeRules.Position {
        if (roomName == null) return DungeonBlazeRules.Position.UNSET
        return when (roomName.trim { it <= ' ' }) {
            "Lower Blaze" -> DungeonBlazeRules.Position.LOWER
            "Higher Blaze" -> DungeonBlazeRules.Position.HIGHER
            else -> DungeonBlazeRules.Position.UNSET
        }
    }

    @JvmStatic
    fun resolvePosition(roomName: String?, configuredFallback: String?): DungeonBlazeRules.Position {
        val fromRoom = positionForRoomName(roomName)
        return if (fromRoom == DungeonBlazeRules.Position.UNSET) parsePosition(configuredFallback) else fromRoom
    }

    @JvmStatic
    fun orderUnavailableMessage(roomName: String?, configuredFallback: String?): String {
        val position = resolvePosition(roomName, configuredFallback)
        if (position == DungeonBlazeRules.Position.UNSET) {
            return "Orientation unknown; set Blaze Room Position to LOWER or HIGHER to show order."
        }
        if (positionForRoomName(roomName) != DungeonBlazeRules.Position.UNSET) {
            return "No ordered Blaze targets are available from the loaded labels for this exact room orientation."
        }
        return "No ordered Blaze targets are available from the loaded labels for the selected ${position.name} orientation."
    }

    @JvmStatic
    fun orderLinkCount(
        ordered: List<DungeonBlazeRules.Candidate?>?,
        position: DungeonBlazeRules.Position?,
    ): Int {
        if (ordered == null || ordered.size < 2 || position == null || position == DungeonBlazeRules.Position.UNSET) {
            return 0
        }
        return minOf(DungeonBlazeRules.MAX_ORDER_LINKS, ordered.size - 1)
    }
}
