package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.score.DungeonScoreCore

/**
 * Text, colours and layout of the Map Info panel under the Dungeon Map. Follows the two-row "Full Hud" panel of Odin's
 * `MapInfo` (BSD-3-Clause): 19 units tall, secrets and score on the first row, deaths or unfound secrets, the
 * Mimic/Prince/Bat markers and crypts on the second. Pure functions, so offline checks can run them without Minecraft.
 */
object DungeonMapInfoLogic {
    const val PANEL_HEIGHT = 19
    const val ROW_ONE_Y = 0
    const val ROW_TWO_Y = 10
    const val TEXT_X = 1
    const val MAX_CRYPTS = 5
    const val ROW_ONE_GAP = 6
    const val ROW_TWO_GAP = 4
    const val MIN_FIT_SCALE = 0.3f

    /** Resolved values for one panel frame, read from the latched score state (never unknown, as in Odin). */
    data class Info(
        val secretsFound: Int,
        val secretsPercent: Double,
        val deaths: Int,
        val crypts: Int,
        val mimic: Boolean,
        val prince: Boolean,
        val batKillers: Int,
        val paul: Boolean,
        val floorNumber: Int,
        val requiredPercent: Double,
        val score: Int?,
        val knownSecrets: Int,
    ) {
        val totalSecrets: Int get() = totalSecrets(secretsFound, secretsPercent)
        val neededSecrets: Int
            get() = neededSecrets(totalSecrets, requiredPercent, bonusScore(crypts, mimic, prince, batKillers, paul), deaths)
    }

    /** One drawn string: [x] is the left edge in panel units, [y] the top. */
    data class Line(val text: String, val x: Int, val y: Int)

    /** Panel options; the first two mirror Odin's "Include Remaining" and "Remaining Secrets" settings. */
    data class Options(
        val includeRemaining: Boolean,
        val remainingIsAll: Boolean,
        val showUnfound: Boolean,
    )

    @JvmStatic
    fun info(core: DungeonScoreCore, knownSecrets: Int): Info {
        val floor = core.floor
        return Info(
            secretsFound = core.secretCount,
            secretsPercent = core.secretsPercent.toDouble(),
            deaths = core.deathCount,
            crypts = core.cryptCount,
            mimic = core.mimicKilled,
            prince = core.princeKilled,
            batKillers = core.numBatsKilled,
            paul = core.isPaul,
            floorNumber = floor?.number() ?: 0,
            requiredPercent = if (floor == null) 0.0 else DungeonScoreCore.requiredPercentage(floor).toDouble(),
            score = core.score,
            knownSecrets = knownSecrets,
        )
    }

    /** Sample values for the settings preview, where no run exists. */
    @JvmStatic
    fun sampleInfo(): Info = Info(
        secretsFound = 28, secretsPercent = 80.0, deaths = 1, crypts = 3, mimic = true, prince = false,
        batKillers = 0, paul = false, floorNumber = 7, requiredPercent = 1.0, score = 284, knownSecrets = 30,
    )

    /** Total secrets in the dungeon, derived from the found count and its percentage; zero until the first secret. */
    @JvmStatic
    fun totalSecrets(found: Int, percent: Double): Int =
        if (found <= 0 || percent <= 0.0) 0 else Math.floor(100 / percent.toFloat() * found + 0.5).toInt()

    @JvmStatic
    fun bonusScore(crypts: Int, mimic: Boolean, prince: Boolean, batKillers: Int, paul: Boolean): Int =
        Math.min(crypts, MAX_CRYPTS) + (if (mimic) 2 else 0) + (if (prince) 1 else 0) + batKillers + (if (paul) 10 else 0)

    /** Secrets needed for S+: `ceil(total * required * (40 - bonus + max(2*deaths-1, 0)) / 40)`. */
    @JvmStatic
    fun neededSecrets(total: Int, requiredPercent: Double, bonus: Int, deaths: Int): Int {
        if (requiredPercent <= 0.0) return 0
        val penalty = Math.max(deaths * 2 - 1, 0)
        return Math.ceil((total * requiredPercent.toFloat()) * (40 - bonus + penalty) / 40.0).toInt()
    }

