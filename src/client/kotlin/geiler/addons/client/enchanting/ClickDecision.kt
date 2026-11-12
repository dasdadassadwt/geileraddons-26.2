package geiler.addons.client.enchanting

/** Result of a local click observation; no network operation occurs in the pure layer. */
class ClickDecision(
    slotId: Int,
    recognized: Boolean,
    expected: Boolean,
    predicted: Boolean,
    visualStateChanged: Boolean,
    reason: String?,
) {
    private val decisionSlotId = slotId
    private val decisionRecognized = recognized
    private val decisionExpected = expected
    private val decisionPredicted = predicted
    private val decisionVisualStateChanged = visualStateChanged
    private val decisionReason = ExperimentTextSupport.emptyIfNull(reason)

    fun slotId(): Int = decisionSlotId

    fun recognized(): Boolean = decisionRecognized

    fun expected(): Boolean = decisionExpected

    fun predicted(): Boolean = decisionPredicted

    fun visualStateChanged(): Boolean = decisionVisualStateChanged

    fun reason(): String = decisionReason

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClickDecision
            && decisionSlotId == other.decisionSlotId
            && decisionRecognized == other.decisionRecognized
            && decisionExpected == other.decisionExpected
            && decisionPredicted == other.decisionPredicted
            && decisionVisualStateChanged == other.decisionVisualStateChanged
            && decisionReason == other.decisionReason)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        decisionSlotId,
        decisionRecognized,
        decisionExpected,
        decisionPredicted,
        decisionVisualStateChanged,
        decisionReason,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ClickDecision",
        "slotId" to decisionSlotId,
        "recognized" to decisionRecognized,
        "expected" to decisionExpected,
        "predicted" to decisionPredicted,
        "visualStateChanged" to decisionVisualStateChanged,
        "reason" to decisionReason,
    )
}
