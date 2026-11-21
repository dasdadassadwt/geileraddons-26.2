package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Java-compatible facade for the pure Kotlin Quiz matching rules. */
@JvmSuppressWildcards
class DungeonQuizRules private constructor() {
    @JvmRecord
    data class ConfirmedOption(val marker: String?, val answer: String?) {
        override fun toString(): String = "ConfirmedOption[marker=$marker, answer=$answer]"
    }

    companion object {
        @JvmStatic
        fun observedQuestion(
            rawLine: String?,
            inDungeon: Boolean,
            inPuzzleRoom: Boolean,
            conversationActive: Boolean,
        ): String? = DungeonQuizRulesCore.observedQuestion(rawLine, inDungeon, inPuzzleRoom, conversationActive)

        @JvmStatic
        fun acceptedAnswers(question: String?, epochSeconds: Long): List<String> =
            DungeonQuizRulesCore.acceptedAnswers(question, epochSeconds)

        @JvmStatic
        fun parseConfirmedOption(
            rawLine: String?,
            acceptedAnswers: List<String?>?,
        ): ConfirmedOption? = DungeonQuizRulesCore.parseConfirmedOption(rawLine, acceptedAnswers)

        @JvmStatic
        fun isOruoStageMessage(rawLine: String?): Boolean = DungeonQuizRulesCore.isOruoStageMessage(rawLine)

        @JvmStatic
        fun isOruoCorrectAnswer(rawLine: String?): Boolean = DungeonQuizRulesCore.isOruoCorrectAnswer(rawLine)

        @JvmStatic
        fun isOruoFinalAnswer(rawLine: String?): Boolean = DungeonQuizRulesCore.isOruoFinalAnswer(rawLine)

        @JvmStatic
        fun resolveExactAnswer(question: String?, facts: Map<String, String?>?): String? =
            DungeonQuizRulesCore.resolveExactAnswer(question, facts)

        @JvmStatic
        fun resolveBossStatus(question: String?): String? = DungeonQuizRulesCore.resolveBossStatus(question)

        @JvmStatic
        fun normalize(value: String?): String = DungeonQuizRulesCore.normalize(value)
    }
}

internal fun DungeonQuizRules.ConfirmedOption.marker(): String? = marker
internal fun DungeonQuizRules.ConfirmedOption.answer(): String? = answer
