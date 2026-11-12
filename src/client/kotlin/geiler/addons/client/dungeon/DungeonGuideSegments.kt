package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.Locale
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Floor-specific Guide stages and client-visible chat milestones. */
class DungeonGuideSegments private constructor() {
    @JvmRecord
    data class Segment(val id: String, val label: String)

    /** Marker cues are observable chat events whose order relative to other boss cues is unverified. */
    @JvmRecord
    data class Event(val segmentId: String, val source: String, val independentCue: Boolean) {
        constructor(segmentId: String, source: String) : this(segmentId, source, false)
    }

    companion object {
        private val WATCHER_BLOOD_OPEN_CUES = listOf(
            "congratulations, you made it through the entrance.",
            "ah, you've finally arrived.",
            "ah, we meet again...",
            "so you made it this far... interesting...",
            "you've managed to scratch and claw your way here, eh?",
            "i'm starting to get tired of seeing you around here...",
            "oh... hello?",
            "things feel a little more roomy now, eh?",
        )
        private val COMMON = listOf(
            Segment("ENTRY", "Entry"),
            Segment("CLEAR", "Clear"),
            Segment("BLOOD_OPEN", "Blood Open"),
            Segment("BLOOD_CLEAR", "Blood Clear"),
            Segment("BOSS_ENTRY", "Boss Entry"),
        )

        @JvmStatic
        fun builtIn(floor: DungeonFloor?): List<Segment> {
            if (floor == null) return emptyList()
            val result = ArrayList(COMMON)
            when (floor.number()) {
                0 -> result.add(Segment("BONZO", "Bonzo"))
                1 -> {
                    result.add(Segment("BONZO_SIKE", "Bonzo's Sike"))
                    result.add(Segment("BONZO", "Bonzo"))
                }
                2 -> {
                    result.add(Segment("SCARF_MINIONS", "Scarf's Minions"))
                    result.add(Segment("SCARF", "Scarf"))
                }
                3 -> {
                    result.add(Segment("GUARDIANS", "Guardians"))
                    result.add(Segment("PROFESSOR", "The Professor"))
                    result.add(Segment("PROFESSOR_TRANSFORMED", "Transformed Professor"))
                }
                4 -> result.add(Segment("THORN", "Thorn"))
                5 -> result.add(Segment("LIVID", "Livid"))
                6 -> {
                    result.add(Segment("TERRACOTTAS", "Terracottas"))
                    result.add(Segment("SADAN_GIANTS", "Sadan's Giants"))
                    result.add(Segment("SADAN", "Sadan"))
                }
                7 -> {
                    result.add(Segment("MAXOR", "Maxor"))
                    result.add(Segment("STORM", "Storm"))
                    result.add(Segment("TERMINALS", "Terminals"))
                    result.add(Segment("GOLDOR", "Goldor"))
                    result.add(Segment("NECRON", "Necron"))
                    if (floor.master()) result.add(Segment("WITHER_KING", "Wither King"))
                }
            }
            result.add(Segment("COMPLETE", "Complete"))
            return java.util.List.copyOf(result)
        }

        @JvmStatic
        fun label(floor: DungeonFloor?, id: String?): String {
            for (segment in builtIn(floor)) if (segment.id == id) return segment.label
            if (id != null) {
                val cueLabel = when (id) {
                    "SCARF_DANCE_CUE" -> "Scarf's final-dance cue"
                    "PROFESSOR_ULTIMATE_CUE" -> "Professor ultimate-technique cue"
                    "PROFESSOR_GUARDIAN_POWER_CUE" -> "Professor guardian-power cue"
                    "TRANSFORMED_PROFESSOR" -> "Transformed Professor"
                    "STORM_LIGHTNING_ENERGY_CUE" -> "Storm energy cue"
                    "STORM_LIGHTNING_THUNDER_CUE" -> "Storm thunder cue"
                    else -> null
                }
                if (cueLabel != null) return cueLabel
            }
            if (id == "BLOOD") return "Blood"
            if (id == "BOSS") return "Boss"
            return id?.replace('_', ' ') ?: "Unknown"
        }

        @JvmStatic
        fun contains(floor: DungeonFloor?, id: String?): Boolean {
            if (floor == null || id == null) return false
            for (segment in builtIn(floor)) if (segment.id == id) return true
            if (floor.number() == 3 && id == "TRANSFORMED_PROFESSOR") return true
            if (floor.number() == 7 && Regex("S[1-4]").matches(id)) return true
            return false
        }

        @JvmStatic
        fun isIndependentCue(floor: DungeonFloor?, id: String?): Boolean {
            if (floor == null || id == null) return false
            return when (floor.number()) {
                2 -> id == "SCARF_DANCE_CUE"
                3 -> id == "PROFESSOR_GUARDIAN_POWER_CUE"
                7 -> id == "STORM_LIGHTNING_ENERGY_CUE" || id == "STORM_LIGHTNING_THUNDER_CUE"
                else -> false
            }
        }

        @JvmStatic
        fun next(floor: DungeonFloor?, id: String?): String? {
            val segments = builtIn(floor)
            for (i in segments.indices) if (segments[i].id == id) {
                return segments[minOf(i + 1, segments.size - 1)].id
            }
            return id
        }

        /** Returns the milestone reached by a chat line, if the line is specific to this floor. */
        @JvmStatic
        fun fromChat(floor: DungeonFloor?, message: String?): Event? {
            if (floor == null || message == null) return null
            val line = ChatStringSemantics.trim(message.lowercase(Locale.ROOT))
            if (line == "starting in 1 second.") return Event("ENTRY", "Run countdown chat")
            if (line.contains("mort: here, i found this map when i first entered the dungeon.")) {
                return Event("CLEAR", "Mort map chat")
            }
            if (line.contains("blood door has been opened") || watcherBloodOpen(line)) {
                return Event("BLOOD_OPEN", "Blood door chat")
            }
            if (line.contains("the watcher:") && line.contains("you have proven yourself") && line.contains("may pass")) {
                return Event("BLOOD_CLEAR", "Watcher completion chat")
            }
            bossEntry(floor, line)?.let { return it }
            if ((line.startsWith("☠ defeated ") || line.startsWith("defeated ")) && defeatBoss(floor, line)) {
                return Event("COMPLETE", "Boss defeat chat")
            }
            return when (floor.number()) {
                1 -> if (line.contains("bonzo:") && line.contains("oh i'm dead")) {
                    Event("BONZO", "Bonzo death dialogue")
                } else null
                2 -> when {
                    line.contains("scarf:") && line.endsWith("those toys are not strong enough i see.") ->
                        Event("SCARF", "Scarf phase dialogue")
                    line.contains("scarf:") && line.endsWith("did you forget? i was taught by the best! let's dance.") ->
                        Event("SCARF_DANCE_CUE", "Scarf final-dance cue", true)
                    else -> null
                }
                3 -> when {
                    line.contains("professor:") && line.contains("guardians") && line.contains("weakness") ->
                        Event("PROFESSOR", "Professor weakness dialogue")
                    line.contains("professor:") && line.contains("ultimate technique") ->
                        Event("PROFESSOR_TRANSFORMED", "Professor ultimate-technique dialogue")
                    line.contains("professor:") && line.contains("guardian power is unbeatable") ->
                        Event("PROFESSOR_GUARDIAN_POWER_CUE", "Professor guardian-power cue", true)
                    else -> null
                }
                6 -> when {
                    line.contains("sadan:") && line.endsWith("enough!") ->
                        Event("SADAN_GIANTS", "Sadan giants dialogue")
                    line.contains("sadan:") && line.contains("earned my respect") ->
                        Event("SADAN", "Sadan final dialogue")
                    else -> null
                }
                7 -> when {
                    line.contains("storm:") && line.contains("pathetic maxor") ->
                        Event("STORM", "Storm opening dialogue")
                    line.contains("storm:") && line.endsWith("energy heed my call!") ->
                        Event("STORM_LIGHTNING_ENERGY_CUE", "Storm lightning energy cue", true)
                    line.contains("storm:") && line.endsWith("thunder let me be your catalyst!") ->
                        Event("STORM_LIGHTNING_THUNDER_CUE", "Storm lightning thunder cue", true)
                    line.contains("goldor:") && line.contains("trespass into my domain") ->
                        Event("TERMINALS", "Goldor terminal-room dialogue")
                    terminalProgress(line) -> Event("TERMINALS", "Terminal progress chat")
                    line.contains("core entrance is opening") -> Event("GOLDOR", "Core entrance chat")
                    line.contains("necron:") && line.contains("went further than any human") ->
                        Event("NECRON", "Necron opening dialogue")
                    floor.master() && line.contains("necron:") && line.contains("all this, for nothing") ->
                        Event("NECRON", "M7 Necron dialogue")
                    floor.master() && line.contains("necron: argh!") ->
                        Event("WITHER_KING", "M7 Necron transition dialogue")
                    floor.master() && line.contains("wither king:") && line.contains("you...") && line.contains("again?") ->
                        Event("WITHER_KING", "M7 Wither King dialogue")
                    else -> null
                }
                else -> null
            }
        }

        private fun bossEntry(floor: DungeonFloor, line: String): Event? = when (floor.number()) {
            0 -> if (line.contains("bonzo:") && line.contains("basically unbeatable")) Event("BONZO", "Entrance Bonzo greeting") else null
            1 -> if (line.contains("bonzo:") && line.contains("basically unbeatable")) Event("BONZO_SIKE", "F1 Bonzo greeting") else null
            2 -> if (line.contains("scarf:") && line.contains("journey ends")) Event("SCARF_MINIONS", "Scarf opening dialogue") else null
            3 -> if (line.contains("professor:") && line.contains("burdened with terrible news")) Event("GUARDIANS", "Professor opening dialogue") else null
            4 -> if (line.contains("thorn:") && line.contains("welcome adventurers")) Event("THORN", "Thorn opening dialogue") else null
            5 -> if (line.contains("livid:") && line.contains("master of shadows")) Event("LIVID", "Livid opening dialogue") else null
            6 -> if (line.contains("sadan:") && line.contains("defy me")) Event("TERRACOTTAS", "Sadan opening dialogue") else null
            7 -> if (line.endsWith("maxor: well! well! well! look who's here!")) Event("MAXOR", "Maxor opening dialogue") else null
            else -> null
        }

        private fun watcherBloodOpen(line: String): Boolean =
            line.contains("the watcher:") && WATCHER_BLOOD_OPEN_CUES.any(line::endsWith)

        private fun terminalProgress(line: String): Boolean =
            Regex("[a-z0-9_]+ (?:activated|completed) a (?:terminal|lever|device)! \\([0-9]/[0-9]\\)").matches(line)

        private fun defeatBoss(floor: DungeonFloor, line: String): Boolean = when (floor.number()) {
            0, 1 -> line.contains("bonzo")
            2 -> line.contains("scarf")
            3 -> line.contains("professor")
            4 -> line.contains("thorn")
            5 -> line.contains("livid")
            6 -> line.contains("sadan")
            7 -> line.contains(if (floor.master()) "wither king" else "necron")
            else -> false
        }
    }
}
