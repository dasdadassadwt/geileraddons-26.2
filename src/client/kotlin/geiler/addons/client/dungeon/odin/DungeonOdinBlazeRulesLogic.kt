package geiler.addons.client.dungeon.odin

import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.function.BiPredicate

/** Kotlin owner for the display-only Blaze target selection and ordering rules. */
object DungeonOdinBlazeRulesLogic {
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
        observed: List<DungeonOdinBlazeRules.Candidate?>?,
        roomComplete: Boolean,
        position: DungeonOdinBlazeRules.Position?,
    ): List<DungeonOdinBlazeRules.Candidate> {
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
            it.source() == DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE
        }
        val unique = HashMap<String, DungeonOdinBlazeRules.Candidate>()
        val conflictingHealth = HashSet<String>()
        for (candidate in valid) {
            if (hasNameplate != (candidate.source() == DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE)) continue
            val stableId = candidate.stableId() ?: continue
            val previous = unique.putIfAbsent(stableId, candidate)
            if (previous != null && previous.health() != candidate.health()) conflictingHealth.add(stableId)
        }

        val targets = ArrayList(unique.values)
        targets.removeIf { it.stableId()?.let(conflictingHealth::contains) == true }
        val byStableId = Comparator<DungeonOdinBlazeRules.Candidate> { left, right ->
            left.stableId().orEmpty().compareTo(right.stableId().orEmpty())
        }
        val order = when (position ?: DungeonOdinBlazeRules.Position.UNSET) {
            DungeonOdinBlazeRules.Position.LOWER ->
                Comparator.comparingInt<DungeonOdinBlazeRules.Candidate> { it.health() }
                    .reversed().thenComparing(byStableId)
            DungeonOdinBlazeRules.Position.HIGHER ->
                Comparator.comparingInt<DungeonOdinBlazeRules.Candidate> { it.health() }
                    .thenComparing(byStableId)
            DungeonOdinBlazeRules.Position.UNSET -> byStableId
        }
        // Java Stream.toList() was unmodifiable; keep that behavior for Java and Kotlin callers.
        return Collections.unmodifiableList(ArrayList(targets.sortedWith(order)))
    }

    @JvmStatic
    fun parsePosition(value: String?): DungeonOdinBlazeRules.Position {
        if (value == null) return DungeonOdinBlazeRules.Position.UNSET
        return when (value.trim { it <= ' ' }.uppercase(java.util.Locale.ROOT)) {
            "LOWER" -> DungeonOdinBlazeRules.Position.LOWER
            "HIGHER" -> DungeonOdinBlazeRules.Position.HIGHER
            else -> DungeonOdinBlazeRules.Position.UNSET
        }
    }

    @JvmStatic
    fun positionForRoomName(roomName: String?): DungeonOdinBlazeRules.Position {
        if (roomName == null) return DungeonOdinBlazeRules.Position.UNSET
        return when (roomName.trim { it <= ' ' }) {
            "Lower Blaze" -> DungeonOdinBlazeRules.Position.LOWER
            "Higher Blaze" -> DungeonOdinBlazeRules.Position.HIGHER
            else -> DungeonOdinBlazeRules.Position.UNSET
        }
    }

    @JvmStatic
    fun resolvePosition(roomName: String?, configuredFallback: String?): DungeonOdinBlazeRules.Position {
        val fromRoom = positionForRoomName(roomName)
        return if (fromRoom == DungeonOdinBlazeRules.Position.UNSET) parsePosition(configuredFallback) else fromRoom
    }

    @JvmStatic
    fun orderUnavailableMessage(roomName: String?, configuredFallback: String?): String {
        val position = resolvePosition(roomName, configuredFallback)
        if (position == DungeonOdinBlazeRules.Position.UNSET) {
            return "Orientation unknown; set Blaze Room Position to LOWER or HIGHER to show order."
        }
        if (positionForRoomName(roomName) != DungeonOdinBlazeRules.Position.UNSET) {
            return "No ordered Blaze targets are available from the loaded labels for this exact room orientation."
        }
        return "No ordered Blaze targets are available from the loaded labels for the selected ${position.name} orientation."
    }

    @JvmStatic
    fun orderLinkCount(
        ordered: List<DungeonOdinBlazeRules.Candidate?>?,
        position: DungeonOdinBlazeRules.Position?,
    ): Int {
        if (ordered == null || ordered.size < 2 || position == null || position == DungeonOdinBlazeRules.Position.UNSET) {
            return 0
        }
        return minOf(DungeonOdinBlazeRules.MAX_ORDER_LINKS, ordered.size - 1)
    }
}
