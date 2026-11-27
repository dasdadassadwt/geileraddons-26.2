package geiler.addons.client.foraging

/**
 * Pure Foraging rules with no Minecraft state, so the offline checks can prove them. The behavior follows what
 * Skyblocker's Foraging features do (LGPL-3.0, read 2026-11-18, behavior only); the code is local.
 */
object ForagingRules {
    /** SkyBlock year 1 starts here (2019-06-11 17:55:00 UTC, per the Hypixel SkyBlock wiki); a day is 20 real minutes. */
    const val SKYBLOCK_EPOCH_SECONDS = 1_560_275_700L
    const val DAY_SECONDS = 1200

    /** Seconds until the next SkyBlock day starts, 1..1200. */
    @JvmStatic
    fun secondsToNextDay(epochMillis: Long): Int {
        val sinceEpoch = Math.floorDiv(epochMillis, 1000L) - SKYBLOCK_EPOCH_SECONDS
        return DAY_SECONDS - Math.floorMod(sinceEpoch, DAY_SECONDS.toLong()).toInt()
    }

    /** Whether cached sweep targets are stale: the aimed log changed, or [ticksSince] reached [interval]. */
    @JvmStatic
    fun recollect(sameTarget: Boolean, ticksSince: Int, interval: Int): Boolean = !sameTarget || ticksSince >= interval

    /** Sea Lumies (sea pickles) matter only while swimming, so the scan and the boxes both wait for water. */
    @JvmStatic
    fun seaLumiesActive(enabled: Boolean, inWater: Boolean): Boolean = enabled && inWater

    /** Whether (x, y, z) is within [range] blocks of any anchor, each `{x, y, z}`. */
    @JvmStatic
    fun withinAny(x: Double, y: Double, z: Double, anchors: Array<DoubleArray>, range: Double): Boolean =
        anchors.any { val dx = it[0] - x; val dy = it[1] - y; val dz = it[2] - z; dx * dx + dy * dy + dz * dz <= range * range }

    /**
     * The Agatha's Contest tab widget as one line: the text after its header colon plus the rows under it up to the next
     * widget, or null when it is not listed. Hypixel words the rows itself, so they are shown as written.
     */
    @JvmStatic
    fun agathaContest(tabLines: List<String>): String? {
        val rows = tabLines.map { geiler.addons.client.tree.ChatText.stripForMatch(it).trim() }
        val start = rows.indexOfFirst { it.lowercase().startsWith("agatha's contest:") }
        if (start < 0) return null
        val parts = ArrayList<String>()
        rows[start].substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        for (row in rows.drop(start + 1).take(4)) {
            if (row.isEmpty() || geiler.addons.client.farming.TabWidgetText.isOtherWidget(row.lowercase())) break
            parts += row
        }
        return parts.joinToString(" ").ifEmpty { null }
    }

    @JvmStatic
    fun clock(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    // ---- highlights ---------------------------------------------------------------------------

    @JvmStatic
    fun seaPickleQualifies(pickles: Int, minimum: Int): Boolean = pickles >= minimum

    const val MAX_HONEY_LEVEL = 5

    /** A full bee nest that does not hang under a birch fence (those belong to protected trees). */
    @JvmStatic
    fun honeyhiveQualifies(honeyLevel: Int, underBirchFence: Boolean): Boolean =
        honeyLevel == MAX_HONEY_LEVEL && !underBirchFence

    // ---- temple puzzles -----------------------------------------------------------------------

    /** Horizontal directions are numbered clockwise: 0 north, 1 east, 2 south, 3 west. */
    @JvmStatic
    fun forestFloorTarget(wallFacing: Int): Int = when (Math.floorMod(wallFacing, 4)) {
        0 -> 3
        1 -> 2
        2 -> 1
        else -> 0
    }

    /** Clicks to turn [floor] into [target]: positive clockwise, negative counter-clockwise, never more than two. */
    @JvmStatic
    fun rotations(floor: Int, target: Int): Int {
        val clockwise = Math.floorMod(target - floor, 4)
        return if (clockwise == 3) -1 else clockwise
    }

    /** Glass colors (any stable int key) from the fewest blocks to the most; ties keep key order. */
    @JvmStatic
    fun buttonOrder(counts: Map<Int, Int>): List<Int> =
        counts.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }

