package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Optional
import kotlin.jvm.JvmRecord

/** Garden-session plot names learned from authoritative pest-spawn and plot-clean chat lines. */
class InfestedPlotTracker {
    private val plotsByKey = LinkedHashMap<String, InfestedPlot>()
    private var latestPlot: InfestedPlot? = null

    fun observeSpawn(spawn: PestSpawnChatParser.Spawn?, observedAtNanos: Long): Boolean {
        if (spawn == null || spawn.plotNames().isEmpty()) return false

        var changed = false
        for (plotName in spawn.plotNames()) {
            val plotKey = plotKey(plotName)
            if (plotKey.isEmpty()) continue

            plotsByKey[plotKey] = InfestedPlot(plotName, observedAtNanos, spawn.knownAmount())
            changed = true
        }

        while (plotsByKey.size > MAX_KNOWN_PLOTS) {
            val oldestKey = plotsByKey.entries.minByOrNull { it.value.lastSeenNanos }?.key ?: break
            plotsByKey.remove(oldestKey)
        }
        refreshLatest()
        return changed
    }

    fun observeCleanPlot(plotName: String?): Boolean {
        if (plotName == null) return false
        val removed = plotsByKey.remove(plotKey(plotName)) != null
        if (removed) refreshLatest()
        return removed
    }

    /** Chooses the newest infested plot, preferring a plot other than the one under the player. */
    fun newestWarpTarget(currentPlotName: String?): Optional<InfestedPlot> {
        val newestFirst = snapshot()
        if (newestFirst.isEmpty()) return Optional.empty()
        if (contains(currentPlotName)) {
            val currentKey = plotKey(currentPlotName)
            for (plot in newestFirst) {
                if (plotKey(plot.name) != currentKey) return Optional.of(plot)
            }
        }
        // When this is the only known infested plot, allow a repeat warp to it.
        return Optional.of(newestFirst.first())
    }

    fun contains(plotName: String?): Boolean =
        plotName != null && plotsByKey.containsKey(plotKey(plotName))

    fun latest(): Optional<InfestedPlot> = latestPlot?.let { Optional.of(it) } ?: Optional.empty()

    fun snapshot(): List<InfestedPlot> {
        val result = ArrayList(plotsByKey.values)
        result.sortWith(compareByDescending { it.lastSeenNanos })
        return Collections.unmodifiableList(result)
    }

    fun clear() {
        plotsByKey.clear()
        latestPlot = null
    }

    /** Clears session-learned plot names when the server connection ends. */
    fun onDisconnect() = clear()

    private fun refreshLatest() {
        var newest: InfestedPlot? = null
        for (plot in plotsByKey.values) {
            if (newest == null || plot.lastSeenNanos > newest.lastSeenNanos) newest = plot
        }
        latestPlot = newest
    }

    @JvmRecord
    data class InfestedPlot(val name: String, val lastSeenNanos: Long, val pestCount: Int) {
        override fun toString(): String =
            "InfestedPlot[name=$name, lastSeenNanos=$lastSeenNanos, pestCount=$pestCount]"
    }

    private companion object {
        const val MAX_KNOWN_PLOTS = GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE

        fun plotKey(name: String?): String {
            if (name == null) return ""
            var normalized = ChatText.stripForMatch(name).trim { it <= ' ' }
            if (normalized.regionMatches(0, "Plot - ", 0, 7, ignoreCase = true)) {
                normalized = normalized.substring(7).trim { it <= ' ' }
            } else if (normalized.regionMatches(0, "Plot ", 0, 5, ignoreCase = true)) {
                normalized = normalized.substring(5).trim { it <= ' ' }
            }
            return normalized.lowercase(Locale.ROOT)
        }
    }
}
