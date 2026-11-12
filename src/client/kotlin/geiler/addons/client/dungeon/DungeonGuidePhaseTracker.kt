package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.ArrayList
import kotlin.jvm.JvmStatic

/** Client-session dungeon stage with an explicit manual override and visible evidence. */
class DungeonGuidePhaseTracker {
    private var floorValue: DungeonFloor? = null
    private var phaseValue = "ENTRY"
    private var evidenceValue = "Waiting for dungeon events"
    private var manualValue = false
    private var phaseKnown = false
    private var enteredNanos = 0L

    fun setFloor(floor: DungeonFloor?, nowNanos: Long) {
        if (floorValue === floor) return
        floorValue = floor
        phaseValue = "ENTRY"
        evidenceValue = if (floor == null) "Outside dungeon" else "Dungeon floor detected"
        manualValue = false
        phaseKnown = false
        enteredNanos = nowNanos
    }

    fun choose(phase: String?, nowNanos: Long): Boolean {
        if (floorValue == null || phase == null || !orderedSegments().contains(phase)) return false
        phaseValue = phase
        manualValue = true
        phaseKnown = true
        evidenceValue = "Manual choice"
        enteredNanos = nowNanos
        return true
    }

    fun resumeAuto() {
        manualValue = false
        evidenceValue = "Automatic detection resumed"
    }

    fun observe(
        event: DungeonGuideSegments.Event?,
        timing: DungeonGuideStore.TransitionTiming?,
        nowNanos: Long,
    ): Boolean {
        if (floorValue == null || manualValue || event == null || event.segmentId == null ||
            event.independentCue
        ) return false
        val order = orderedSegments()
        val at = order.indexOf(event.segmentId)
        if (at < 0) return false
        phaseKnown = true
        val target = if (timing == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE) {
            order[minOf(order.size - 1, at + 1)]
        } else {
            event.segmentId
        }
        if (order.indexOf(target) <= order.indexOf(phaseValue)) return false
        phaseValue = target
        evidenceValue = event.source
        enteredNanos = nowNanos
        return true
    }

    fun observeCustomChat(message: String?, nowNanos: Long): Boolean {
        val activeFloor = floorValue ?: return false
        if (manualValue || message == null) return false
        for (segment in DungeonGuideStore.customSegments(activeFloor)) {
            if (segment.trigger != DungeonGuideCustomSegment.Trigger.CHAT_EVENT ||
                ChatStringSemantics.isBlank(segment.eventText) ||
                !ChatStringSemantics.trim(message).equals(segment.eventText, ignoreCase = true)
            ) continue
            return enterCustom(segment, "Configured chat event", nowNanos)
        }
        return false
    }

    fun tickCustom(x: Double, y: Double, z: Double, nowNanos: Long): Boolean {
        val activeFloor = floorValue ?: return false
        if (manualValue) return false
        for (segment in DungeonGuideStore.customSegments(activeFloor)) {
            if (segment.trigger == DungeonGuideCustomSegment.Trigger.ENTER_RADIUS) {
                val dx = x - (segment.x + 0.5)
                val dy = y - (segment.y + 0.5)
                val dz = z - (segment.z + 0.5)
                if (dx * dx + dy * dy + dz * dz <= segment.radius * segment.radius &&
                    enterCustom(segment, "Configured location", nowNanos)
                ) return true
            } else if (segment.trigger == DungeonGuideCustomSegment.Trigger.AFTER_SECONDS && segment.seconds > 0 &&
                nowNanos - enteredNanos >= (segment.seconds * 1_000_000_000L).toLong() &&
                enterCustom(segment, "Configured timer", nowNanos)
            ) {
                return true
            }
        }
        return false
    }

    private fun enterCustom(segment: DungeonGuideCustomSegment, source: String, nowNanos: Long): Boolean {
        val order = orderedSegments()
        if (order.indexOf(segment.id) != order.indexOf(phaseValue) + 1) return false
        phaseValue = segment.id
        phaseKnown = true
        evidenceValue = source
        enteredNanos = nowNanos
        return true
    }

    fun orderedSegments(): List<String> {
        val activeFloor = floorValue ?: return emptyList()
        val result = ArrayList<String>()
        val custom = ArrayList(DungeonGuideStore.customSegments(activeFloor))
        custom.sortBy { it.afterBuiltInIndex }
        val builtIn = DungeonGuideSegments.builtIn(activeFloor)
        for (i in builtIn.indices) {
            result.add(builtIn[i].id)
            for (segment in custom) {
                if (segment.afterBuiltInIndex == i) result.add(segment.id)
            }
        }
        return java.util.List.copyOf(result)
    }

    fun phase(): String = phaseValue
    fun evidence(): String = evidenceValue
    fun manual(): Boolean = manualValue
    fun hasPhaseEvidence(): Boolean = phaseKnown
    fun detectedPhaseFor(requestedFloor: DungeonFloor?): String? =
        if (requestedFloor != null && requestedFloor === floorValue && phaseKnown) phaseValue else null

    fun floor(): DungeonFloor? = floorValue

    companion object {
        @JvmStatic
        fun preferredWaypointPhase(
            validPhases: List<String>?,
            detectedPhase: String?,
            selectedPhase: String?,
        ): String {
            if (validPhases != null && detectedPhase != null && validPhases.contains(detectedPhase)) {
                return detectedPhase
            }
            if (validPhases != null && selectedPhase != null && validPhases.contains(selectedPhase)) {
                return selectedPhase
            }
            return "ENTRY"
        }
    }
}
