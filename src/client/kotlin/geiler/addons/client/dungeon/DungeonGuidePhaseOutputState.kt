package geiler.addons.client.dungeon

import java.util.ArrayList
import java.util.HashSet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Deduplicates observed phase and independent-cue outputs by run and validates their chat evidence. */
class DungeonGuidePhaseOutputState {
    private var current: Transition? = null
    private val observedPhases = HashSet<Transition>()
    private val observedCues = HashSet<Transition>()
    private var activeFloor: DungeonFloor? = null
    private var sawEntry = false

    fun observe(floor: DungeonFloor?, phaseId: String?, evidence: String?): Transition? {
        if (floor == null) {
            reset()
            return null
        }
        prepareFloor(floor)
        if (phaseId == null || DungeonGuideSegments.isIndependentCue(floor, phaseId) ||
            !stableSignal(floor, phaseId, evidence)
        ) return null
        if (phaseId == "ENTRY" && !isCurrentEntry(floor)) beginRun(floor)
        val next = Transition(floor, phaseId)
        if (observedPhases.contains(next) || !advancesFromCurrent(next)) return null
        observedPhases.add(next)
        current = next
        return next
    }

    /** Emits feedback directly from a source-backed chat event, independent of route timing settings. */
    fun observeEvent(floor: DungeonFloor?, event: DungeonGuideSegments.Event?): Transition? {
        if (floor == null || event == null) return null
        return if (event.independentCue) observeCue(floor, event) else {
            observe(floor, event.segmentId, event.source)
        }
    }

    /** Independent markers are surfaced but never become ordered phase intervals. */
    fun observeCue(floor: DungeonFloor?, event: DungeonGuideSegments.Event?): Transition? {
        if (floor == null || event == null || !event.independentCue ||
            !DungeonGuideSegments.isIndependentCue(floor, event.segmentId) ||
            !stableSignal(floor, event.segmentId, event.source)
        ) return null
        prepareFloor(floor)
        val cue = Transition(floor, event.segmentId)
        return if (observedCues.add(cue)) cue else null
    }

    /** Drop per-run event memory on a dungeon exit or floor change. */
    fun tickContext(floor: DungeonFloor?) {
        if (floor == null) reset() else prepareFloor(floor)
    }

    fun reset() {
        current = null
        observedPhases.clear()
        observedCues.clear()
        activeFloor = null
        sawEntry = false
    }

    private fun prepareFloor(floor: DungeonFloor) {
        if (activeFloor === floor) return
        current = null
        observedPhases.clear()
        observedCues.clear()
        activeFloor = floor
        sawEntry = false
    }

    private fun isCurrentEntry(floor: DungeonFloor): Boolean =
        sawEntry && Transition(floor, "ENTRY") == current

    private fun beginRun(floor: DungeonFloor) {
        observedPhases.clear()
        observedCues.clear()
        current = null
        activeFloor = floor
        sawEntry = true
    }

    private fun advancesFromCurrent(next: Transition): Boolean {
        val currentTransition = current ?: return true
        val order = DungeonGuideSegments.builtIn(next.floor)
        val currentIndex = indexOf(order, currentTransition.phaseId)
        val nextIndex = indexOf(order, next.phaseId)
        return currentIndex < 0 || nextIndex > currentIndex
    }

    private fun indexOf(order: List<DungeonGuideSegments.Segment>, id: String?): Int {
        for (i in order.indices) if (order[i].id == id) return i
        return -1
    }

    private fun stableSignal(floor: DungeonFloor, id: String?, evidence: String?): Boolean =
        id == phaseForEvidence(floor, evidence)

