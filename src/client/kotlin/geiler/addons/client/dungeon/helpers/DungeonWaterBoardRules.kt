package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Pure signature validation for the locally observed Water Board probes. */
@JvmSuppressWildcards
class DungeonWaterBoardRules private constructor() {
    companion object {
        /** Resolves one or more already selected room orientations from their loaded pattern and slot probes. */
        @JvmStatic
        fun detect(candidates: List<Candidate?>?): Detection = DungeonWaterBoardRulesCore.detect(candidates)

        @JvmStatic
        fun validSlots(extendedSlots: String?): Boolean = DungeonWaterBoardRulesCore.validSlots(extendedSlots)

        /** Odin's route resource uses this stable order for equal-time valve prompts. */
        @JvmStatic
        fun valveOrder(leverId: String?): Int = DungeonWaterBoardRulesCore.valveOrder(leverId)
    }

    enum class UnknownReason {
        INCOMPLETE_ROTATIONS,
        ACTIVE_ORIENTATION_UNAVAILABLE,
        UNLOADED_PROBES,
        NO_CONFIRMED_SIGNATURE,
        AMBIGUOUS_ROTATION,
    }

    class Candidate(
        rotation: Int,
        patternIds: List<Int>?,
        extendedSlots: String?,
        loaded: Boolean,
    ) {
        private val rotationValue = rotation
        private val patternIdsValue: List<Int> = java.util.List.copyOf(patternIds ?: emptyList())
        private val extendedSlotsValue = extendedSlots ?: ""
        private val loadedValue = loaded

        fun rotation(): Int = rotationValue
        fun patternIds(): List<Int> = patternIdsValue
        fun extendedSlots(): String = extendedSlotsValue
        fun loaded(): Boolean = loadedValue

        override fun equals(other: Any?): Boolean =
            this === other || (other is Candidate && rotationValue == other.rotationValue &&
                patternIdsValue == other.patternIdsValue && extendedSlotsValue == other.extendedSlotsValue &&
                loadedValue == other.loadedValue)

        override fun hashCode(): Int {
            var result = rotationValue
            result = 31 * result + patternIdsValue.hashCode()
            result = 31 * result + extendedSlotsValue.hashCode()
            result = 31 * result + java.lang.Boolean.hashCode(loadedValue)
            return result
        }

        override fun toString(): String =
            "Candidate[rotation=$rotationValue, patternIds=$patternIdsValue, extendedSlots=$extendedSlotsValue, loaded=$loadedValue]"
    }

    class Detection(
        rotation: Int,
        patternId: Int,
        extendedSlots: String?,
        unknownReason: UnknownReason?,
    ) {
        private val rotationValue = rotation
        private val patternIdValue = patternId
        private val extendedSlotsValue = extendedSlots ?: ""
        private val unknownReasonValue = unknownReason

        fun rotation(): Int = rotationValue
        fun patternId(): Int = patternIdValue
        fun extendedSlots(): String = extendedSlotsValue
        fun unknownReason(): UnknownReason? = unknownReasonValue
        fun known(): Boolean = DungeonWaterBoardRulesCore.known(this)

        override fun equals(other: Any?): Boolean =
            this === other || (other is Detection && rotationValue == other.rotationValue &&
                patternIdValue == other.patternIdValue && extendedSlotsValue == other.extendedSlotsValue &&
                unknownReasonValue == other.unknownReasonValue)

        override fun hashCode(): Int {
            var result = rotationValue
            result = 31 * result + patternIdValue
            result = 31 * result + extendedSlotsValue.hashCode()
            result = 31 * result + (unknownReasonValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Detection[rotation=$rotationValue, patternId=$patternIdValue, extendedSlots=$extendedSlotsValue, unknownReason=$unknownReasonValue]"
    }
}
