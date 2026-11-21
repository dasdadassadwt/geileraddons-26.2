package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideSegments
import java.util.ArrayList
import java.util.HashSet
import java.util.Locale
import kotlin.jvm.JvmRecord

private const val MAX_COMPLETED_ROWS = 32

/** Client-observed run splits using the existing floor-specific Guide segment vocabulary. */
class DungeonSplitTracker {
    private var activeFloor: DungeonFloor? = null
    private var trackerRunning = false
    private var trackerComplete = false
    private var currentSegmentId: String? = null
    private var currentStartedNanos = 0L
    private var currentStartedTick = 0L
    private var currentStartedPingPackets = 0L
    private var currentStartedServerTicks = 0L
    private var runStartedNanos = 0L
    private var runStartedTick = 0L
    private var runStartedPingPackets = 0L
    private var runStartedServerTicks = 0L
    private var campStartedNanos = 0L
    private var campStartedTick = 0L
    private var campStartedPingPackets = 0L
    private var campStartedServerTicks = 0L
    private var portalStartedNanos = 0L
    private var portalStartedTick = 0L
    private var portalStartedPingPackets = 0L
    private var portalStartedServerTicks = 0L
    private var bloodComplete = false
    private var campStarted = false
    private var campComplete = false
    private var portalStarted = false
    private var portalComplete = false
    private val finished = ArrayList<Split>()
    private val newlyFinished = ArrayList<Split>()
    private val cueMarkers = ArrayList<CueMarker>()
    private val observedCueIds = HashSet<String>()
    private val terminalSections = DungeonTerminalSectionTracker()

    /** A run starts only from a positively identified Entry event. */
    fun observe(eventFloor: DungeonFloor?, segmentId: String?, nowNanos: Long, clientTick: Long): Boolean =
        observe(eventFloor, segmentId, nowNanos, clientTick, 0L)

