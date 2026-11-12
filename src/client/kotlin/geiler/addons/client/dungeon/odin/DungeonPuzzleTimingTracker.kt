package geiler.addons.client.dungeon.odin

import java.util.HashMap
import java.util.Optional
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Pure state for puzzle timing, personal-best comparison, and the clickable draft prompt.
 * Callers supply already-validated dungeon context and room-local block coordinates.
 */
class DungeonPuzzleTimingTracker {
    private val timers = HashMap<String, PuzzleTimer>()
    private val personalBests = HashMap<String, Float>()
    private val blazeTargetCounts = HashMap<String, Int>()

    /** Starts one timer per exact timed room; Tic Tac Toe stays out until it has a source completion event. */
    fun onPuzzleRoomEnter(roomDisplayName: String?, nowNanos: Long): Boolean {
        if (roomDisplayName == "Tic Tac Toe") return false
        if (roomDisplayName != null &&
            (roomDisplayName == "Higher Blaze" || roomDisplayName == "Lower Blaze")
        ) {
            blazeTargetCounts[roomDisplayName] = 0
        }
        if (roomDisplayName == null || roomDisplayName.isBlank() || timers.containsKey(roomDisplayName)) return false
        timers[roomDisplayName] = PuzzleTimer(nowNanos)
        return true
    }

    /** A new level clears active puzzle timers while retaining saved personal bests. */
    fun onLevelLoad() {
        timers.clear()
        blazeTargetCounts.clear()
    }

    /**
     * Completes a timer only for the source's exact CHERRY_LOG block event targets.
     * The caller converts the packet's world position with the current room transform first.
     */
    fun onPuzzleBlockEvent(
        roomDisplayName: String?,
        blockId: String?,
        roomX: Int,
        roomY: Int,
        roomZ: Int,
        nowNanos: Long,
        inPuzzlePhase: Boolean,
        currentRoomIsPuzzle: Boolean,
    ): Optional<Completion> {
        if (!inPuzzlePhase || !currentRoomIsPuzzle ||
            !isCompletionTarget(roomDisplayName, blockId, roomX, roomY, roomZ)
        ) {
            return Optional.empty()
        }
        return completePuzzle(roomDisplayName, nowNanos)
    }

    /** Completes Creeper Beams only for the source's exact air-to-chest block update and local position. */
    fun onPuzzleBlockUpdate(
        roomDisplayName: String?,
        oldBlockId: String?,
        newBlockId: String?,
        roomX: Int,
        roomY: Int,
        roomZ: Int,
        nowNanos: Long,
        inPuzzlePhase: Boolean,
        currentRoomIsPuzzle: Boolean,
    ): Optional<Completion> {
        if (!inPuzzlePhase || !currentRoomIsPuzzle || roomDisplayName != "Creeper Beams" ||
            !isBlock(oldBlockId, "air") || !isBlock(newBlockId, "chest") ||
            roomX != 15 || roomY != 69 || roomZ != 15
        ) {
            return Optional.empty()
        }
        return completePuzzle(roomDisplayName, nowNanos)
    }

    /** Completes Quiz only when the caller confirms the exact final-answer event from the source rules. */
    fun onQuizFinalAnswer(
        roomDisplayName: String?,
        exactFinalAnswer: Boolean,
        nowNanos: Long,
        inPuzzlePhase: Boolean,
        currentRoomIsPuzzle: Boolean,
    ): Optional<Completion> {
        if (!exactFinalAnswer || !inPuzzlePhase || !currentRoomIsPuzzle || roomDisplayName != "Quiz") {
            return Optional.empty()
        }
        return completePuzzle(roomDisplayName, nowNanos)
    }

