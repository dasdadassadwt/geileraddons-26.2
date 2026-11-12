package geiler.addons.client.enchanting

import java.util.Optional

/** Kotlin value normalization and factories behind the Java record boundary. */
object SolverViewSupport {
    @JvmStatic
    fun normalizeSequence(sequence: List<SequenceStep>?): List<SequenceStep> =
        java.util.List.copyOf(sequence ?: emptyList())

    @JvmStatic
    fun <T : Any> normalizeOptional(value: Optional<T>?): Optional<T> = value ?: Optional.empty()

    @JvmStatic
    fun normalizeSuperpairs(value: SuperpairsBoard.View?): SuperpairsBoard.View =
        value ?: SuperpairsBoard.View.empty()

    @JvmStatic
    fun idle(): SolverView = SolverView(
        null,
        ExperimentTier.UNKNOWN,
        ExperimentPhase.IDLE,
        -1,
        -1,
        emptyList(),
        0,
        0,
        0,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        SuperpairsBoard.View.empty(),
        Optional.empty(),
        false,
    )

    /** Returns the existing sequence tail without synthesizing unavailable future steps. */
    @JvmStatic
    fun upcoming(view: SolverView, maximum: Int): List<SequenceStep> {
        val sequence = view.sequence()
        val start = view.visualIndex()
        if (maximum <= 0 || start < 0 || start >= sequence.size) return emptyList()
        return sequence.subList(start, minOf(sequence.size, start + maximum))
    }
}
