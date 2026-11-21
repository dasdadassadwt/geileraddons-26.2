package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.Optional
import java.util.regex.Pattern

/**
 * Reads the Sprayonator row of the Pests tab widget, `Spray: Compost (12m)` or `Spray: None`, which Hypixel fills for the
 * plot you stand on. The row form is SkyHanni's (GardenPlotApi `tablist.spraytime`); the code is local.
 */
object PestSprayText {
    private val SPRAY: Pattern = Pattern.compile("(?i)^Spray:\\s*(?<name>[A-Za-z][A-Za-z ]*?)\\s*(?:\\((?<time>[^)]*)\\))?$")
    private val TIME: Pattern = Pattern.compile("^(?:(?<m>\\d{1,3})m)?\\s*(?:(?<s>\\d{1,2})s)?$")

    /** [name] is null when the plot has no spray; [remainingSeconds] is 0 then, or when the row has no readable time. */
    data class Observation(val name: String?, val remainingSeconds: Int)

    @JvmStatic
    fun parse(orderedLines: List<String?>?): Optional<Observation> {
        if (orderedLines.isNullOrEmpty()) return Optional.empty()
        var header = -1
        for (index in orderedLines.indices) {
            if (!strip(orderedLines[index]).lowercase(Locale.ROOT).startsWith("pests:")) continue
            if (header >= 0) return Optional.empty()
            header = index
        }
        if (header < 0) return Optional.empty()
        for (index in header + 1 until orderedLines.size) {
            val row = strip(orderedLines[index])
            if (TabWidgetText.isOtherWidget(row.lowercase(Locale.ROOT))) break
            val matcher = SPRAY.matcher(row)
            if (!matcher.matches()) continue
            val name = matcher.group("name").trim()
            if (name.equals("None", ignoreCase = true)) return Optional.of(Observation(null, 0))
            return Optional.of(Observation(name, seconds(matcher.group("time"))))
        }
        return Optional.empty()
    }

    /** `Compost 12m`, `Compost 1m 03s`, `Honey Jar 53s`, or just the name when there is no time. */
    @JvmStatic
    fun text(observation: Observation?): String {
        if (observation == null) return "unknown"
        val name = observation.name ?: return "none"
        val seconds = observation.remainingSeconds
        if (seconds <= 0) return name
        return if (seconds < 60) "$name ${seconds}s" else "$name ${seconds / 60}m ${"%02d".format(Locale.ROOT, seconds % 60)}s"
    }

    private fun seconds(time: String?): Int {
        val matcher = TIME.matcher(time?.trim().orEmpty())
        if (!matcher.matches()) return 0
        val minutes = matcher.group("m")?.toIntOrNull() ?: 0
        val seconds = matcher.group("s")?.toIntOrNull() ?: 0
        return minutes * 60 + seconds
    }

    private fun strip(value: String?): String = ChatText.stripForMatch(value ?: "")
}
