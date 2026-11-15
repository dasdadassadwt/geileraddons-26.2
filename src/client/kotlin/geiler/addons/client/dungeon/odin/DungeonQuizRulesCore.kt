package geiler.addons.client.dungeon.odin

import java.util.Locale

/** Pure prompt matching and answer interpretation for the Quiz adapter. */
internal object DungeonQuizRulesCore {
    private const val ORUO_PREFIX = "[STATUE] Oruo the Omniscient: "
    private const val SKYBLOCK_EPOCH_START_SECONDS = 1_560_276_000L
    private const val SKYBLOCK_YEAR_SECONDS = 446_400L

    fun observedQuestion(rawLine: String?, inDungeon: Boolean, inPuzzleRoom: Boolean, conversationActive: Boolean): String? {
        if (!inDungeon || rawLine == null) return null
        val line = javaTrim(stripFormatting(rawLine))
        if (line.isEmpty()) return null
        val explicitOruo = line.regionMatches(0, ORUO_PREFIX, 0, ORUO_PREFIX.length, ignoreCase = true)
        if (!inPuzzleRoom && !explicitOruo) return null
        val body = if (explicitOruo) {
            javaTrim(line.substring(ORUO_PREFIX.length))
        } else {
            line
        }
        if (isSkyblockYearQuestion(body)) return body
        val match = DungeonQuizCatalog.bundled().match(body)
        return match?.sourceQuestion()
    }

    fun isQuestionHeader(rawLine: String?): Boolean =
        rawLine != null && normalize(rawLine).matches(Regex("question [0-9]+"))

    fun visibleLine(rawLine: String?): String = javaTrim(stripFormatting(rawLine))

    fun acceptedAnswers(question: String?, epochSeconds: Long): List<String> {
        if (isSkyblockYearQuestion(question)) {
            val year = Math.floorDiv(epochSeconds - SKYBLOCK_EPOCH_START_SECONDS, SKYBLOCK_YEAR_SECONDS) + 1L
            return java.util.List.of("Year $year")
        }
        val match = DungeonQuizCatalog.bundled().match(question)
        return match?.answers() ?: java.util.List.of()
    }

    fun parseConfirmedOption(rawLine: String?, acceptedAnswers: List<String?>?): DungeonQuizRules.ConfirmedOption? {
        if (rawLine == null || acceptedAnswers.isNullOrEmpty()) return null
        val line = javaTrim(stripFormatting(rawLine))
        if (line.length < 2) return null
        val marker = line.substring(0, 1)
        if (marker != "ⓐ" && marker != "ⓑ" && marker != "ⓒ") return null
        val answer = acceptedAnswers.asSequence()
            .filterNotNull()
            .filter { !javaIsBlank(it) && line.endsWith(it) }
            .maxWithOrNull(compareBy<String> { it.length })
            ?: return null
        return DungeonQuizRules.ConfirmedOption(marker, answer)
    }

    fun isOruoStageMessage(rawLine: String?): Boolean {
        val body = oruoBody(rawLine) ?: return false
        val normalized = normalize(body)
        return normalized == normalize("I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.") ||
            normalized == normalize("2 questions left... Then you will have proven your worth to me!") ||
            normalized == normalize("One more question!")
    }

    fun isOruoCorrectAnswer(rawLine: String?): Boolean {
        val body = oruoBody(rawLine) ?: return false
        return normalize(body).matches(Regex(".+ answered question [0-9]+ correctly"))
    }

    fun isOruoFinalAnswer(rawLine: String?): Boolean {
        val body = oruoBody(rawLine) ?: return false
        return normalize(body).matches(Regex(".+ answered the final question correctly"))
    }

    fun resolveExactAnswer(question: String?, facts: Map<String, String?>?): String? {
        val key = normalize(question)
        if (key.isEmpty() || facts.isNullOrEmpty()) return null
        var resolved: String? = null
        var matched = false
        for ((factQuestion, rawAnswer) in facts) {
            if (key != normalize(factQuestion)) continue
            val answer = rawAnswer?.let(::javaTrim).orEmpty()
            if (answer.isEmpty()) continue
            if (matched && resolved != answer) return null
            resolved = answer
            matched = true
        }
        return if (matched) resolved else null
    }

    fun resolveBossStatus(question: String?): String? {
        val match = DungeonQuizCatalog.bundled().match(question)
        return if (match == null || match.answers().size != 1) null else match.answers().get(0)
    }

    fun normalize(value: String?): String = stripFormatting(value ?: "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .let(::javaTrim)

    private fun isSkyblockYearQuestion(question: String?): Boolean =
        normalize(question) == "what skyblock year is it"

    private fun oruoBody(rawLine: String?): String? {
        if (rawLine == null) return null
        val line = javaTrim(stripFormatting(rawLine))
        return if (line.regionMatches(0, ORUO_PREFIX, 0, ORUO_PREFIX.length, ignoreCase = true)) {
            javaTrim(line.substring(ORUO_PREFIX.length))
        } else {
            null
        }
    }

    private fun stripFormatting(value: String?): String =
        value?.replace(Regex("(?i)§[0-9A-FK-OR]"), "") ?: ""

    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    private fun javaIsBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
}
