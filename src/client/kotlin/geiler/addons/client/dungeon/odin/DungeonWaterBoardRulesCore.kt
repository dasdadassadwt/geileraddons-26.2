package geiler.addons.client.dungeon.odin

import java.util.ArrayList
import java.util.HashSet

/** Loaded-signature validation and stable valve ordering behind the Java Water Board DTOs. */
internal object DungeonWaterBoardRulesCore {
    fun detect(candidates: List<DungeonWaterBoardRules.Candidate?>?): DungeonWaterBoardRules.Detection {
        if (candidates == null || candidates.isEmpty() || candidates.size > 4)
            return unknown(DungeonWaterBoardRules.UnknownReason.INCOMPLETE_ROTATIONS)
        val rotations = BooleanArray(4)
        for (candidate in candidates) {
            if (candidate == null || candidate.rotation() !in 0..3 || rotations[candidate.rotation()])
                return unknown(DungeonWaterBoardRules.UnknownReason.INCOMPLETE_ROTATIONS)
            rotations[candidate.rotation()] = true
            if (!candidate.loaded()) return unknown(DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES)
        }
        val matches = ArrayList<DungeonWaterBoardRules.Candidate>()
        for (candidate in candidates) {
            val patternIds = candidate!!.patternIds()
            if (patternIds.size != 1 || patternIds[0] !in 0..3 || !validSlots(candidate.extendedSlots())) continue
            matches.add(candidate)
        }
        if (matches.isEmpty()) return unknown(DungeonWaterBoardRules.UnknownReason.NO_CONFIRMED_SIGNATURE)
        if (matches.size != 1) return unknown(DungeonWaterBoardRules.UnknownReason.AMBIGUOUS_ROTATION)
        val match = matches[0]
        return DungeonWaterBoardRules.Detection(
            match.rotation(), match.patternIds()[0], match.extendedSlots(), null,
        )
    }

    fun validSlots(extendedSlots: String?): Boolean {
        if (extendedSlots == null || extendedSlots.length != 3) return false
        val seen = HashSet<Char>()
        for (slot in extendedSlots) if (slot !in '0'..'4' || !seen.add(slot)) return false
        return true
    }

    fun valveOrder(leverId: String?): Int = when (leverId) {
        "coal_block" -> 0
        "gold_block" -> 1
        "quartz_block" -> 2
        "diamond_block" -> 3
        "emerald_block" -> 4
        "hardened_clay" -> 5
        "water" -> 6
        else -> Int.MAX_VALUE
    }

    fun known(detection: DungeonWaterBoardRules.Detection): Boolean =
        detection.unknownReason() == null && detection.rotation() >= 0 && detection.patternId() >= 0

    fun unknown(reason: DungeonWaterBoardRules.UnknownReason): DungeonWaterBoardRules.Detection =
        DungeonWaterBoardRules.Detection(-1, -1, "", reason)
}