    /** Server ping packets are kept as a separate raw event count from local client ticks. */
    fun observe(
        eventFloor: DungeonFloor?,
        segmentId: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = observe(eventFloor, segmentId, nowNanos, clientTick, serverPingPackets, 0L)

    fun observe(
        eventFloor: DungeonFloor?,
        segmentId: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (eventFloor == null || segmentId.isNullOrBlank()) return false
        if (segmentId == "ENTRY") {
            begin(eventFloor, nowNanos, clientTick, serverPingPackets, serverTickCount)
            return true
        }
        if (!trackerRunning || activeFloor !== eventFloor || trackerComplete) return false

        val runFloor = activeFloor ?: return false
        val order = splitOrder(runFloor)
        val incomingIndex = indexOf(order, segmentId)
        val currentIndex = indexOf(order, currentSegmentId)
        if (incomingIndex < 0 || currentIndex < 0 || incomingIndex <= currentIndex) return false

        retainFinished(
            Split(
                currentSegmentId,
                intervalLabel(runFloor, currentSegmentId),
                maxOf(0L, nowNanos - currentStartedNanos),
                maxOf(0L, clientTick - currentStartedTick),
                maxOf(0L, serverPingPackets - currentStartedPingPackets),
                maxOf(0L, serverTickCount - currentStartedServerTicks),
                true,
            ),
        )
        if (segmentId == "BLOOD_OPEN") {
            retainFinished(
                Split(
                    "BLOOD",
                    "Blood",
                    maxOf(0L, nowNanos - runStartedNanos),
                    maxOf(0L, clientTick - runStartedTick),
                    maxOf(0L, serverPingPackets - runStartedPingPackets),
                    maxOf(0L, serverTickCount - runStartedServerTicks),
                    true,
                ),
            )
            bloodComplete = true
            campStarted = true
            campStartedNanos = nowNanos
            campStartedTick = clientTick
            campStartedPingPackets = serverPingPackets
            campStartedServerTicks = serverTickCount
        }
        if (segmentId == "BLOOD_CLEAR" && campStarted && !campComplete) {
            retainFinished(
                Split(
                    "CAMP",
                    "Camp",
                    maxOf(0L, nowNanos - campStartedNanos),
                    maxOf(0L, clientTick - campStartedTick),
                    maxOf(0L, serverPingPackets - campStartedPingPackets),
                    maxOf(0L, serverTickCount - campStartedServerTicks),
                    true,
                ),
            )
            campComplete = true
            portalStarted = true
            portalStartedNanos = nowNanos
            portalStartedTick = clientTick
            portalStartedPingPackets = serverPingPackets
            portalStartedServerTicks = serverTickCount
        }
        // Use the floor's first opening boss dialogue as the client-observed entry cue.
        if (portalStarted && !portalComplete && (segmentId == "BOSS_ENTRY" || isBossStage(segmentId))) {
            retainFinished(
                Split(
                    "PORTAL",
                    "Portal",
                    maxOf(0L, nowNanos - portalStartedNanos),
                    maxOf(0L, clientTick - portalStartedTick),
                    maxOf(0L, serverPingPackets - portalStartedPingPackets),
                    maxOf(0L, serverTickCount - portalStartedServerTicks),
                    true,
                ),
            )
            portalComplete = true
        }
        if (segmentId == "COMPLETE") {
            retainFinished(
                Split(
                    segmentId,
                    intervalLabel(runFloor, segmentId),
                    maxOf(0L, nowNanos - runStartedNanos),
                    maxOf(0L, clientTick - runStartedTick),
                    maxOf(0L, serverPingPackets - runStartedPingPackets),
                    maxOf(0L, serverTickCount - runStartedServerTicks),
                    true,
                ),
            )
            currentSegmentId = null
            trackerComplete = true
            return true
        }
        currentSegmentId = segmentId
        currentStartedNanos = nowNanos
        currentStartedTick = clientTick
        currentStartedPingPackets = serverPingPackets
        currentStartedServerTicks = serverTickCount
        return true
    }

    /** Consumes a client-observed phase line through the existing floor-specific milestone table. */
    fun observeChat(eventFloor: DungeonFloor?, plainMessage: String?, nowNanos: Long, clientTick: Long): Boolean =
        observeChat(eventFloor, plainMessage, nowNanos, clientTick, 0L)

    fun observeChat(
        eventFloor: DungeonFloor?,
        plainMessage: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = observeChat(eventFloor, plainMessage, nowNanos, clientTick, serverPingPackets, 0L)

    fun observeChat(
        eventFloor: DungeonFloor?,
        plainMessage: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (eventFloor == null || plainMessage == null) return false
        val event = DungeonGuideSegments.fromChat(eventFloor, plainMessage)
        var changed = event != null && observeEvent(eventFloor, event, nowNanos, clientTick,
            serverPingPackets, serverTickCount)
        if (eventFloor.number() == 7 && trackerRunning && activeFloor === eventFloor &&
            currentSegmentId == "TERMINALS" && terminalSections.active()
        ) {
            changed = appendTerminalClosures(
                terminalSections.observeChat(plainMessage, nowNanos, clientTick, serverPingPackets, serverTickCount),
            ) || changed
        }
        return changed
    }

    /** Starts the Watcher fight interval from the client-observed Watcher entity name. */
    fun observeWatcherName(
        eventFloor: DungeonFloor?,
        observedName: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = observeWatcherName(eventFloor, observedName, nowNanos, clientTick, serverPingPackets, 0L)

    fun observeWatcherName(
        eventFloor: DungeonFloor?,
        observedName: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (observedName == null || !observedName.lowercase(Locale.ROOT).contains("the watcher")) return false
        if (!trackerRunning || activeFloor !== eventFloor || trackerComplete || currentSegmentId != "BLOOD_OPEN")
            return false
        return observe(eventFloor, "WATCHER", nowNanos, clientTick, serverPingPackets, serverTickCount)
    }

    /** Routes an ordered phase or an independent, source-backed cue without inventing cue ordering. */
    fun observeEvent(
        eventFloor: DungeonFloor?,
        event: DungeonGuideSegments.Event?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = observeEvent(eventFloor, event, nowNanos, clientTick, serverPingPackets, 0L)

    fun observeEvent(
        eventFloor: DungeonFloor?,
        event: DungeonGuideSegments.Event?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (event == null) return false
        if (event.independentCue) return observeCue(eventFloor, event, nowNanos, clientTick)
        var changed = observe(eventFloor, event.segmentId, nowNanos, clientTick, serverPingPackets, serverTickCount)
        if (eventFloor != null && eventFloor.number() == 7) {
            if (event.segmentId == "TERMINALS" && trackerRunning && activeFloor === eventFloor &&
                currentSegmentId == "TERMINALS" && !terminalSections.active()
            ) {
                changed = terminalSections.begin(nowNanos, clientTick, serverPingPackets, serverTickCount) || changed
            } else if (event.segmentId == "GOLDOR") {
                changed = appendTerminalClosures(
                    terminalSections.stopAtCore(nowNanos, clientTick, serverPingPackets, serverTickCount),
                ) || changed
            }
        }
        return changed
    }

    private fun appendTerminalClosures(changed: Boolean): Boolean {
        var result = changed
        for (reading in terminalSections.drainNewlyFinished()) {
            retainFinished(
                Split(
                    reading.id,
                    reading.label,
                    reading.elapsedNanos,
                    reading.elapsedTicks,
                    reading.elapsedPingPackets,
                    reading.elapsedServerTicks,
                    reading.complete,
                ),
            )
            result = true
        }
        return result
    }

    /** Bounds display history while retaining every closure until its PB consumer drains the queue. */
    private fun retainFinished(split: Split) {
        finished.add(split)
        if (finished.size > MAX_COMPLETED_ROWS) finished.removeAt(0)
        newlyFinished.add(split)
    }

    /** Stores a chat cue once per run, but never turns it into a timed phase interval or PB. */
    fun observeCue(
        eventFloor: DungeonFloor?,
        event: DungeonGuideSegments.Event?,
        nowNanos: Long,
        clientTick: Long,
    ): Boolean {
        if (eventFloor == null || event == null || !event.independentCue ||
            !DungeonGuideSegments.isIndependentCue(eventFloor, event.segmentId) ||
            !trackerRunning || activeFloor !== eventFloor || trackerComplete ||
            !observedCueIds.add(event.segmentId)
        ) return false
        val runFloor = activeFloor ?: return false
        cueMarkers.add(
            CueMarker(
                event.segmentId,
                DungeonGuideSegments.label(runFloor, event.segmentId),
                event.source,
                maxOf(0L, nowNanos - runStartedNanos),
                maxOf(0L, clientTick - runStartedTick),
                cueMarkers.size + 1,
            ),
        )
        return true
    }

    /** Allows separately observed stable boss phase markers to advance a split without chat parsing. */
    fun observePhase(eventFloor: DungeonFloor?, segmentId: String?, nowNanos: Long, clientTick: Long): Boolean =
        observePhase(eventFloor, segmentId, nowNanos, clientTick, 0L)

    fun observePhase(
        eventFloor: DungeonFloor?,
        segmentId: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = observePhase(eventFloor, segmentId, nowNanos, clientTick, serverPingPackets, 0L)

    fun observePhase(
        eventFloor: DungeonFloor?,
        segmentId: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (!DungeonGuideSegments.contains(eventFloor, segmentId)) return false
        return observe(eventFloor, segmentId, nowNanos, clientTick, serverPingPackets, serverTickCount)
    }

    fun tickContext(currentFloor: DungeonFloor?) {
        if (currentFloor == null || activeFloor != null && currentFloor != activeFloor) reset()
    }

    fun begin(runFloor: DungeonFloor?, nowNanos: Long, clientTick: Long) {
        begin(runFloor, nowNanos, clientTick, 0L)
    }

    fun begin(runFloor: DungeonFloor?, nowNanos: Long, clientTick: Long, serverPingPackets: Long) {
        begin(runFloor, nowNanos, clientTick, serverPingPackets, 0L)
    }

    fun begin(
        runFloor: DungeonFloor?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ) {
        reset()
        if (runFloor == null) return
        activeFloor = runFloor
        trackerRunning = true
        currentSegmentId = "ENTRY"
        currentStartedNanos = nowNanos
        currentStartedTick = clientTick
        currentStartedPingPackets = serverPingPackets
        currentStartedServerTicks = serverTickCount
        runStartedNanos = nowNanos
        runStartedTick = clientTick
        runStartedPingPackets = serverPingPackets
        runStartedServerTicks = serverTickCount
    }

    fun reset() {
        activeFloor = null
        trackerRunning = false
        trackerComplete = false
        currentSegmentId = null
        currentStartedNanos = 0L
        currentStartedTick = 0L
        currentStartedPingPackets = 0L
        currentStartedServerTicks = 0L
        runStartedNanos = 0L
        runStartedTick = 0L
        runStartedPingPackets = 0L
        runStartedServerTicks = 0L
        campStartedNanos = 0L
        campStartedTick = 0L
        campStartedPingPackets = 0L
        campStartedServerTicks = 0L
        portalStartedNanos = 0L
        portalStartedTick = 0L
        portalStartedPingPackets = 0L
        portalStartedServerTicks = 0L
        bloodComplete = false
        campStarted = false
        campComplete = false
        portalStarted = false
        portalComplete = false
        finished.clear()
        newlyFinished.clear()
        cueMarkers.clear()
        observedCueIds.clear()
        terminalSections.reset()
    }

    /** Snapshots preserve recent completions and keep all live phase and aggregate rows separate. */
    fun snapshot(nowNanos: Long, clientTick: Long): List<Split> =
        snapshot(nowNanos, clientTick, 0L)

    fun snapshot(nowNanos: Long, clientTick: Long, serverPingPackets: Long): List<Split> =
        snapshot(nowNanos, clientTick, serverPingPackets, 0L)

    fun snapshot(
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): List<Split> {
        val rows = ArrayList(finished)
        val runFloor = activeFloor
        val currentId = currentSegmentId
        if (trackerRunning && currentId != null) {
            rows.add(
                Split(
                    currentId,
                    intervalLabel(runFloor, currentId),
                    maxOf(0L, nowNanos - currentStartedNanos),
                    maxOf(0L, clientTick - currentStartedTick),
                    maxOf(0L, serverPingPackets - currentStartedPingPackets),
                    maxOf(0L, serverTickCount - currentStartedServerTicks),
                    false,
                ),
            )
        }
        if (trackerRunning && !trackerComplete && !bloodComplete) {
            rows.add(
                Split(
                    "BLOOD",
                    "Blood",
                    maxOf(0L, nowNanos - runStartedNanos),
                    maxOf(0L, clientTick - runStartedTick),
                    maxOf(0L, serverPingPackets - runStartedPingPackets),
                    maxOf(0L, serverTickCount - runStartedServerTicks),
                    false,
                ),
            )
        }
        if (trackerRunning && !trackerComplete && campStarted && !campComplete) {
            rows.add(
                Split(
                    "CAMP",
                    "Camp",
                    maxOf(0L, nowNanos - campStartedNanos),
                    maxOf(0L, clientTick - campStartedTick),
                    maxOf(0L, serverPingPackets - campStartedPingPackets),
                    maxOf(0L, serverTickCount - campStartedServerTicks),
                    false,
                ),
            )
        }
        if (trackerRunning && !trackerComplete && portalStarted && !portalComplete) {
            rows.add(
                Split(
                    "PORTAL",
                    "Portal",
                    maxOf(0L, nowNanos - portalStartedNanos),
                    maxOf(0L, clientTick - portalStartedTick),
                    maxOf(0L, serverPingPackets - portalStartedPingPackets),
                    maxOf(0L, serverTickCount - portalStartedServerTicks),
                    false,
                ),
            )
        }
        for (reading in terminalSections.snapshot(nowNanos, clientTick, serverPingPackets, serverTickCount)) {
            if (!reading.complete) {
                rows.add(
                    Split(
                        reading.id,
                        reading.label,
                        reading.elapsedNanos,
                        reading.elapsedTicks,
                        reading.elapsedPingPackets,
                        reading.elapsedServerTicks,
                        false,
                    ),
                )
            }
        }
        if (trackerRunning && !trackerComplete) {
            rows.add(
                Split(
                    "RUN",
                    "Run",
                    maxOf(0L, nowNanos - runStartedNanos),
                    maxOf(0L, clientTick - runStartedTick),
                    maxOf(0L, serverPingPackets - runStartedPingPackets),
                    maxOf(0L, serverTickCount - runStartedServerTicks),
                    false,
                ),
            )
        }
        return java.util.List.copyOf(rows)
    }

    /** Returns every closed split since the previous drain, independent of the bounded display window. */
    fun drainNewlyFinished(): List<Split> {
        if (newlyFinished.isEmpty()) return java.util.List.of()
        val result = java.util.List.copyOf(newlyFinished)
        newlyFinished.clear()
        return result
    }

    /** Independent cues are returned in the order the client observed them; they have no inferred interval. */
    fun cueMarkers(): List<CueMarker> = java.util.List.copyOf(cueMarkers)

    fun floor(): DungeonFloor? = activeFloor
    fun currentId(): String? = currentSegmentId
    fun running(): Boolean = trackerRunning
    fun complete(): Boolean = trackerComplete

    @JvmRecord
    data class Split(
        val id: String?,
        val label: String?,
        val elapsedNanos: Long,
        val elapsedTicks: Long,
        val elapsedPingPackets: Long,
        val elapsedServerTicks: Long,
        val complete: Boolean,
    ) {
        constructor(
            id: String?,
            label: String?,
            elapsedNanos: Long,
            elapsedTicks: Long,
            elapsedPingPackets: Long,
            complete: Boolean,
        ) : this(id, label, elapsedNanos, elapsedTicks, elapsedPingPackets, 0L, complete)

        override fun toString() =
            "Split[id=$id, label=$label, elapsedNanos=$elapsedNanos, elapsedTicks=$elapsedTicks, " +
                "elapsedPingPackets=$elapsedPingPackets, elapsedServerTicks=$elapsedServerTicks, complete=$complete]"
    }

    @JvmRecord
    data class CueMarker(
        val id: String?,
        val label: String?,
        val source: String?,
        val elapsedNanos: Long,
        val elapsedTicks: Long,
        val observedOrder: Int,
    ) {
        override fun toString() =
            "CueMarker[id=$id, label=$label, source=$source, elapsedNanos=$elapsedNanos, " +
                "elapsedTicks=$elapsedTicks, observedOrder=$observedOrder]"
    }
}

private fun indexOf(segments: List<DungeonGuideSegments.Segment>, id: String?): Int {
    for (index in segments.indices) if (segments[index].id == id) return index
    return -1
}

private fun isBossStage(id: String?): Boolean = id != null && when (id) {
    "ENTRY", "CLEAR", "BLOOD_OPEN", "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE" -> false
    else -> true
}

private fun splitOrder(floor: DungeonFloor): List<DungeonGuideSegments.Segment> {
    val source = DungeonGuideSegments.builtIn(floor)
    val result = ArrayList<DungeonGuideSegments.Segment>(source.size + 1)
    for (segment in source) {
        result.add(segment)
        if (segment.id == "BLOOD_OPEN") result.add(DungeonGuideSegments.Segment("WATCHER", "Watcher"))
    }
    return java.util.List.copyOf(result)
}

private fun intervalLabel(floor: DungeonFloor?, id: String?): String? = when (id) {
    "CLEAR" -> "Blood"
    "BLOOD_OPEN" -> "Watcher Dialog"
    "WATCHER" -> "Watcher"
    "BLOOD_CLEAR" -> "Boss Entry"
    else -> DungeonGuideSegments.label(floor, id)
}
