package geiler.addons.client.dungeon.helpers

import java.util.function.BiPredicate
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Pure fail-closed selection and ordering rules for the display-only Blaze target overlay. */
@JvmSuppressWildcards
class DungeonBlazeRules private constructor() {
    enum class Position { UNSET, LOWER, HIGHER }
    enum class Source { ARMOR_STAND_NAMEPLATE, BLAZE_ENTITY }

    @JvmRecord
    data class Candidate(
        val stableId: String?,
        val health: Int,
        val source: Source?,
        val alive: Boolean,
        val loaded: Boolean,
        val insideRoom: Boolean,
        val blazeBodyResolved: Boolean,
    ) {
        override fun toString(): String =
            "Candidate[stableId=$stableId, health=$health, source=$source, alive=$alive, loaded=$loaded, " +
                "insideRoom=$insideRoom, blazeBodyResolved=$blazeBodyResolved]"
    }

    companion object {
        const val MAX_ORDER_LINKS = 3

        /** Checks the complete horizontal room footprint without requesting any chunk. */
        @JvmStatic
        fun allRoomChunksLoaded(
            baseX: Int,
            baseZ: Int,
            roomSize: Int,
            isChunkLoaded: BiPredicate<Int, Int>?,
        ): Boolean = DungeonBlazeRulesLogic.allRoomChunksLoaded(baseX, baseZ, roomSize, isChunkLoaded)

        /**
         * Selects only targets from a complete observation. Armor-stand health labels take precedence
         * over direct Blaze names to avoid double-counting the same mob; they are accepted only after a
         * caller has resolved the label to a real Blaze body.
         */
        @JvmStatic
        fun orderTargets(observed: List<Candidate?>?, roomComplete: Boolean, position: Position?): List<Candidate> =
            DungeonBlazeRulesLogic.orderTargets(observed, roomComplete, position)

        @JvmStatic
        fun parsePosition(value: String?): Position = DungeonBlazeRulesLogic.parsePosition(value)

        /** Exact catalog room names are sufficient to determine the puzzle's ordering orientation. */
        @JvmStatic
        fun positionForRoomName(roomName: String?): Position = DungeonBlazeRulesLogic.positionForRoomName(roomName)

        /** Uses exact room-catalog orientation when available, and the saved choice only as fallback. */
        @JvmStatic
        fun resolvePosition(roomName: String?, configuredFallback: String?): Position =
            DungeonBlazeRulesLogic.resolvePosition(roomName, configuredFallback)

        /** Distinguishes missing orientation from an empty target order without prompting known rooms. */
        @JvmStatic
        fun orderUnavailableMessage(roomName: String?, configuredFallback: String?): String =
            DungeonBlazeRulesLogic.orderUnavailableMessage(roomName, configuredFallback)

        /** Returns the number of consecutive target links allowed for this display mode. */
        @JvmStatic
        fun orderLinkCount(ordered: List<Candidate?>?, position: Position?): Int =
            DungeonBlazeRulesLogic.orderLinkCount(ordered, position)
    }
}

// Preserve the component-call shape used by Kotlin helpers while the Java ABI remains a record.
internal fun DungeonBlazeRules.Candidate.stableId(): String? = stableId
internal fun DungeonBlazeRules.Candidate.health(): Int = health
internal fun DungeonBlazeRules.Candidate.source(): DungeonBlazeRules.Source? = source
internal fun DungeonBlazeRules.Candidate.alive(): Boolean = alive
internal fun DungeonBlazeRules.Candidate.loaded(): Boolean = loaded
internal fun DungeonBlazeRules.Candidate.insideRoom(): Boolean = insideRoom
internal fun DungeonBlazeRules.Candidate.blazeBodyResolved(): Boolean = blazeBodyResolved
