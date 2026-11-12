package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Optional
import java.util.regex.Pattern

/** Reads and parses the already-ordered Hypixel tab-list Pests plot row. */
object TabWidgetText {
    /** The widget's own header, or the start of a line such as `Pests: 3`. */
    private const val PESTS_HEADER = "pests:"

    /** The authoritative row: every infested plot id, or an explicit spelling of no plots. */
    private val PESTS_IN_PLOTS = Pattern.compile("^plots:\\s*(.*)$")
    private val NO_PLOTS = Pattern.compile("(?i)^(?:none|n/a|-*)$")

    /** One plot id, bounded so a stray word cannot reach the parser. */
    private val PLOT_ID = Pattern.compile("^\\d{1,2}$")

    /** Java's `String.split(",")` drops trailing empty tokens; keep that exact delimiter behavior. */
    private val PLOT_SEPARATOR = Pattern.compile(",")

    /**
     * Every other widget's header, so the end of the `Pests` block can be found. These delimit the
     * block rather than being read, and are matched as lowercase prefixes.
     */
    private val OTHER_WIDGETS = arrayOf(
        "players (", "area:", "dungeon:", "server:", "gems:", "fairy souls:", "profile:",
        "sb level:", "bank:", "interest:", "soulflow:", "pet:", "pet training:", "kat:",
        "fire sales:", "election:", "event:", "skills:", "stats:", "guests (", "coop ",
        "minions:", "island closes in:", "north stars:", "collection:", "jacob's contest:",
        "slayer:", "daily quests:", "active effects:", "bestiary:", "essence:", "forges:",
        "timers:", "opened rooms:", "party:", "trapper:", "commissions:", "powders:",
        "crystals:", "unclaimed chests:", "thunder:", "rain:", "broodmother:", "eyes placed:",
        "protector:", "dragon:", "volcano:", "reputation:", "faction quests:", "trophy fish:",
        "good to know:", "shen:", "advertisement:", "composter:", "garden level:", "copper:",
        "sowdust:", "pest traps:", "full traps:", "no bait:", "visitors (", "crop milestones:",
        "frozen corpses:", "scrap:", "event trackers:", "agatha's contest:", "miria's contest:",
        "salts:", "starborn temple:", "pity:", "pickaxe ability:",
    )

    /** Headers that stand alone, so a name merely starting with them cannot match. */
    private val OTHER_WIDGET_WORDS = arrayOf("info", "island", "puzzle", "dungeon stats", "shard traps")

    /**
     * Reads one already-ordered tab list. Hypixel's `Plots:` row is the authoritative snapshot;
     * some tab layouts omit the separate `Pests:` heading, so a unique valid row is sufficient.
     * Empty means unknown and must never be treated as clear.
     */
    @JvmStatic
    fun parse(lines: List<String?>?): Optional<Set<Int>> {
        if (lines.isNullOrEmpty()) return Optional.empty()

        val rows = ArrayList<String>(lines.size)
        for (line in lines) rows.add(row(line))

        var header = -1
        for (index in rows.indices) {
            if (!rows[index].startsWith(PESTS_HEADER)) continue
            // Two headers cannot both be the widget; reject a tab list we do not understand.
            if (header >= 0) return Optional.empty()
            header = index
        }
        if (header < 0) return parseStandalonePlotRow(rows)

        var infested: Set<Int>? = null
        for (index in header + 1 until rows.size) {
            val text = rows[index]
            if (text.isEmpty()) continue
            if (isOtherWidget(text)) break

            val plots = PESTS_IN_PLOTS.matcher(text)
            if (plots.matches()) {
                // A second plot list makes the snapshot ambiguous; never choose one by row order.
                if (infested != null) return Optional.empty()
                val parsed = parsePlotIds(plots.group(1))
                if (parsed.isEmpty) return Optional.empty()
                infested = parsed.get()
                continue
            }
            // The count row, such as `5 pests`, carries no plot id but is part of the widget.
            if (isPestCount(text)) continue
            // Any other row makes this a partial read, so reject the whole widget.
            return Optional.empty()
        }
        return if (infested == null) Optional.empty() else Optional.of(infested)
    }

    /** Accepts the server's unique authoritative row even when it omits the widget heading. */
    private fun parseStandalonePlotRow(rows: List<String>): Optional<Set<Int>> {
        var infested: Set<Int>? = null
        for (text in rows) {
            val plots = PESTS_IN_PLOTS.matcher(text)
            if (!plots.matches()) continue
            if (infested != null) return Optional.empty()
            val parsed = parsePlotIds(plots.group(1))
            if (parsed.isEmpty) return Optional.empty()
            infested = parsed.get()
        }
        return if (infested == null) Optional.empty() else Optional.of(infested)
    }

    /** Whether a normalized row delimits the Pests widget; retained for the cooldown parser. */
    @JvmStatic
    fun isOtherWidget(text: String): Boolean {
        for (word in OTHER_WIDGET_WORDS) {
            if (text == word) return true
        }
        for (header in OTHER_WIDGETS) {
            if (text.startsWith(header)) return true
        }
        return false
    }

    /** Splits a `Plots:` value; explicit `none` is a decisive empty list, not a failure. */
    private fun parsePlotIds(value: String): Optional<Set<Int>> {
        val text = value.trim { it <= ' ' }
        // Empty is a half-rendered row; only the widget's explicit no-plots spellings clear it.
        if (text.isEmpty()) return Optional.empty()
        if (NO_PLOTS.matcher(text).matches()) return Optional.of<Set<Int>>(LinkedHashSet())

        val ids: MutableSet<Int> = LinkedHashSet()
        for (token in PLOT_SEPARATOR.split(text)) {
            val candidate = token.trim { it <= ' ' }
            if (!PLOT_ID.matcher(candidate).matches()) return Optional.empty()
            val id = try {
                Integer.parseInt(candidate)
            } catch (_: NumberFormatException) {
                return Optional.empty()
            }
            if (!GardenPlotGrid.isValidId(id)) return Optional.empty()
            ids.add(id)
        }
        return Optional.of<Set<Int>>(ids)
    }

    /** Whether a row is the widget's pest-count line, such as `5 pests` or `1 pest`. */
    private fun isPestCount(text: String): Boolean {
        val space = text.indexOf(' ')
        if (space <= 0) return false
        for (index in 0 until space) {
            if (!Character.isDigit(text[index])) return false
        }
        val noun = text.substring(space + 1).trim { it <= ' ' }
        return noun == "pest" || noun == "pests"
    }

    /** Removes formatting, Hypixel glyphs and padding before matching. */
    private fun row(line: String?): String =
        (if (line == null) "" else ChatText.stripForMatch(line)).lowercase(Locale.ROOT)
}
