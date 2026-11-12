package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.ArrayList
import java.util.Optional
import java.util.regex.Pattern

/** Static-call-compatible parser and immutable spawn value for Java callers. */
object PestSpawnChatParser {
    private val singleSpawn = Pattern.compile(
        "(?i)^\\w+! A Pest has appeared in (?:Plot - )?(?<plot>.+)!$",
    )
    private val multipleSpawn = Pattern.compile(
        "(?i)^\\w+! (?<amount>\\d{1,2}) Pests? have spawned in (?:Plot - )?(?<plot>.+)!$",
    )
    private val offlineSpawn = Pattern.compile(
        "(?i)^\\w+! While you were offline, Pests? spawned in Plots (?<plots>.+)!$",
    )
    private val cleanPlot = Pattern.compile("(?i)^Plot - (?<plot>.+) is now clean!$")
    private val plotSeparator = Pattern.compile("(?i)\\s*,\\s*|\\s+and\\s+")
    private val pestKill = Pattern.compile(killPattern(), Pattern.CASE_INSENSITIVE)
    private val safePlotName = Pattern.compile("(?i)^[a-z0-9][a-z0-9 _-]{0,31}$")

    @JvmStatic
    fun parseSpawn(rawMessage: String): Optional<Spawn> {
        val line = ChatText.stripForMatch(rawMessage)
        val single = singleSpawn.matcher(line)
        if (single.matches()) return spawn(1, listOf(single.group("plot")), false)

        val multiple = multipleSpawn.matcher(line)
        if (multiple.matches()) {
            val amount = try {
                Integer.parseInt(multiple.group("amount"))
            } catch (_: NumberFormatException) {
                return Optional.empty()
            }
            return spawn(amount, listOf(multiple.group("plot")), false)
        }

        val offline = offlineSpawn.matcher(line)
        if (!offline.matches()) return Optional.empty()
        return spawn(0, splitPlotNames(offline.group("plots")), true)
    }

    @JvmStatic
    fun parseCleanPlot(rawMessage: String): Optional<String> {
        val matcher = cleanPlot.matcher(ChatText.stripForMatch(rawMessage))
        return if (matcher.matches()) safePlotName(matcher.group("plot")) else Optional.empty()
    }

    @JvmStatic
    fun isPestKill(rawMessage: String): Boolean = pestKill.matcher(ChatText.stripForMatch(rawMessage)).matches()

    private fun spawn(
        amount: Int,
        plotNames: List<String>,
        offline: Boolean,
    ): Optional<Spawn> {
        val safeNames = ArrayList<String>(plotNames.size)
        for (plotName in plotNames) safePlotName(plotName).ifPresent { safeNames.add(it) }
        if (safeNames.isEmpty()) return Optional.empty()
        return Optional.of(Spawn(amount, java.util.List.copyOf(safeNames), offline))
    }

    private fun splitPlotNames(value: String): List<String> {
        val values = plotSeparator.split(value)
        val names = ArrayList<String>(values.size)
        for (candidate in values) safePlotName(candidate).ifPresent { names.add(it) }
        return java.util.List.copyOf(names)
    }

    private fun safePlotName(raw: String?): Optional<String> {
        if (raw == null) return Optional.empty()
        var name = ChatText.stripForMatch(raw).trim { it.code <= 0x20 }
        val prefix = "Plot - "
        if (name.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            name = name.substring(prefix.length).trim { it.code <= 0x20 }
        }
        if (name.equals("The Barn", ignoreCase = true)) return Optional.of("The Barn")
        if (!safePlotName.matcher(name).matches()) return Optional.empty()
        return Optional.of(name)
    }

    private fun killPattern(): String {
        val names = StringBuilder()
        for (kind in PestKind.values()) {
            if (names.isNotEmpty()) names.append('|')
            names.append(Pattern.quote(kind.displayName()))
        }
        return "^You received .+ for killing an? (?:$names)!$"
    }

    data class Spawn(
        val amount: Int,
        private var plotNames: List<String>?,
        val whileOffline: Boolean,
    ) {
        init {
            plotNames = plotNames?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
        }

        fun amount(): Int = amount

        fun plotNames(): List<String> = checkNotNull(plotNames)

        fun whileOffline(): Boolean = whileOffline

        fun knownAmount(): Int = maxOf(0, amount)

        override fun toString(): String =
            "Spawn[amount=$amount, plotNames=${plotNames()}, whileOffline=$whileOffline]"
    }
}
