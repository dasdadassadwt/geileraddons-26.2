package geiler.addons.client.enchanting

/** Renderer-independent state for one experiment board cell. */
class ExperimentCell(
    slotId: Int,
    value: String?,
    number: Int,
    revealed: Boolean,
    highlighted: Boolean,
    removed: Boolean,
) {
    private val cellSlotId = slotId
    private val cellValue: String?
    private val cellNumber = number
    private val cellRevealed = revealed
    private val cellHighlighted = highlighted
    private val cellRemoved = removed

    init {
        ExperimentCellLogic.validateSlotId(slotId)
        cellValue = ExperimentCellLogic.normalizeValue(value)
    }

    constructor(slotId: Int, value: String?, revealed: Boolean, highlighted: Boolean) :
        this(slotId, value, -1, revealed, highlighted, false)

    fun slotId(): Int = cellSlotId

    fun value(): String? = cellValue

    fun number(): Int = cellNumber

    fun revealed(): Boolean = cellRevealed

    fun highlighted(): Boolean = cellHighlighted

    fun removed(): Boolean = cellRemoved

    fun hasValue(): Boolean = ExperimentCellLogic.hasValue(cellValue)

    fun numericValue(): Int = ExperimentCellLogic.numericValue(cellValue, cellNumber)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExperimentCell
            && cellSlotId == other.cellSlotId
            && cellValue == other.cellValue
            && cellNumber == other.cellNumber
            && cellRevealed == other.cellRevealed
            && cellHighlighted == other.cellHighlighted
            && cellRemoved == other.cellRemoved)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        cellSlotId,
        cellValue,
        cellNumber,
        cellRevealed,
        cellHighlighted,
        cellRemoved,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ExperimentCell",
        "slotId" to cellSlotId,
        "value" to cellValue,
        "number" to cellNumber,
        "revealed" to cellRevealed,
        "highlighted" to cellHighlighted,
        "removed" to cellRemoved,
    )

    companion object {
        @JvmStatic
        fun token(slotId: Int, value: String?, highlighted: Boolean): ExperimentCell =
            ExperimentCellLogic.token(slotId, value, highlighted)

        @JvmStatic
        fun number(slotId: Int, number: Int): ExperimentCell = ExperimentCellLogic.number(slotId, number)
    }
}
