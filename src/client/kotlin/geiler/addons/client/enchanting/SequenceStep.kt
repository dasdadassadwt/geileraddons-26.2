package geiler.addons.client.enchanting

/** One remembered value and the playable slots that currently contain it. */
class SequenceStep(index: Int, value: String?, slotIds: List<Int?>?) {
    private val stepIndex: Int
    private val stepValue: String
    private val playableSlotIds: List<Int>

    init {
        SequenceStep.validateIndex(index)
        stepIndex = index
        stepValue = SequenceStep.normalizeValue(value)
        playableSlotIds = SequenceStep.normalizeSlotIds(slotIds)
    }

    fun index(): Int = stepIndex

    fun value(): String = stepValue

    fun slotIds(): List<Int> = playableSlotIds

    fun containsSlot(slotId: Int): Boolean = SequenceStep.containsSlot(playableSlotIds, slotId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SequenceStep
            && stepIndex == other.stepIndex
            && stepValue == other.stepValue
            && playableSlotIds == other.playableSlotIds)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(stepIndex, stepValue, playableSlotIds)

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "SequenceStep",
        "index" to stepIndex,
        "value" to stepValue,
        "slotIds" to playableSlotIds,
    )

    companion object {
        @JvmStatic
        fun containsSlot(slotIds: List<Int>, slotId: Int): Boolean = slotIds.contains(slotId)

        @JvmStatic
        fun validateIndex(index: Int) {
            if (index < 0) throw IllegalArgumentException("index must be non-negative")
        }

        /** Match Java String.trim(), which strips UTF-16 code units at or below U+0020 only. */
        @JvmStatic
        fun normalizeValue(value: String?): String = value?.trim { it <= '\u0020' } ?: ""

        /** Keep only non-negative IDs, sort and deduplicate them, then expose a Java immutable copy. */
        @JvmStatic
        fun normalizeSlotIds(slotIds: List<Int?>?): List<Int> {
            val normalized = slotIds.orEmpty()
                .asSequence()
                .filterNotNull()
                .filter { it >= 0 }
                .distinct()
                .sorted()
                .toList()
            return java.util.List.copyOf(normalized)
        }
    }
}