    /**
     * Tracks the source's Blaze entity scan edge. Empty scans never complete unless the previous
     * scan had exactly one tracked target and the current scan has none.
     */
    fun onBlazeTrackedTargetCount(
        roomDisplayName: String?,
        trackedTargetCount: Int,
        nowNanos: Long,
        inPuzzlePhase: Boolean,
        currentRoomIsPuzzle: Boolean,
    ): Optional<Completion> {
        val blazeRoom = roomDisplayName ?: return Optional.empty()
        if (blazeRoom != "Higher Blaze" && blazeRoom != "Lower Blaze") return Optional.empty()
        if (trackedTargetCount < 0 || !inPuzzlePhase || !currentRoomIsPuzzle) return Optional.empty()
        val previousCount = blazeTargetCounts.getOrDefault(blazeRoom, 0)
        blazeTargetCounts[blazeRoom] = trackedTargetCount
        if (previousCount != 1 || trackedTargetCount != 0) return Optional.empty()
        return completePuzzle(blazeRoom, nowNanos)
    }

    /** Returns empty when the timer is absent, already reported, invalid, or below the source threshold. */
    fun completePuzzle(roomDisplayName: String?, nowNanos: Long): Optional<Completion> {
        if (roomDisplayName == null) return Optional.empty()
        val timer = timers[roomDisplayName] ?: return Optional.empty()
        if (timer.reported || nowNanos < timer.startedAtNanos) return Optional.empty()
        val puzzleKey = puzzleKey(roomDisplayName)
        val minimumSeconds = MINIMUM_TIMES_SECONDS[puzzleKey] ?: return Optional.empty()

        val elapsedSeconds = (nowNanos - timer.startedAtNanos) / 1_000_000_000.0f
        if (elapsedSeconds < minimumSeconds) return Optional.empty()

        val previousBestSeconds = personalBests.getOrDefault(puzzleKey, 9999.0f)
        val personalBest = elapsedSeconds < previousBestSeconds
        val bestSeconds = if (personalBest) elapsedSeconds else previousBestSeconds
        if (personalBest) personalBests[puzzleKey] = elapsedSeconds
        timer.reported = true
        return Optional.of(
            Completion(
                puzzleKey,
                elapsedSeconds,
                minimumSeconds,
                previousBestSeconds,
                bestSeconds,
                personalBest,
            ),
        )
    }

    fun onChatMessage(
        rawMessage: String?,
        enabled: Boolean,
        inPuzzlePhase: Boolean,
        currentRoomIsPuzzle: Boolean,
    ): Optional<DraftPrompt> = draftPrompt(rawMessage, enabled, inPuzzlePhase, currentRoomIsPuzzle)

    fun personalBestSnapshot(): Map<String, Float> = java.util.Map.copyOf(personalBests)

    /** Restores only finite, non-negative values for names with a source-known puzzle threshold. */
    fun restorePersonalBests(saved: Map<String?, Float?>?) {
        personalBests.clear()
        if (saved == null) return
        for ((name, seconds) in saved) {
            if (name != null && MINIMUM_TIMES_SECONDS.containsKey(puzzleKey(name)) && seconds != null &&
                seconds.isFinite() && seconds >= 0.0f
            ) {
                val key = puzzleKey(name)
                personalBests.merge(key, seconds) { existing, incoming -> java.lang.Math.min(existing, incoming) }
            }
        }
    }

    private data class PuzzleTimer(val startedAtNanos: Long, var reported: Boolean = false)

    @JvmRecord
    data class Completion(
        val puzzleName: String?,
        val elapsedSeconds: Float,
        val minimumSeconds: Int,
        val previousBestSeconds: Float,
        val bestSeconds: Float,
        val personalBest: Boolean,
    )

    @JvmRecord
    data class DraftPrompt(
        val playerName: String?,
        val text: String?,
        val hoverText: String?,
        val runCommand: String?,
    )

    @JvmRecord
    private data class BlockTarget(val x: Int, val y: Int, val z: Int)

