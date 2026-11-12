package geiler.addons.client.enchanting

/** One ordered Chronomatron container event. */
class ChronomatronEvent(
    kind: Kind?,
    slotId: Int,
    value: String?,
    highlighted: Boolean,
    status: String?,
) {
    private val eventKind: Kind
    private val eventSlotId = slotId
    private val eventValue: String?
    private val eventHighlighted = highlighted
    private val eventStatus: String

    init {
        ChronomatronEventLogic.validate(kind, slotId)
        eventKind = kind!!
        eventValue = ChronomatronEventLogic.normalizeValue(value)
        eventStatus = ChronomatronEventLogic.normalizeStatus(status)
    }

    fun kind(): Kind = eventKind

    fun slotId(): Int = eventSlotId

    fun value(): String? = eventValue

    fun highlighted(): Boolean = eventHighlighted

    fun status(): String = eventStatus

    override fun equals(other: Any?): Boolean =
        this === other || (other is ChronomatronEvent
            && eventKind == other.eventKind
            && eventSlotId == other.eventSlotId
            && eventValue == other.eventValue
            && eventHighlighted == other.eventHighlighted
            && eventStatus == other.eventStatus)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        eventKind,
        eventSlotId,
        eventValue,
        eventHighlighted,
        eventStatus,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ChronomatronEvent",
        "kind" to eventKind,
        "slotId" to eventSlotId,
        "value" to eventValue,
        "highlighted" to eventHighlighted,
        "status" to eventStatus,
    )

    enum class Kind {
        BOARD,
        STATUS,
    }

    companion object {
        @JvmStatic
        fun board(slotId: Int, value: String?, highlighted: Boolean): ChronomatronEvent =
            ChronomatronEventLogic.board(slotId, value, highlighted)

        @JvmStatic
        fun status(status: String?): ChronomatronEvent = ChronomatronEventLogic.status(status)
    }
}