    private fun phaseForEvidence(floor: DungeonFloor?, evidence: String?): String? {
        if (floor == null || evidence == null) return null
        return when (evidence) {
            "Run countdown chat" -> "ENTRY"
            "Mort map chat" -> "CLEAR"
            "Blood door chat" -> "BLOOD_OPEN"
            "Watcher completion chat" -> "BLOOD_CLEAR"
            "Boss defeat chat" -> "COMPLETE"
            "Entrance Bonzo greeting" -> if (floor.number() == 0) "BONZO" else null
            "F1 Bonzo greeting" -> if (floor.number() == 1) "BONZO_SIKE" else null
            "Bonzo death dialogue" -> if (floor.number() == 1) "BONZO" else null
            "Scarf opening dialogue" -> if (floor.number() == 2) "SCARF_MINIONS" else null
            "Scarf phase dialogue" -> if (floor.number() == 2) "SCARF" else null
            "Professor opening dialogue" -> if (floor.number() == 3) "GUARDIANS" else null
            "Professor weakness dialogue" -> if (floor.number() == 3) "PROFESSOR" else null
            "Thorn opening dialogue" -> if (floor.number() == 4) "THORN" else null
            "Livid opening dialogue" -> if (floor.number() == 5) "LIVID" else null
            "Sadan opening dialogue" -> if (floor.number() == 6) "TERRACOTTAS" else null
            "Sadan giants dialogue" -> if (floor.number() == 6) "SADAN_GIANTS" else null
            "Sadan final dialogue" -> if (floor.number() == 6) "SADAN" else null
            "Maxor opening dialogue" -> if (floor.number() == 7) "MAXOR" else null
            "Storm opening dialogue" -> if (floor.number() == 7) "STORM" else null
            "Goldor terminal-room dialogue", "Terminal progress chat" ->
                if (floor.number() == 7) "TERMINALS" else null
            "Core entrance chat" -> if (floor.number() == 7) "GOLDOR" else null
            "Necron opening dialogue", "M7 Necron dialogue" -> if (floor.number() == 7) "NECRON" else null
            "M7 Necron transition dialogue", "M7 Wither King dialogue" ->
                if (floor.number() == 7 && floor.master()) "WITHER_KING" else null
            "Scarf final-dance cue" -> if (floor.number() == 2) "SCARF_DANCE_CUE" else null
            "Professor ultimate-technique dialogue" ->
                if (floor.number() == 3) "PROFESSOR_TRANSFORMED" else null
            "Professor guardian-power cue" ->
                if (floor.number() == 3) "PROFESSOR_GUARDIAN_POWER_CUE" else null
            "Storm lightning energy cue" ->
                if (floor.number() == 7) "STORM_LIGHTNING_ENERGY_CUE" else null
            "Storm lightning thunder cue" ->
                if (floor.number() == 7) "STORM_LIGHTNING_THUNDER_CUE" else null
            else -> null
        }
    }

    companion object {
        /** Built-in IDs for which the source-backed chat parser exposes a stable signal. */
        @JvmStatic
        fun supportedPhaseIds(floor: DungeonFloor?): List<String> {
            if (floor == null) return emptyList()
            val supported = ArrayList<String>()
            for (segment in DungeonGuideSegments.builtIn(floor)) {
                if (hasStableSignal(floor, segment.id)) supported.add(segment.id)
            }
            supported.addAll(cueIds(floor))
            return java.util.List.copyOf(supported)
        }

        private fun cueIds(floor: DungeonFloor): List<String> = when (floor.number()) {
            2 -> listOf("SCARF_DANCE_CUE")
            3 -> listOf("PROFESSOR_GUARDIAN_POWER_CUE")
            7 -> listOf("STORM_LIGHTNING_ENERGY_CUE", "STORM_LIGHTNING_THUNDER_CUE")
            else -> emptyList()
        }

        private fun hasStableSignal(floor: DungeonFloor, id: String): Boolean {
            if (id == "ENTRY" || id == "CLEAR" || id == "BLOOD_OPEN" || id == "BLOOD_CLEAR" ||
                id == "COMPLETE" || DungeonGuideSegments.isIndependentCue(floor, id)
            ) return true
            return when (floor.number()) {
                0 -> id == "BONZO"
                1 -> id == "BONZO_SIKE" || id == "BONZO"
                2 -> id == "SCARF_MINIONS" || id == "SCARF"
                3 -> id == "GUARDIANS" || id == "PROFESSOR" || id == "PROFESSOR_TRANSFORMED"
                4 -> id == "THORN"
                5 -> id == "LIVID"
                6 -> id == "TERRACOTTAS" || id == "SADAN_GIANTS" || id == "SADAN"
                7 -> id == "MAXOR" || id == "STORM" || id == "TERMINALS" || id == "GOLDOR" ||
                    id == "NECRON" || (floor.master() && id == "WITHER_KING")
                else -> false
            }
        }
    }

    @JvmRecord
    data class Transition(val floor: DungeonFloor?, val phaseId: String?)
}