    companion object {
        private const val CHERRY_LOG_ID = "cherry_log"
        private const val DRAFT_COMMAND = "gfs architect's first draft 1"
        private const val DRAFT_TEXT = "Click here to fetch architect's draft"
        private const val DRAFT_HOVER_TEXT = "Click to fetch the architect's draft"
        private val MINIMUM_TIMES_SECONDS = mapOf(
            "Water Board" to 10,
            "Ice Fill" to 10,
            "Teleport Maze" to 6,
            "Three Weirdos" to 2,
            "Boulder" to 6,
            "Creeper Beams" to 6,
            "Quiz" to 10,
            "Higher Or Lower" to 6,
        )
        private val PUZZLE_KEYS = mapOf(
            "Higher Blaze" to "Higher Or Lower",
            "Lower Blaze" to "Higher Or Lower",
        )
        private val COMPLETION_TARGETS = mapOf(
            "Water Board" to arrayOf(BlockTarget(15, 56, 22)),
            "Ice Fill" to arrayOf(BlockTarget(14, 75, 29), BlockTarget(16, 75, 29)),
            "Teleport Maze" to arrayOf(BlockTarget(15, 70, 20)),
            "Three Weirdos" to arrayOf(
                BlockTarget(18, 69, 24),
                BlockTarget(16, 69, 25),
                BlockTarget(14, 69, 24),
            ),
            "Boulder" to arrayOf(BlockTarget(15, 66, 29)),
        )
        private val FAIL_PROMPT = Pattern.compile("^PUZZLE FAIL! (\\w{1,16}) .+$")
        private val ORUO_PROMPT = Pattern.compile(
            "^\\[STATUE] Oruo the Omniscient: (\\w{1,16}) chose the wrong answer! " +
                "I shall never forget this moment of misrememberance\\.$",
        )

        private fun puzzleKey(roomDisplayName: String): String =
            PUZZLE_KEYS.getOrDefault(roomDisplayName, roomDisplayName)

        private fun isBlock(blockId: String?, expected: String): Boolean {
            if (blockId == null) return false
            val normalized = blockId.lowercase(java.util.Locale.ROOT)
            val namespace = normalized.indexOf(':')
            val name = if (namespace >= 0) normalized.substring(namespace + 1) else normalized
            return expected == name
        }

        /** The packet's block identity and room-local target must both match exactly. */
        @JvmStatic
        fun isCompletionTarget(
            roomDisplayName: String?,
            blockId: String?,
            roomX: Int,
            roomY: Int,
            roomZ: Int,
        ): Boolean {
            if (roomDisplayName == null || blockId == null ||
                !blockId.equals(CHERRY_LOG_ID, ignoreCase = true)
            ) {
                return false
            }
            val targets = COMPLETION_TARGETS[roomDisplayName] ?: return false
            return targets.any { it.x == roomX && it.y == roomY && it.z == roomZ }
        }

        /** Applies the message, active puzzle-phase, and exact puzzle-room gates. */
        @JvmStatic
        fun draftPrompt(
            rawMessage: String?,
            enabled: Boolean,
            inPuzzlePhase: Boolean,
            currentRoomIsPuzzle: Boolean,
        ): Optional<DraftPrompt> {
            if (!enabled || !inPuzzlePhase || !currentRoomIsPuzzle || rawMessage == null) {
                return Optional.empty()
            }
            val fail: Matcher = FAIL_PROMPT.matcher(rawMessage)
            if (fail.matches()) {
                return Optional.of(
                    DraftPrompt(fail.group(1), DRAFT_TEXT, DRAFT_HOVER_TEXT, DRAFT_COMMAND),
                )
            }
            val oruo: Matcher = ORUO_PROMPT.matcher(rawMessage)
            if (oruo.matches()) {
                return Optional.of(
                    DraftPrompt(oruo.group(1), DRAFT_TEXT, DRAFT_HOVER_TEXT, DRAFT_COMMAND),
                )
            }
            return Optional.empty()
        }
    }
}
