package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonFloor
import java.util.EnumSet
import java.util.Locale
import java.util.regex.Pattern

/** Parsing and scoring rules behind the stable Java-facing Odin score API. */
object DungeonOdinScoreLogic {
    private val SECRETS_COUNT = Pattern.compile("(?i)^Secrets Found:\\s*(\\d+)\\s*$")
    private val SECRETS_PERCENT = Pattern.compile("(?i)^Secrets Found:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*$")
    private val COMPLETED_ROOMS = Pattern.compile("(?i)^Completed Rooms:\\s*(\\d+)\\s*$")
    private val CLEARED = Pattern.compile("(?i)^Cleared:\\s*([0-9]+)\\s*%\\s*\\(\\s*\\d+\\s*\\).*$")
    private val TEAM_CLEARED = Pattern.compile("(?i)Cleared:\\s*(\\d+)\\s*%\\s*\\(\\s*\\d+\\s*\\)")
    private val TEAM_FLOOR = Pattern.compile("(?i)The Catacombs\\s*\\((Entrance|[FM]?[1-7])\\)")
    private val PUZZLES = Pattern.compile("(?i)^Puzzles:\\s*\\((\\d+)\\)\\s*$")
    private val PUZZLE_STATUS = Pattern.compile("^.+:\\s*\\[([✖✔✦])\\]\\s*(?:\\([^)]*\\))?\\s*$")
    private val DEATHS = Pattern.compile("(?i)^(?:Team )?Deaths:\\s*(\\d+)\\s*$")
    private val CRYPTS = Pattern.compile("(?i)^Crypts:\\s*(\\d+)\\s*$")
    private val BAT_KILLERS = Pattern.compile("(?i)^Bat Killers:\\s*(\\d+)\\s*$")
    private val EXPLICIT_BONUS = Pattern.compile("(?i)^(Mimic|Prince|Paul):\\s*(.+)$")
    private val PARTY_MESSAGE = Pattern.compile("(?i)^Party > (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}): (.+)$")
    private val FORMATTING = Pattern.compile("(?i)[§&][0-9A-FK-OR]")
    private val MIMIC_KILL_LINES = setOf("mimic killed", "mimic slain", "mimic killed!", "mimic dead", "mimic dead!")
    private val PRINCE_KILL_LINES = setOf("prince killed", "prince slain", "prince killed!", "prince dead", "prince dead!")
    private val BAT_KILL_LINES = setOf("bat killed", "bat slain", "bat killed!", "bat dead", "bat dead!")

    @JvmStatic
    fun readTabLines(lines: Iterable<String?>?): DungeonOdinScoreCalculator.TabStats {
        if (lines == null) return unavailableTabStats()
        var secretsFound: Int? = null
        var secretsPercent: Double? = null
        var completedRooms: Int? = null
        var clearedPercent: Double? = null
        var puzzleCount: Int? = null
        var completedPuzzles = 0
        var observedPuzzleStatusRows = 0
        var deaths: Int? = null
        var crypts: Int? = null
        var batKillers: Int? = null
        var mimic: Boolean? = null
        var prince: Boolean? = null
        var paul: Boolean? = null

        for (raw in lines) {
            val line = stripFormatting(raw ?: "").trim()
            var matcher = SECRETS_COUNT.matcher(line)
            if (matcher.matches()) secretsFound = integer(matcher.group(1))
            matcher = SECRETS_PERCENT.matcher(line)
            if (matcher.matches()) secretsPercent = decimal(matcher.group(1))
            matcher = COMPLETED_ROOMS.matcher(line)
            if (matcher.matches()) completedRooms = integer(matcher.group(1))
            matcher = CLEARED.matcher(line)
            if (matcher.matches()) clearedPercent = decimal(matcher.group(1))
            matcher = PUZZLES.matcher(line)
            if (matcher.matches()) puzzleCount = integer(matcher.group(1))
            else {
                matcher = PUZZLE_STATUS.matcher(line)
                if (matcher.matches()) {
                    observedPuzzleStatusRows++
                    if (matcher.group(1) == "✔") completedPuzzles++
                }
            }
            matcher = DEATHS.matcher(line)
            if (matcher.matches()) deaths = integer(matcher.group(1))
            matcher = CRYPTS.matcher(line)
            if (matcher.matches()) crypts = integer(matcher.group(1))
            matcher = BAT_KILLERS.matcher(line)
            if (matcher.matches()) batKillers = integer(matcher.group(1))
            matcher = EXPLICIT_BONUS.matcher(line)
            if (matcher.matches()) {
                val found = affirmative(matcher.group(2))
                when (matcher.group(1).lowercase(Locale.ROOT)) {
                    "mimic" -> mimic = found
                    "prince" -> prince = found
                    "paul" -> paul = found
                }
            }
        }
        return DungeonOdinScoreCalculator.TabStats(
            secretsFound, secretsPercent, completedRooms, clearedPercent, puzzleCount,
            completedPuzzles, observedPuzzleStatusRows, deaths, crypts, mimic, prince, batKillers, paul
        )
    }