    // ---- tuner --------------------------------------------------------------------------------

    private val DYES = listOf(
        "magenta_dye", "light_blue_dye", "yellow_dye", "lime_dye", "pink_dye", "cyan_dye", "purple_dye",
        "lapis_lazuli", "cocoa_beans", "green_dye", "red_dye", "bone_meal", "orange_dye",
    )
    private val PANES = listOf(
        "magenta", "light_blue", "yellow", "lime", "pink", "cyan", "purple", "blue", "brown", "green", "red",
        "white", "orange",
    ).map { it + "_stained_glass_pane" }
    const val COLOR_CYCLE = 13
    const val SPEED_CYCLE = 5
    const val PITCH_CYCLE = 3
    private val PITCH_VALUES = floatArrayOf(0.0952381f, 0.7936508f, 1.4920635f)
    private val PITCH_NAMES = listOf("Low", "Normal", "High")

    /** Server ticks between two moves of the target pane, for speeds 1..5 (SkyHanni's measured values). */
    private val SPEED_TICKS = doubleArrayOf(52.0, 42.0, 32.0, 22.0, 12.0)
    private const val SPEED_TOLERANCE = 6.0

    @JvmStatic
    fun colorIndex(itemPath: String): Int = DYES.indexOf(itemPath)

    @JvmStatic
    fun paneIndex(itemPath: String): Int = PANES.indexOf(itemPath)

    /** Shortest signed distance around a cycle; a tie goes forward. */
    @JvmStatic
    fun cycleClicks(from: Int, to: Int, length: Int): Int {
        val forward = Math.floorMod(to - from, length)
        val backward = length - forward
        return if (forward <= backward) forward else -backward
    }

    /** The speed (1..5) nearest to a target pane moving every [ticks] server ticks, or -1. */
    @JvmStatic
    fun speedFromTicks(ticks: Int): Int = speedFromTicks(ticks.toDouble())

    fun speedFromTicks(ticks: Double): Int {
        val index = SPEED_TICKS.indices.minBy { Math.abs(SPEED_TICKS[it] - ticks) }
        return if (Math.abs(SPEED_TICKS[index] - ticks) > SPEED_TOLERANCE) -1 else index + 1
    }

    @JvmStatic
    fun pitchIndex(pitch: Float): Int = PITCH_VALUES.indexOfFirst { Math.abs(it - pitch) < 0.0001f }

    @JvmStatic
    fun pitchFromLore(line: String): Int = PITCH_NAMES.indexOfFirst { line.contains(it) }

    private val SPEED_LINE = Regex("speed:\\s*(\\d)", RegexOption.IGNORE_CASE)

    /** The speed written on the speed button, 1..5, or 0. */
    @JvmStatic
    fun speedFromLore(line: String): Int {
        val value = SPEED_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return if (value in 1..SPEED_CYCLE) value else 0
    }

    // ---- sweep --------------------------------------------------------------------------------

    @JvmField
    val FIG_SWEEP = listOf(7, 9, 11, 16, 21, 26, 34, 42, 50, 62, 74, 86, 102, 118, 134, 154, 174, 194, 214, 234)

    @JvmField
    val MANGROVE_SWEEP = listOf(
        50, 55, 60, 75, 90, 105, 130, 155, 180, 230, 280, 330, 405, 480, 555, 655, 755, 855, 955, 1055,
    )

    @JvmField
    val HELIX_SWEEP = listOf(
        150, 160, 170, 200, 230, 260, 320, 380, 440, 540, 640, 740, 890, 1040, 1190, 1390, 1590, 1790, 1990, 2190,
    )
    const val BASIC_LOG_CAP = 36

    /** Logs one swing cuts: every threshold the Sweep stat reaches adds one. */
    @JvmStatic
    fun maxLogs(sweep: Float, thresholds: List<Int>): Int = thresholds.count { sweep >= it }

    /** Hub and Park trees: one log plus one per Sweep point, capped. */
    @JvmStatic
    fun basicMaxLogs(sweep: Float): Int = (1 + sweep).toInt().coerceIn(0, BASIC_LOG_CAP)

    /** A thrown axe cuts half as many logs. */
    @JvmStatic
    fun thrownMaxLogs(max: Int): Int = max / 2
}
