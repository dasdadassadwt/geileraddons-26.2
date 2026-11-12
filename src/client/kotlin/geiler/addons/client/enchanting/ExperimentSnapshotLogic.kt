package geiler.addons.client.enchanting

import java.util.ArrayList
import java.util.Locale
import java.util.Optional

/** Kotlin implementation of the Java record's normalization and derived solver metadata. */
object ExperimentSnapshotLogic {
    /** Matches Java String.trim(), which removes only characters at or below U+0020. */
    @JvmStatic
    fun normalizeText(value: String?): String = value?.trim { it <= '\u0020' } ?: ""

    /** Matches Java String.trim() and toLowerCase(Locale.ROOT), including the null case. */
    @JvmStatic
    fun normalizePaneColor(value: String?): String? =
        value?.trim { it <= '\u0020' }?.lowercase(Locale.ROOT)

    /** Copies, sorts by slot id, and returns the same null-rejecting immutable list as List.copyOf. */
    @JvmStatic
    fun sortedImmutableCells(
        cells: kotlin.collections.List<ExperimentCell>?
    ): kotlin.collections.List<ExperimentCell> {
        val copy = ArrayList(cells ?: emptyList())
        copy.sortWith(compareBy { it.slotId() })
        return java.util.List.copyOf(copy)
    }

    @JvmStatic
    fun type(title: String?): ExperimentType? = ExperimentType.fromTitle(title).orElse(null)

    @JvmStatic
    fun tier(title: String?): ExperimentTier =
        ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN)

    @JvmStatic
    fun phase(title: String?, status: String?): ExperimentPhase =
        ExperimentPhase.detect(type(title), status)

    @JvmStatic
    fun firstHighlightedValue(
        cells: kotlin.collections.List<ExperimentCell>
    ): Optional<ExperimentCell> =
        Optional.ofNullable(cells.firstOrNull { it.highlighted() && it.hasValue() })
}