    @JvmStatic
    fun withBonusSignals(
        tab: DungeonOdinScoreCalculator.TabStats?,
        signals: DungeonOdinScoreCalculator.BonusSnapshot?
    ): DungeonOdinScoreCalculator.TabStats {
        val source = tab ?: unavailableTabStats()
        val observed = signals ?: DungeonOdinScoreCalculator.BonusSnapshot.UNKNOWN
        return DungeonOdinScoreCalculator.TabStats(
            source.secretsFound, source.secretsPercent, source.completedRooms, source.clearedPercent,
            source.puzzleCount, source.completedPuzzles, source.observedPuzzleStatusRows, source.deaths,
            source.crypts, source.mimic ?: observed.mimic, source.prince ?: observed.prince,
            source.batKillers ?: observed.batKillers, source.paul ?: observed.paul
        )
    }

    @JvmStatic
    fun withTeamSignals(
        tab: DungeonOdinScoreCalculator.TabStats?,
        team: DungeonOdinScoreCalculator.TeamSnapshot?
    ): DungeonOdinScoreCalculator.TabStats {
        val source = tab ?: unavailableTabStats()
        val clearedPercent = team?.clearedPercent ?: return source
        return DungeonOdinScoreCalculator.TabStats(
            source.secretsFound, source.secretsPercent, source.completedRooms, clearedPercent,
            source.puzzleCount, source.completedPuzzles, source.observedPuzzleStatusRows, source.deaths,
            source.crypts, source.mimic, source.prince, source.batKillers, source.paul
        )
    }

    @JvmStatic
    fun calculate(
        floor: DungeonFloor?,
        stats: DungeonOdinScoreCalculator.TabStats?,
        bloodDone: Boolean?,
        inBoss: Boolean
    ): DungeonOdinScoreCalculator.ScoreEstimate {
        val missing = EnumSet.noneOf(DungeonOdinScoreCalculator.Input::class.java)
        if (floor == null) missing.add(DungeonOdinScoreCalculator.Input.FLOOR)
        val observed = stats ?: unavailableTabStats()
        val secretsFound = observed.secretsFound
        val secretsPercent = observed.secretsPercent
        val completedRooms = observed.completedRooms
        val puzzleCount = observed.puzzleCount
        val deaths = observed.deaths
        val clearedPercent = observed.clearedPercent

        if (secretsFound == null) missing.add(DungeonOdinScoreCalculator.Input.SECRETS_FOUND)
        if (secretsFound != null && secretsFound > 0 && secretsPercent == null)
            missing.add(DungeonOdinScoreCalculator.Input.SECRETS_PERCENT)
        if (completedRooms == null) missing.add(DungeonOdinScoreCalculator.Input.COMPLETED_ROOMS)
        if (puzzleCount == null) missing.add(DungeonOdinScoreCalculator.Input.PUZZLE_COUNT)
        if (deaths == null) missing.add(DungeonOdinScoreCalculator.Input.DEATHS)
        if (bloodDone == null && !inBoss) missing.add(DungeonOdinScoreCalculator.Input.BLOOD_STATUS)
        if (floor != null && requiredSecretPercentage(floor) <= 0.0)
            missing.add(DungeonOdinScoreCalculator.Input.FLOOR)
        if (missing.isNotEmpty()) return unavailableScore(missing)

        val found = secretsFound!!
        val rooms = completedRooms!!
        val puzzles = puzzleCount!!
        val deathCount = deaths!!
        if (found < 0 || rooms < 0 || puzzles < 0 || deathCount < 0 ||
            (clearedPercent != null && (clearedPercent < 0.0 || clearedPercent > 100.0))) {
            return unavailableScore(setOf(DungeonOdinScoreCalculator.Input.INVALID_TAB_VALUE))
        }

        var totalSecrets = 0
        if (found > 0) {
            val percent = secretsPercent!!
            if (percent <= 0.0 || percent > 100.0)
                return unavailableScore(setOf(DungeonOdinScoreCalculator.Input.INVALID_TAB_VALUE))
            totalSecrets = kotlin.math.floor(100.0 / percent * found + 0.5).toInt()
        }

        val secretRequirement = requiredSecretPercentage(floor)
        val didBlood = bloodDone == true || inBoss
        val completed = rooms + (if (didBlood) 0 else 1) + (if (inBoss) 0 else 1)
        var totalRooms = if (completed != 0 && clearedPercent != null && clearedPercent != 0.0)
            kotlin.math.floor(completed / (clearedPercent * 0.01) + 0.4).toInt()
        else 0
        if (totalRooms == 0) totalRooms = 36

        val secretScore = if (totalSecrets > 0)
            clamp(kotlin.math.floor(found / (totalSecrets * secretRequirement) * 40.0).toInt(), 0, 40)
        else 0
        val exploration = secretScore + clamp(kotlin.math.floor(completed / totalRooms.toDouble() * 60.0).toInt(), 0, 60)
        val skillRooms = clamp(kotlin.math.floor(completed / totalRooms.toDouble() * 80.0).toInt(), 0, 80)
        val completedPuzzles = observed.completedPuzzles.coerceIn(0, puzzles)
        val observedPuzzles = observed.observedPuzzleStatusRows.coerceIn(0, puzzles)
        val minIncompletePuzzles = (observedPuzzles - completedPuzzles).coerceAtLeast(0)
        val maxIncompletePuzzles = (puzzles - completedPuzzles).coerceAtLeast(minIncompletePuzzles)
        if (observedPuzzles < puzzles) missing.add(DungeonOdinScoreCalculator.Input.PUZZLE_STATUS_ROWS)
        val deathPenalty = (deathCount * 2 - 1).coerceAtLeast(0)
        val minimumSkill = clamp(20 + skillRooms - maxIncompletePuzzles * 10 - deathPenalty, 20, 100)
        val maximumSkill = clamp(20 + skillRooms - minIncompletePuzzles * 10 - deathPenalty, 20, 100)

        val unknownBonuses = EnumSet.noneOf(DungeonOdinScoreCalculator.Input::class.java)
        val minimumBonus = bonusFloor(observed, unknownBonuses)
        val maximumBonus = bonusCeiling(observed)
        unknownBonuses.addAll(missing)
        val base = exploration + 100
        return DungeonOdinScoreCalculator.ScoreEstimate(
            base + minimumSkill + minimumBonus, base + maximumSkill + maximumBonus, unknownBonuses
        )
    }