    @JvmStatic
    fun colorScore(score: Int): String = when {
        score < 270 -> "§c$score"
        score < 300 -> "§e$score"
        else -> "§a$score"
    }

    @JvmStatic
    fun colorCrypts(count: Int): String = when {
        count < 3 -> "§c$count"
        count < 5 -> "§e$count"
        else -> "§a$count"
    }

    @JvmStatic
    fun colorDeaths(count: Int, floorNumber: Int): String {
        val early = floorNumber < 6
        return when {
            count == 0 -> "§a0"
            count <= (if (early) 2 else 3) -> "§e$count"
            count == (if (early) 3 else 4) -> "§c$count"
            else -> "§4$count"
        }
    }

    @JvmStatic
    fun secretText(info: Info, options: Options): String = buildString {
        val needed = info.neededSecrets
        append("§7Secrets: §b").append(info.secretsFound)
        if (options.includeRemaining) append("§7-§d").append(Math.max(needed - info.secretsFound, 0))
        append("§7-§e")
        append(if (options.remainingIsAll || options.includeRemaining) needed else Math.max(needed - info.secretsFound, 0))
        append("§7-§c").append(info.totalSecrets)
    }

    @JvmStatic
    fun scoreText(info: Info): String = "§7Score: " + (info.score?.let { colorScore(it) } ?: "§8?")

    @JvmStatic
    fun deathsText(info: Info, options: Options): String =
        if (options.showUnfound) "§7Unfound: §e" + Math.max(info.totalSecrets - info.knownSecrets, 0)
        else "§7Deaths: §c" + colorDeaths(info.deaths, info.floorNumber)

    @JvmStatic
    fun mimicText(info: Info): String = buildString {
        if (info.floorNumber == 6 || info.floorNumber == 7) append(if (info.mimic) "§a" else "§c").append("M §8| ")
        append(if (info.prince) "§a" else "§c").append("P §8| ")
        append(if (info.batKillers > 0) "§a" else "§c").append("B")
    }

    @JvmStatic
    fun cryptsText(info: Info): String = "§7Crypts: " + colorCrypts(Math.min(info.crypts, MAX_CRYPTS))

    /**
     * Text scale that lets both rows fit a panel [width] units wide without touching: 1 when they already fit, smaller
     * when the map is narrow. Odin avoids the overlap with a user-set width; this panel follows the map width instead.
     */
    @JvmStatic
    fun fitScale(width: Int, info: Info, options: Options, measure: (String) -> Int): Float {
        val rowOne = measure(secretText(info, options)) + measure(scoreText(info)) + ROW_ONE_GAP
        val rowTwo = measure(deathsText(info, options)) + measure(mimicText(info)) + measure(cryptsText(info)) +
            2 * ROW_TWO_GAP
        val needed = Math.max(rowOne, rowTwo) + 2 * TEXT_X
        if (needed <= 0 || needed <= width) return 1f
        return Math.max(width.toFloat() / needed, MIN_FIT_SCALE)
    }

    /**
     * Lays the five strings out in a panel [width] units wide. [measure] returns the drawn width of a string
     * (formatting codes excluded). Mirrors Odin: left texts at x=1, right texts end one unit from the edge, and the
     * marker text is centred in the gap between the deaths/unfound text and the crypts text.
     */
    @JvmStatic
    fun layout(width: Int, info: Info, options: Options, measure: (String) -> Int): List<Line> {
        val secrets = secretText(info, options)
        val score = scoreText(info)
        val deaths = deathsText(info, options)
        val marks = mimicText(info)
        val crypts = cryptsText(info)
        val gapStart = TEXT_X + measure(deaths)
        val gapEnd = width - 1 - measure(crypts)
        return listOf(
            Line(secrets, TEXT_X, ROW_ONE_Y),
            Line(score, width - 1 - measure(score), ROW_ONE_Y),
            Line(deaths, TEXT_X, ROW_TWO_Y),
            Line(marks, gapStart + (gapEnd - gapStart - measure(marks)) / 2, ROW_TWO_Y),
            Line(crypts, gapEnd, ROW_TWO_Y),
        )
    }
}
