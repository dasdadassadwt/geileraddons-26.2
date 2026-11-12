package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.regex.Pattern

/**
 * Client-session Garden pest knowledge. Callers feed only observations already visible to the client;
 * no request or persistence layer exists here.
 */
class GardenPlotState {
    private val evidenceByPlot = HashMap<Int, Evidence>()

    enum class Status {
        INFESTED,
        CLEAR,
        UNKNOWN,
    }

    enum class Source {
        NONE,
        PESTS_WIDGET,
        PLOT_MENU,
        CURRENT_PLOT_SCOREBOARD,
        SPAWN_CHAT,
        CLEAN_CHAT,
        NO_PESTS_CHAT,
    }

    /**
     * A whole-widget snapshot is authoritative for the current grid. The listed plots are infested;
     * omitted plots are clear only because this method requires a complete, valid observation.
     */
    fun observePestsWidgetSnapshot(infestedPlotIds: Collection<Int?>?, observedAtMillis: Long): Boolean =
        observePestsWidgetSnapshot(infestedPlotIds, true, observedAtMillis)

    /**
     * Applies a widget read only when its ordered Pests block is complete. Partial or malformed reads
     * are rejected before changing any plot; a complete snapshot may clear omitted plots.
     */
    fun observePestsWidgetSnapshot(
        infestedPlotIds: Collection<Int?>?,
        complete: Boolean,
        observedAtMillis: Long,
    ): Boolean {
        if (!complete || infestedPlotIds == null) return false
        val infested = HashSet<Int>()
        for (id in infestedPlotIds) {
            if (id == null || !GardenPlotGrid.isValidId(id)) return false
            infested.add(id)
        }
        for (plot in GardenPlotGrid.plots()) {
            if (infested.contains(plot.id)) {
                put(plot.id, Status.INFESTED, null, observedAtMillis, Source.PESTS_WIDGET)
            } else {
                put(plot.id, Status.CLEAR, 0, observedAtMillis, Source.PESTS_WIDGET)
            }
        }
        return true
    }

    /** Applies exact pest counts for the plots actually represented in a visible Configure Plots menu. */
    fun observePlotMenuCounts(pestCounts: Map<Int?, Int?>?, observedAtMillis: Long): Boolean =
        observeCounts(pestCounts, observedAtMillis, Source.PLOT_MENU)

    /**
     * Re-stamps existing widget knowledge when the widget is still visible and still says the same
     * thing. Independent evidence keeps its own timestamp, and an older refresh cannot move widget
     * evidence backwards in time.
     */
    fun refreshWidgetObservation(observedAtMillis: Long) {
        for ((plotId, evidence) in HashMap(evidenceByPlot)) {
            if (evidence.source != Source.PESTS_WIDGET || evidence.observedAtMillis >= observedAtMillis) continue
            evidenceByPlot[plotId] = Evidence(
                evidence.status,
                evidence.pestCount,
                observedAtMillis,
                evidence.source,
            )
        }
    }

    fun observeCurrentPlotScoreboardCount(plotId: Int, pestCount: Int, observedAtMillis: Long): Boolean {
        if (!validCount(plotId, pestCount)) return false
        return put(
            plotId,
            if (pestCount == 0) Status.CLEAR else Status.INFESTED,
            pestCount,
            observedAtMillis,
            Source.CURRENT_PLOT_SCOREBOARD,
        )
    }

    /**
     * Restamps an unchanged current-plot scoreboard count. Polling an unchanged row cannot replace
     * a newer spawn or clean-chat observation.
     */
    fun refreshCurrentPlotScoreboardCount(plotId: Int, pestCount: Int, observedAtMillis: Long): Boolean {
        if (!validCount(plotId, pestCount)) return false
        val previous = evidenceByPlot[plotId] ?: return false
        if (previous.source != Source.CURRENT_PLOT_SCOREBOARD ||
            previous.pestCount != pestCount || observedAtMillis < previous.observedAtMillis
        ) {
            return false
        }
        evidenceByPlot[plotId] = Evidence(
            previous.status,
            previous.pestCount,
            observedAtMillis,
            previous.source,
        )
        return true
    }

    /** Records a spawn for one exact plot ID. Zero means the spawn count was unavailable. */
    fun observeSpawn(plotId: Int, spawnedAmount: Int, observedAtMillis: Long): Boolean =
        observeSpawn(plotId, spawnedAmount, observedAtMillis, DEFAULT_EVIDENCE_MAX_AGE_MILLIS)

    /**
     * Applies a spawn increment. Its resulting count stays unknown unless the previous exact count is
     * still fresh at the spawn timestamp.
     */
    fun observeSpawn(plotId: Int, spawnedAmount: Int, observedAtMillis: Long, maxAgeMillis: Long): Boolean {
        if (!GardenPlotGrid.isValidId(plotId) || spawnedAmount < 0) return false
        var resultingCount: Int? = null
        val previous = evidenceByPlot[plotId]
        if (spawnedAmount > 0 && previous?.pestCount != null) {
            val age = observedAtMillis - previous.observedAtMillis
            val maxAge = maxOf(0L, maxAgeMillis)
            if (age >= 0L && age <= maxAge) {
                val sum = previous.pestCount.toLong() + spawnedAmount
                if (sum <= Int.MAX_VALUE) resultingCount = sum.toInt()
            }
        }
        return put(plotId, Status.INFESTED, resultingCount, observedAtMillis, Source.SPAWN_CHAT)
    }