    @JvmStatic
    fun requiredSecretPercentage(floor: DungeonFloor?): Double {
        if (floor == null) return 0.0
        if (floor.master() || floor.number() >= 7) return 1.0
        return when (floor.number()) {
            0, 1 -> 0.30
            2 -> 0.40
            3 -> 0.50
            4 -> 0.60
            5 -> 0.70
            6 -> 0.85
            else -> 0.0
        }
    }

    private fun bonusFloor(
        stats: DungeonOdinScoreCalculator.TabStats,
        missing: MutableSet<DungeonOdinScoreCalculator.Input>
    ): Int {
        var bonus = 0
        val crypts = stats.crypts
        val mimic = stats.mimic
        val prince = stats.prince
        val batKillers = stats.batKillers
        val paul = stats.paul
        if (crypts == null) missing.add(DungeonOdinScoreCalculator.Input.CRYPTS)
        else bonus += crypts.coerceIn(0, 5)
        if (mimic == null) missing.add(DungeonOdinScoreCalculator.Input.MIMIC) else if (mimic) bonus += 2
        if (prince == null) missing.add(DungeonOdinScoreCalculator.Input.PRINCE) else if (prince) bonus += 1
        if (batKillers == null) missing.add(DungeonOdinScoreCalculator.Input.BAT_KILLERS)
        else bonus += batKillers.coerceIn(0, 5)
        if (paul == null) missing.add(DungeonOdinScoreCalculator.Input.PAUL) else if (paul) bonus += 10
        return bonus
    }

    private fun bonusCeiling(stats: DungeonOdinScoreCalculator.TabStats): Int {
        val crypts = stats.crypts
        val mimic = stats.mimic
        val prince = stats.prince
        val batKillers = stats.batKillers
        val paul = stats.paul
        var bonus = crypts?.coerceIn(0, 5) ?: 5
        bonus += if (mimic == null || mimic) 2 else 0
        bonus += if (prince == null || prince) 1 else 0
        bonus += batKillers?.coerceIn(0, 5) ?: 5
        bonus += if (paul == null || paul) 10 else 0
        return bonus
    }

