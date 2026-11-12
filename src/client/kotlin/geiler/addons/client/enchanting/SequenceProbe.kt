package geiler.addons.client.enchanting

/** Diagnostic readout of the Ultrasequencer model, carried on the solver view. */
class SequenceProbe(
    lastStatus: String?,
    modelState: String?,
    openingClickOwed: Boolean,
    nextSlotId: Int,
    solveWindowOpen: Boolean,
) {
    private val statusText = ExperimentTextSupport.emptyIfNull(lastStatus)
    private val modelStateText = ExperimentTextSupport.emptyIfNull(modelState)
    private val owesOpeningClick = openingClickOwed
    private val nextSlot = nextSlotId
    private val solveWindow = solveWindowOpen

    fun lastStatus(): String = statusText

    fun modelState(): String = modelStateText

    fun openingClickOwed(): Boolean = owesOpeningClick

    fun nextSlotId(): Int = nextSlot

    fun solveWindowOpen(): Boolean = solveWindow

    fun describe(): String = SequenceProbeLogic.describe(this)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SequenceProbe
            && statusText == other.statusText
            && modelStateText == other.modelStateText
            && owesOpeningClick == other.owesOpeningClick
            && nextSlot == other.nextSlot
            && solveWindow == other.solveWindow)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        statusText,
        modelStateText,
        owesOpeningClick,
        nextSlot,
        solveWindow,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "SequenceProbe",
        "lastStatus" to statusText,
        "modelState" to modelStateText,
        "openingClickOwed" to owesOpeningClick,
        "nextSlotId" to nextSlot,
        "solveWindowOpen" to solveWindow,
    )

    companion object {
        @JvmField
        val NONE = SequenceProbe("", "", false, -1, false)
    }
}
