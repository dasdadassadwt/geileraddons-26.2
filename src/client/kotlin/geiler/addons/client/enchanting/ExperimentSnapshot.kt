package geiler.addons.client.enchanting

import java.util.Optional

/** Immutable input boundary between Minecraft/container code and the pure solver engine. */
class ExperimentSnapshot(
    title: String?,
    status: String?,
    cells: List<ExperimentCell>?,
    ultrasequencerPaneColor: String?,
    revision: Long,
    statusEvent: Boolean,
) {
    private val snapshotTitle = ExperimentSnapshotLogic.normalizeText(title)
    private val snapshotStatus = ExperimentSnapshotLogic.normalizeText(status)
    private val snapshotCells = ExperimentSnapshotLogic.sortedImmutableCells(cells)
    private val paneColor = ExperimentSnapshotLogic.normalizePaneColor(ultrasequencerPaneColor)
    private val snapshotRevision = revision
    private val snapshotStatusEvent = statusEvent

    constructor(title: String?, status: String?, cells: List<ExperimentCell>?) :
        this(title, status, cells, null, 0L, false)

    constructor(
        title: String?,
        status: String?,
        cells: List<ExperimentCell>?,
        ultrasequencerPaneColor: String?,
        revision: Long,
    ) : this(title, status, cells, ultrasequencerPaneColor, revision, false)

    /** Compatibility constructor for older offline fixtures; progress fields are ignored. */
    @Deprecated("Progress fields are ignored; cursors advance from ordered events and accepted clicks")
    constructor(
        title: String?,
        status: String?,
        cells: List<ExperimentCell>?,
        ignoredSequenceLength: Int,
        ignoredCompletedRounds: Int,
        ignoredAuthoritativeProgress: Int,
        revision: Long,
    ) : this(title, status, cells, null, revision, false)

    fun title(): String = snapshotTitle

    fun status(): String = snapshotStatus

    fun cells(): List<ExperimentCell> = snapshotCells

    fun ultrasequencerPaneColor(): String? = paneColor

    fun revision(): Long = snapshotRevision

    fun statusEvent(): Boolean = snapshotStatusEvent

    fun type(): ExperimentType? = ExperimentSnapshotLogic.type(snapshotTitle)

    fun tier(): ExperimentTier = ExperimentSnapshotLogic.tier(snapshotTitle)

    fun phase(): ExperimentPhase = ExperimentSnapshotLogic.phase(snapshotTitle, snapshotStatus)

    fun highlightedCell(): Optional<ExperimentCell> = ExperimentSnapshotLogic.firstHighlightedValue(snapshotCells)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExperimentSnapshot
            && snapshotTitle == other.snapshotTitle
            && snapshotStatus == other.snapshotStatus
            && snapshotCells == other.snapshotCells
            && paneColor == other.paneColor
            && snapshotRevision == other.snapshotRevision
            && snapshotStatusEvent == other.snapshotStatusEvent)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        snapshotTitle,
        snapshotStatus,
        snapshotCells,
        paneColor,
        snapshotRevision,
        snapshotStatusEvent,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ExperimentSnapshot",
        "title" to snapshotTitle,
        "status" to snapshotStatus,
        "cells" to snapshotCells,
        "ultrasequencerPaneColor" to paneColor,
        "revision" to snapshotRevision,
        "statusEvent" to snapshotStatusEvent,
    )
}
