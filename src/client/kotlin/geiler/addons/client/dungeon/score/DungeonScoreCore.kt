package geiler.addons.client.dungeon.score

import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.tree.ChatText
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor as mathFloor

/**
 * Latched dungeon score state, a behavior port of Odin's `DungeonListener` stats and `DungeonUtils.updateScore`
 * (https://github.com/odtheking/Odin, BSD-3-Clause; written independently, no code pasted). Values are kept until a
 * newer row arrives, so a tab list that briefly lacks a row never makes the score unknown. Pure Kotlin: the
 * Minecraft hooks live in [DungeonScoreState].
 */
class DungeonScoreCore {
    enum class PuzzleStatus { COMPLETED, FAILED, INCOMPLETE }

    /** Called with the new score whenever it changes. */
    var onScore: ((Int) -> Unit)? = null

    var floor: DungeonFloor? = null
        private set
    var inBoss: Boolean = false
        private set

    /** Paul's +10 as detected by [DungeonPaulLookup]. */
    var paul: Boolean
        get() = paulValue
        set(value) {
            if (paulValue == value) return
            paulValue = value
            updateScore()
        }
    private var paulValue = false

    /** The user's YES/NO choice; null means "use the detected value". */
    var paulOverride: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            updateScore()
        }

    var secretCount = 0
        private set
    var secretsPercent = 0f
        private set

    /** Secrets in rooms the player has seen; set by the map code, only used for the "Unfound" display. */
    var knownSecrets = 0
    var cryptCount = 0
        private set
    var openedRooms = 0
        private set
    var completedRooms = 0
        private set
    var deathCount = 0
        private set
    var percentCleared = 0
        private set
    var elapsedTime = "0s"
        private set
    var mimicKilled = false
        private set
    var princeKilled = false
        private set
    var bloodDone = false
        private set
    var puzzleCount = 0
        private set
    var score = 0
        private set

    private val batKillers = LinkedHashSet<String>()
    private val puzzles = LinkedHashMap<String, PuzzleStatus>()
    private var expectingBloodUpdate = false

    val numBatsKilled: Int get() = batKillers.size
    val isPaul: Boolean get() = paulOverride ?: paul

    val totalSecrets: Int
        get() = if (secretCount == 0 || secretsPercent == 0f) 0
        else mathFloor(100 / secretsPercent * secretCount + 0.5).toInt()

    val totalRooms: Int
        get() = if (completedRooms == 0 || percentCleared == 0) 0
        else mathFloor((completedRooms / (percentCleared * 0.01).toFloat()) + 0.4).toInt()

    val bonusScore: Int
        get() {
            var bonus = cryptCount.coerceAtMost(5)
            if (mimicKilled) bonus += 2
            if (princeKilled) bonus += 1
            bonus += numBatsKilled
            if (isPaul) bonus += 10
            return bonus
        }

    /** Secrets needed for S+, as Odin's `neededSecretsAmount`; 0 while the floor is unknown. */
    val neededSecrets: Int
        get() = floor?.let {
            ceil(
                (totalSecrets * requiredPercentage(it)) * (40 - bonusScore + (deathCount * 2 - 1).coerceAtLeast(0)) / 40.0,
            ).toInt()
        } ?: 0

    fun isFloor(vararg numbers: Int): Boolean = floor?.number()?.let { it in numbers } ?: false

    /** Tab-list rows (display names), as Odin's player-info handler: stats, then puzzles, then the score. */
    fun onTabRows(rows: List<String>) {
        for (raw in rows) {
            val entry = ChatText.plain(raw)
            SECRET_PERCENT.find(entry)?.groupValues?.get(1)?.toFloatOrNull()?.let { secretsPercent = it }
            COMPLETED_ROOMS.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { completedRooms = it }
            SECRET_COUNT.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { secretCount = it }
            OPENED_ROOMS.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { openedRooms = it }
            PUZZLE_COUNT.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { puzzleCount = it }
            DEATHS.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { deathCount = it }
            CRYPTS.find(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { cryptCount = it }
            TIME.find(entry)?.groupValues?.get(1)?.let { elapsedTime = it }
            readPuzzle(entry)
        }
        updateScore()
    }

    /** Team prefix plus suffix text. Returns true when this text revealed the floor for the first time. */
    fun onTeamText(raw: String): Boolean {
        val text = ChatText.plain(raw)
        var floorDetected = false
        FLOOR.find(text)?.groupValues?.get(1)?.let { label ->
            if (floor == null) floorDetected = adoptFloor(parseFloor(label))
        }
        CLEARED.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (percentCleared != it && expectingBloodUpdate) bloodDone = true
            percentCleared = it
            updateScore()
        }
        TIME.find(text)?.groupValues?.get(1)?.let { elapsedTime = it }
        return floorDetected
    }

    /** Sets the floor if none is known yet (Odin reads it once per world). True when it was set now. */
    fun adoptFloor(candidate: DungeonFloor?): Boolean {
        if (candidate == null || floor != null) return false
        floor = candidate
        updateScore()
        return true
    }

    /** Plain chat lines: the Watcher cue arms blood detection, party lines report Mimic, Prince, bats and Blaze. */
    fun onChat(rawMessage: String) {
        val message = ChatText.plain(rawMessage).trim()
        if (EXPECTING_BLOOD.matches(message)) expectingBloodUpdate = true
        val party = PARTY.find(message) ?: return
        when (party.groupValues[2].lowercase(Locale.ROOT)) {
            "mimic killed", "mimic slain", "mimic killed!", "mimic dead", "mimic dead!" ->
                if (isFloor(6, 7)) mimicKilled = true
            "prince killed", "prince slain", "prince killed!", "prince dead", "prince dead!" -> princeKilled = true
            "bat killed", "bat slain", "bat killed!", "bat dead", "bat dead!" -> batKillers.add(party.groupValues[1])
            "blaze done!", "blaze done", "blaze puzzle solved!" ->
                if (puzzles.containsKey(BLAZE)) puzzles[BLAZE] = PuzzleStatus.COMPLETED
            else -> return
        }
        updateScore()
    }

    fun setBoss(value: Boolean) {
        if (inBoss == value) return
        inBoss = value
        updateScore()
    }

    /** Forgets the whole run (new world). The score goes back to 0 without a callback. */
    fun reset() {
        floor = null
        inBoss = false
        paulValue = false
        secretCount = 0
        secretsPercent = 0f
        knownSecrets = 0
        cryptCount = 0
        openedRooms = 0
        completedRooms = 0
        deathCount = 0
        percentCleared = 0
        elapsedTime = "0s"
        mimicKilled = false
        princeKilled = false
        bloodDone = false
        puzzleCount = 0
        score = 0
        batKillers.clear()
        puzzles.clear()
        expectingBloodUpdate = false
    }

    /** One-line state for the bounded diagnostics log. */
    fun summary(): String =
        "floor=${floor?.displayName() ?: "none"} score=$score secrets=$secretCount/${totalSecrets}@$secretsPercent " +
            "rooms=$completedRooms/$totalRooms cleared=$percentCleared deaths=$deathCount crypts=$cryptCount " +
            "puzzles=${puzzles.count { it.value == PuzzleStatus.COMPLETED }}/$puzzleCount mimic=$mimicKilled " +
            "prince=$princeKilled bats=$numBatsKilled paul=$isPaul blood=$bloodDone boss=$inBoss"

    private fun readPuzzle(entry: String) {
        val match = PUZZLE.find(entry) ?: return
        val name = match.groupValues[1]
        if (name !in PUZZLE_NAMES) return
        val status = when (match.groupValues[2]) {
            "✖" -> PuzzleStatus.FAILED
            "✔" -> PuzzleStatus.COMPLETED
            "✦" -> PuzzleStatus.INCOMPLETE
            else -> return
        }
        puzzles[name] = status
    }

    private fun updateScore() {
        val completed = completedRooms + (if (!bloodDone) 1 else 0) + (if (!inBoss) 1 else 0)
        val total = if (totalRooms != 0) totalRooms else 36

        val exploration = floor?.let {
            val secretScore = if (totalSecrets > 0) {
                mathFloor(secretCount.toDouble() / (totalSecrets.toDouble() * requiredPercentage(it)) * 40.0)
                    .toInt().coerceIn(0, 40)
            } else 0
            secretScore + mathFloor(completed.toFloat() / total * 60f).coerceIn(0f, 60f).toInt()
        } ?: 0

        val skillRooms = mathFloor(completed.toFloat() / total * 80f).coerceIn(0f, 80f).toInt()
        val puzzlePenalty = (puzzleCount - puzzles.count { it.value == PuzzleStatus.COMPLETED }) * 10

        val newScore = exploration + (20 + skillRooms - puzzlePenalty - (deathCount * 2 - 1).coerceAtLeast(0))
            .coerceIn(20, 100) + bonusScore + 100

        if (newScore == score) return
        score = newScore
        onScore?.invoke(newScore)
    }

    companion object {
        private const val BLAZE = "Higher Or Lower"

        /** The puzzle names Odin knows (its `Puzzle` enum without the unknown `???`). */
        private val PUZZLE_NAMES = setOf(
            BLAZE, "Creeper Beams", "Three Weirdos", "Tic Tac Toe", "Water Board", "Teleport Maze", "Boulder",
            "Ice Fill", "Ice Path", "Quiz",
        )

        // Odin's patterns, with the fixed leading space of tab rows made optional-tolerant (`\s*`).
        private val PUZZLE = Regex("^\\s*(\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?\\s*$")
        private val EXPECTING_BLOOD = Regex("^\\[BOSS] The Watcher: You have proven yourself. You may pass.")
        private val SECRET_PERCENT = Regex("^\\s*Secrets Found: ([\\d.]+)%\\s*$")
        private val TIME = Regex("^\\s*Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)\\s*$")
        private val COMPLETED_ROOMS = Regex("^\\s*Completed Rooms: (\\d+)\\s*$")
        private val CLEARED = Regex("^\\s*Cleared: (\\d+)% \\(\\d+\\)\\s*$")
        private val SECRET_COUNT = Regex("^\\s*Secrets Found: (\\d+)\\s*$")
        private val OPENED_ROOMS = Regex("^\\s*Opened Rooms: (\\d+)\\s*$")
        private val FLOOR = Regex("The Catacombs \\((\\w+)\\)\\s*$")
        private val PARTY = Regex("^Party > (?:\\[[^]]+] )*(\\w{1,16}): (.+)$")
        private val PUZZLE_COUNT = Regex("^\\s*Puzzles: \\((\\d+)\\)\\s*$")
        private val DEATHS = Regex("^\\s*Team Deaths: (\\d+)\\s*$")
        private val CRYPTS = Regex("^\\s*Crypts: (\\d+)\\s*$")

        private fun parseFloor(label: String): DungeonFloor? =
            if (label == "E") DungeonFloor.ENTRANCE else DungeonFloor.parse(label)

        /** Share of secrets the floor requires for S+ (Odin's `Floor.requiredPercentage`). */
        @JvmStatic
        fun requiredPercentage(floor: DungeonFloor?): Float = when (floor) {
            DungeonFloor.ENTRANCE, DungeonFloor.F1 -> 0.3f
            DungeonFloor.F2 -> 0.4f
            DungeonFloor.F3 -> 0.5f
            DungeonFloor.F4 -> 0.6f
            DungeonFloor.F5 -> 0.7f
            DungeonFloor.F6 -> 0.85f
            else -> 1f
        }

        /** True once the player stands inside the boss room of floor [floorNumber] (Odin's `getBoss`). */
        @JvmStatic
        fun inBossAt(floorNumber: Int, x: Double, z: Double): Boolean = when (floorNumber) {
            1 -> x > -71 && z > -39
            in 2..4 -> x > -39 && z > -39
            in 5..6 -> x > -39 && z > -7
            7 -> x > -7 && z > -7
            else -> false
        }
    }
}