    /** Records a clean confirmation for only the exact plot ID named by the evidence. */
    fun observePlotClean(plotId: Int, observedAtMillis: Long): Boolean {
        if (!GardenPlotGrid.isValidId(plotId)) return false
        return put(plotId, Status.CLEAR, 0, observedAtMillis, Source.CLEAN_CHAT)
    }

    /** Garden-wide totals carry no plot identity and therefore never change individual plot evidence. */
    fun observeGardenPestTotal(totalPests: Int, observedAtMillis: Long): Boolean = totalPests >= 0

    /** Parses explicit plot-clean and whole-Garden no-pests confirmations from chat. */
    fun observeChatMessage(rawMessage: String?, observedAtMillis: Long): Boolean {
        if (rawMessage == null || rawMessage.isBlank()) return false
        val message = ChatText.stripForMatch(rawMessage)
        val clean = CLEAN_PLOT.matcher(message)
        if (clean.matches()) {
            return try {
                observePlotClean(clean.group(1).toInt(), observedAtMillis)
            } catch (_: NumberFormatException) {
                false
            }
        }
        if (NO_PESTS.matcher(message).matches()) {
            for (plot in GardenPlotGrid.plots()) {
                put(plot.id, Status.CLEAR, 0, observedAtMillis, Source.NO_PESTS_CHAT)
            }
            return true
        }
        return false
    }

    /**
     * Compatibility overload for callers that share a chat parsing signature. Spawn messages are
     * handled separately by observeSpawn; this method only consumes clean confirmations.
     */
    fun observeChatMessage(rawMessage: String?, observedAtMillis: Long, maxAgeMillis: Long): Boolean =
        observeChatMessage(rawMessage, observedAtMillis)

    /** Returns all 25 plots in stable grid order; expired data becomes UNKNOWN, never CLEAR. */
    fun snapshot(nowMillis: Long, maxAgeMillis: Long): List<PlotStatus> {
        val maxAge = maxOf(0L, maxAgeMillis)
        val snapshot = ArrayList<PlotStatus>(GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE)
        for (plot in GardenPlotGrid.plots()) {
            val evidence = evidenceByPlot[plot.id]
            if (evidence == null) {
                snapshot.add(PlotStatus(plot.id, Status.UNKNOWN, null, 0L, false, Source.NONE))
                continue
            }
            val age = nowMillis - evidence.observedAtMillis
            if (age < 0L || age > maxAge) {
                snapshot.add(
                    PlotStatus(plot.id, Status.UNKNOWN, null, evidence.observedAtMillis, true, evidence.source),
                )
            } else {
                snapshot.add(
                    PlotStatus(
                        plot.id,
                        evidence.status,
                        evidence.pestCount,
                        evidence.observedAtMillis,
                        false,
                        evidence.source,
                    ),
                )
            }
        }
        return java.util.List.copyOf(snapshot)
    }

    fun reset() {
        evidenceByPlot.clear()
    }

    private fun observeCounts(counts: Map<Int?, Int?>?, observedAtMillis: Long, source: Source): Boolean {
        if (counts == null || counts.isEmpty()) return false
        for ((plotId, count) in counts) {
            if (plotId == null || count == null || !validCount(plotId, count)) return false
        }
        for ((plotId, count) in counts) {
            val validPlotId = plotId!!
            val validPestCount = count!!
            put(
                validPlotId,
                if (validPestCount == 0) Status.CLEAR else Status.INFESTED,
                validPestCount,
                observedAtMillis,
                source,
            )
        }
        return true
    }

    private fun validCount(plotId: Int, count: Int): Boolean = GardenPlotGrid.isValidId(plotId) && count >= 0

    private fun put(
        plotId: Int,
        status: Status,
        pestCount: Int?,
        observedAtMillis: Long,
        source: Source,
    ): Boolean {
        val previous = evidenceByPlot[plotId]
        if (previous != null && observedAtMillis < previous.observedAtMillis) return false
        evidenceByPlot[plotId] = Evidence(status, pestCount, observedAtMillis, source)
        return true
    }

    private data class Evidence(
        val status: Status,
        val pestCount: Int?,
        val observedAtMillis: Long,
        val source: Source,
    )

    @JvmRecord
    data class PlotStatus(
        val plotId: Int,
        val status: Status?,
        val pestCount: Int?,
        val observedAtMillis: Long,
        val stale: Boolean,
        val source: Source?,
    ) {
        override fun toString(): String =
            "PlotStatus[plotId=$plotId, status=$status, pestCount=$pestCount, " +
                "observedAtMillis=$observedAtMillis, stale=$stale, source=$source]"
    }

    private companion object {
        const val DEFAULT_EVIDENCE_MAX_AGE_MILLIS = 120_000L
        val CLEAN_PLOT = Pattern.compile("(?i)^Plot\\s*-\\s*(\\d+)\\s+is now clean!$")
        val NO_PESTS = Pattern.compile(
            "(?i)^There are not any Pests on your Garden right now! Keep farming!$",
        )
    }
}
