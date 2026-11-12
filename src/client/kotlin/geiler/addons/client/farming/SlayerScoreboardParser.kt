package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.regex.Pattern

/** Parses usable Slayer quest progress, boss state, and stable absence from the sidebar. */
class SlayerScoreboardParser private constructor() {
    enum class State { INCOMPLETE, NO_QUEST, QUEST_ACTIVE, BOSS_ACTIVE, BOSS_SLAIN }

    companion object {
        private val QUEST_PROGRESS =
            Pattern.compile("(?s).*(?:quest\\s*xp|\\bxp\\b|\\bprogress\\b|\\b\\d+\\s*/\\s*\\d+\\b).*")
        private val LINE_BREAK = Pattern.compile("\\R")
        private val QUEST_NAMES = arrayOf(
            "revenant horror",
            "tarantula broodfather",
            "sven packmaster",
            "voidgloom seraph",
            "inferno demonlord",
        )

        @JvmStatic
        fun parse(text: String?): State {
            if (text == null || isJavaBlank(text)) return State.INCOMPLETE
            val normalized = ChatText.stripForMatch(text).lowercase(Locale.ROOT)
            if (normalized.contains("boss slain!")) return State.BOSS_SLAIN
            if (normalized.contains("slay the boss!")) return State.BOSS_ACTIVE

            // stripForMatch normalizes every whitespace character, including line breaks, to a space.
            // Count usable rows from the original sidebar before flattening it for phrase matching.
            val lines = LINE_BREAK.split(text)
            var nonEmptyLines = 0
            for (line in lines) {
                if (!isJavaBlank(ChatText.stripForMatch(line))) nonEmptyLines++
            }
            if (nonEmptyLines < 2) return State.INCOMPLETE

            val hasQuestHeader = normalized.contains("slayer quest")
            if (hasQuestHeader && (QUEST_PROGRESS.matcher(normalized).matches() || containsQuestName(normalized))) {
                return State.QUEST_ACTIVE
            }
            if (hasQuestHeader) return State.INCOMPLETE
            return State.NO_QUEST
        }

        private fun containsQuestName(text: String): Boolean {
            for (questName in QUEST_NAMES) if (text.contains(questName)) return true
            return false
        }

        /** Mirrors String.isBlank() from Java, which uses Character.isWhitespace. */
        private fun isJavaBlank(text: String): Boolean {
            for (character in text) if (!Character.isWhitespace(character)) return false
            return true
        }
    }
}
