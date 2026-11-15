package geiler.addons.client.dungeon.odin

import java.time.Instant

/** Client-observed Quiz conversation facade with a Java-compatible instance API. */
class DungeonQuizTracker {
    private val startStage = DungeonQuizRulesCore.normalize(
        "[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.",
    )
    private val secondStage = DungeonQuizRulesCore.normalize(
        "[STATUE] Oruo the Omniscient: 2 questions left... Then you will have proven your worth to me!",
    )
    private val finalStage = DungeonQuizRulesCore.normalize(
        "[STATUE] Oruo the Omniscient: One more question!",
    )

    private var active = false
    private var currentQuestion: String? = null
    private var currentAcceptedAnswers: List<String> = java.util.List.of()
    private var currentConfirmedOption: DungeonQuizRules.ConfirmedOption? = null
    private var awaitingQuestionLine = false
    private var unmatched: String? = null
    private var currentTimerTicks = 0
    private var currentTimerMaximumTicks = 0
    private var currentTimerStage = 0
    private var lastServerGameTime = Long.MIN_VALUE

    fun onChat(rawLine: String?, inDungeon: Boolean, inPuzzleRoom: Boolean) {
        onChat(rawLine, inDungeon, inPuzzleRoom, Instant.now().epochSecond)
    }

    /** Time-injected overload keeps the rotating SkyBlock-year answer deterministic in checks. */
    fun onChat(rawLine: String?, inDungeon: Boolean, inPuzzleRoom: Boolean, epochSeconds: Long) {
        if (!inDungeon) {
            reset()
            return
        }
        if (rawLine == null) return
        if (DungeonQuizRulesCore.isOruoFinalAnswer(rawLine)) {
            reset()
            return
        }
        val normalized = DungeonQuizRulesCore.normalize(rawLine)
        when (normalized) {
            startStage -> setTimer(220, 1)
            secondStage -> setTimer(100, 2)
            finalStage -> setTimer(100, 3)
        }
        if (DungeonQuizRulesCore.isOruoStageMessage(rawLine)) {
            active = true
            currentConfirmedOption = null
            return
        }
        if (DungeonQuizRulesCore.isOruoCorrectAnswer(rawLine)) {
            active = true
            currentConfirmedOption = null
            return
        }
        val option = DungeonQuizRulesCore.parseConfirmedOption(rawLine, currentAcceptedAnswers)
        if (option != null) {
            currentConfirmedOption = option
            return
        }
        if (DungeonQuizRulesCore.isQuestionHeader(rawLine)) {
            // Hypixel prints "Question #N" and then the unprefixed question; room detection is not needed.
            active = true
            awaitingQuestionLine = true
            return
        }
        val afterHeader = awaitingQuestionLine && rawLine.isNotBlank()
        if (afterHeader) awaitingQuestionLine = false
        val observed = DungeonQuizRulesCore.observedQuestion(rawLine, true, inPuzzleRoom || afterHeader, active)
        if (observed != null) {
            active = true
            currentQuestion = observed
            currentAcceptedAnswers = DungeonQuizRulesCore.acceptedAnswers(observed, epochSeconds)
            currentConfirmedOption = null
            unmatched = null
        } else if (afterHeader) {
            unmatched = DungeonQuizRulesCore.visibleLine(rawLine).take(120)
            currentQuestion = null
            currentAcceptedAnswers = java.util.List.of()
            currentConfirmedOption = null
        }
    }

    fun onServerGameTime(gameTime: Long) {
        if (lastServerGameTime == Long.MIN_VALUE || gameTime < lastServerGameTime) {
            lastServerGameTime = gameTime
            return
        }
        val elapsedTicks = gameTime - lastServerGameTime
        lastServerGameTime = gameTime
        if (currentTimerTicks <= 0 || elapsedTicks <= 0) return
        currentTimerTicks = (currentTimerTicks - minOf(currentTimerTicks.toLong(), elapsedTicks)).toInt()
    }

    private fun setTimer(maximumTicks: Int, stage: Int) {
        currentTimerTicks = maximumTicks
        currentTimerMaximumTicks = maximumTicks
        currentTimerStage = stage
    }

    fun question(): String? = currentQuestion
    fun conversationActive(): Boolean = active
    fun acceptedAnswers(): List<String> = currentAcceptedAnswers
    fun confirmedOption(): DungeonQuizRules.ConfirmedOption? = currentConfirmedOption

    /** Last header-announced question the catalog could not match; surfaced in diagnostics so data gaps are visible. */
    fun unmatchedQuestion(): String? = unmatched
    fun timerTicks(): Int = currentTimerTicks
    fun timerMaximumTicks(): Int = currentTimerMaximumTicks
    fun timerStage(): Int = currentTimerStage

    fun reset() {
        active = false
        currentQuestion = null
        currentAcceptedAnswers = java.util.List.of()
        currentConfirmedOption = null
        awaitingQuestionLine = false
        unmatched = null
        currentTimerTicks = 0
        currentTimerMaximumTicks = 0
        currentTimerStage = 0
        lastServerGameTime = Long.MIN_VALUE
    }
}
