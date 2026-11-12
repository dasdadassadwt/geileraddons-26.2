package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Optional
import java.util.OptionalInt
import java.util.regex.Pattern

/** Parses exact current-plot pest counts and non-attributable Garden totals from sidebar rows. */
object GardenPlotScoreboardParser {
    private val currentPlot = Pattern.compile(
        "(?i)^plot\\s*-\\s*(?<id>\\d{1,2})(?:\\s+x(?<count>\\d{1,3}))?$",
    )
    private val gardenTotal = Pattern.compile("(?i)^the garden(?:\\s+x(?<count>\\d{1,4}))?$")

    /**
     * Parses a single current-plot row. The server omits the pest suffix for a clean plot; that exact
     * row means zero. Names and out-of-grid IDs are ignored instead of being mapped by player position.
     */
    @JvmStatic
    fun parseCurrentPlotCount(rawLine: String?): Optional<PlotCount> {
        val matcher = currentPlot.matcher(ChatText.stripForMatch(rawLine ?: ""))
        if (!matcher.matches()) return Optional.empty()
        return try {
            val plotId = matcher.group("id").toInt()
            val count = matcher.group("count")?.toInt() ?: 0
            if (!GardenPlotGrid.isValidId(plotId)) Optional.empty() else Optional.of(PlotCount(plotId, count))
        } catch (_: NumberFormatException) {
            Optional.empty()
        }
    }

    /** A Garden-wide aggregate may be read for diagnostics, but it cannot identify any plot. */
    @JvmStatic
    fun parseGardenTotal(rawLine: String?): OptionalInt {
        val matcher = gardenTotal.matcher(ChatText.stripForMatch(rawLine ?: ""))
        if (!matcher.matches()) return OptionalInt.empty()
        return try {
            OptionalInt.of(matcher.group("count")?.toInt() ?: 0)
        } catch (_: NumberFormatException) {
            OptionalInt.empty()
        }
    }

    @JvmRecord
    data class PlotCount(val plotId: Int, val pestCount: Int) {
        override fun toString(): String = "PlotCount[plotId=$plotId, pestCount=$pestCount]"
    }
}
