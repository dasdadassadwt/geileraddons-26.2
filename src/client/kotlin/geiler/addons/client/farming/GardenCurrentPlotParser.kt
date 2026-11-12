package geiler.addons.client.farming

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.tree.ChatText
import java.util.Optional
import java.util.regex.Pattern

/** Reads and normalizes plot names from Garden scoreboard text. */
object GardenCurrentPlotParser {
    private val currentPlot = Pattern.compile(
        "(?i)^(?:current\\s+)?plot\\s*(?:-|:)\\s*(?<name>[a-z0-9][a-z0-9 _-]{0,31})$",
    )
    private val safePlotName = Pattern.compile("(?i)^[a-z0-9][a-z0-9 _-]{0,31}$")
    private val lineBreak = Pattern.compile("\\R")

    @JvmStatic
    fun parse(scoreboardText: String?): Optional<String> {
        if (scoreboardText == null || isJavaBlank(scoreboardText)) return Optional.empty()
        for (rawLine in lineBreak.split(scoreboardText)) {
            val matcher = currentPlot.matcher(ChatStringSemantics.trim(ChatText.stripForMatch(rawLine)))
            if (!matcher.matches()) continue
            val name = ChatStringSemantics.trim(matcher.group("name"))
            if (name.equals("the barn", ignoreCase = true)) return Optional.of("The Barn")
            return Optional.of(name)
        }
        return Optional.empty()
    }

    @JvmStatic
    fun normalize(plotName: String?): Optional<String> {
        if (plotName == null) return Optional.empty()
        var name = ChatStringSemantics.trim(ChatText.stripForMatch(plotName))
        if (name.regionMatches(0, "Plot - ", 0, 7, ignoreCase = true)) {
            name = ChatStringSemantics.trim(name.substring(7))
        }
        if (name.equals("The Barn", ignoreCase = true)) return Optional.of("The Barn")
        if (!safePlotName.matcher(name).matches()) return Optional.empty()
        return Optional.of(name)
    }

    /** Matches String.isBlank's Character.isWhitespace rule rather than Kotlin's broader isBlank. */
    private fun isJavaBlank(value: String): Boolean = ChatStringSemantics.isBlank(value)
}
