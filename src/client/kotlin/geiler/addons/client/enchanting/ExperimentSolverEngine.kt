package geiler.addons.client.enchanting

/** Shared facade for the Kotlin experiment solver engine. */
class ExperimentSolverEngine {
    class Configuration(serumsConsumed: Int) {
        private val serumCount = ExperimentMilestoneSupport.normalizeSerums(serumsConsumed)

        fun serumsConsumed(): Int = serumCount

        override fun equals(other: Any?): Boolean =
            this === other || (other is Configuration && serumCount == other.serumCount)

        override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(serumCount)

        override fun toString(): String = ExperimentValueSemantics.recordString(
            "Configuration",
            "serumsConsumed" to serumCount,
        )

        companion object {
            @JvmStatic
            fun defaults(): Configuration = Configuration(0)
        }
    }

    private val logic: ExperimentSolverEngineLogic

    constructor() : this(Configuration.defaults())

    constructor(configuration: Configuration?) {
        logic = ExperimentSolverEngineLogic(configuration)
    }

    fun configuration(): Configuration = logic.configuration()

    fun configure(configuration: Configuration?) {
        logic.configure(configuration)
    }

    /** Consumes a new server/container snapshot and returns the complete immutable render view. */
    fun observe(snapshot: ExperimentSnapshot?): SolverView = logic.observe(snapshot)

    /** Consumes a snapshot together with its ordered Chronomatron event, when present. */
    fun observe(snapshot: ExperimentSnapshot?, chronomatronEvent: ChronomatronEvent?): SolverView =
        logic.observe(snapshot, chronomatronEvent)

    /** Records a local click; sequence progress advances only after server-confirmed state. */
    fun onClick(slotId: Int): ClickDecision = logic.onClick(slotId)

    /** Confirms a click after vanilla dispatched it; ordinary menu clicks may have no item diff. */
    fun confirmClick(slotId: Int): ClickDecision = logic.confirmClick(slotId)

    /** Confirms only the sequence index that was validated before vanilla received the click. */
    fun confirmClick(expectedSequenceIndex: Int, slotId: Int): ClickDecision =
        logic.confirmClick(expectedSequenceIndex, slotId)

    /** Number of accepted positions in the current sequence, including the final Ultra click. */
    fun acceptedSequencePosition(): Int = logic.acceptedSequencePosition()

    fun reset(): SolverView = logic.reset()

    /** Alias for lifecycle adapters that receive a disconnect rather than a screen close. */
    fun onDisconnect(): SolverView = logic.onDisconnect()

    fun view(): SolverView = logic.view()

    /** Inventory mutations, unlike rendering, can signal an Ultrasequencer round boundary. */
    fun markUltrasequencerDirty(paneColors: List<String>?) {
        logic.markUltrasequencerDirty(paneColors)
    }
}