    private fun integer(value: String): Int? = value.toIntOrNull()

    private fun decimal(value: String): Double? = value.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun affirmative(value: String?): Boolean? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "yes", "true", "1", "found", "killed", "complete", "active", "✔", "✓" -> true
        "no", "false", "0", "not found", "not killed", "incomplete", "inactive", "✖", "✦" -> false
        else -> null
    }

    internal fun stripFormatting(value: String): String = FORMATTING.matcher(value).replaceAll("")

    private fun clamp(value: Int, min: Int, max: Int) = maxOf(min, minOf(max, value))

    private fun unavailableTabStats() = DungeonOdinScoreCalculator.TabStats(
        null, null, null, null, null, 0, 0, null, null, null, null, null, null
    )

    private fun unavailableScore(missing: Set<DungeonOdinScoreCalculator.Input>) =
        DungeonOdinScoreCalculator.ScoreEstimate(null, null, missing)
}

/** Run-scoped bonus and team-prefix signals used by the Java compatibility facade. */
class DungeonOdinRunSignalsCore {
    private var mimic: Boolean? = null
    private var prince: Boolean? = null
    private val batKillers = mutableSetOf<String>()
    private var batKillersKnown = false
    private var paul: Boolean? = null
    private var teamFloor: DungeonFloor? = null
    private var clearedPercent: Double? = null

    fun resetUnknown() {
        mimic = null
        prince = null
        batKillers.clear()
        batKillersKnown = false
        paul = null
        teamFloor = null
        clearedPercent = null
    }

    fun beginRun() {
        mimic = false
        prince = false
        batKillers.clear()
        batKillersKnown = true
        paul = null
        clearedPercent = null
    }

    fun observeTeamUpdate(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = DungeonOdinScoreLogic.stripFormatting(text).trim()
        var changed = false
        val floorMatcher = TEAM_FLOOR.matcher(normalized)
        if (floorMatcher.find()) {
            val parsed = DungeonFloor.parse(floorMatcher.group(1))
            if (parsed != null && parsed != teamFloor) {
                teamFloor = parsed
                changed = true
            }
        }
        val clearMatcher = TEAM_CLEARED.matcher(normalized)
        if (clearMatcher.find()) {
            val parsed = clearMatcher.group(1).toIntOrNull()?.toDouble()
            if (parsed != null && parsed in 0.0..100.0 && parsed != clearedPercent) {
                clearedPercent = parsed
                changed = true
            }
        }
        return changed
    }

    fun observePartyMessage(line: String?, floor: DungeonFloor?): Boolean {
        if (line == null || floor == null) return false
        val matcher = PARTY_MESSAGE.matcher(DungeonOdinScoreLogic.stripFormatting(line).trim())
        if (!matcher.matches()) return false
        val player = matcher.group(1).lowercase(Locale.ROOT)
        val message = matcher.group(2).trim().lowercase(Locale.ROOT)
        if (message in MIMIC_KILL_LINES && (floor.number() == 6 || floor.number() == 7)) {
            mimic = true
            return true
        }
        if (message in PRINCE_KILL_LINES) {
            prince = true
            return true
        }
        if (message in BAT_KILL_LINES) {
            batKillersKnown = true
            return batKillers.add(player)
        }
        return false
    }

    fun observePaul(active: Boolean?) {
        paul = active
    }

    fun teamSnapshot() = DungeonOdinScoreCalculator.TeamSnapshot(teamFloor, clearedPercent)

    fun snapshot(): DungeonOdinScoreCalculator.BonusSnapshot {
        val batCount = if (batKillersKnown) batKillers.size else null
        return DungeonOdinScoreCalculator.BonusSnapshot(mimic, prince, batCount, paul)
    }

    private companion object {
        val TEAM_CLEARED = Pattern.compile("(?i)Cleared:\\s*(\\d+)\\s*%\\s*\\(\\s*\\d+\\s*\\)")
        val TEAM_FLOOR = Pattern.compile("(?i)The Catacombs\\s*\\((Entrance|[FM]?[1-7])\\)")
        val PARTY_MESSAGE = Pattern.compile("(?i)^Party > (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}): (.+)$")
        val MIMIC_KILL_LINES = setOf("mimic killed", "mimic slain", "mimic killed!", "mimic dead", "mimic dead!")
        val PRINCE_KILL_LINES = setOf("prince killed", "prince slain", "prince killed!", "prince dead", "prince dead!")
        val BAT_KILL_LINES = setOf("bat killed", "bat slain", "bat killed!", "bat dead", "bat dead!")
    }
}
