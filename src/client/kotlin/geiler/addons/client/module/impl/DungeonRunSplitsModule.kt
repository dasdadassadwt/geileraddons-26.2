package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideSegments
import geiler.addons.client.dungeon.DungeonSplitPersonalBestBook
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStore
import geiler.addons.client.dungeon.helpers.DungeonSplitTracker
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Compact, color-coded dungeon milestone rows with real time and one optional secondary metric. */
class DungeonRunSplitsModule private constructor() : Module(
    "Run Splits",
    "Shows the dungeon clear, floor boss phases, and total run time.",
    Category.F7,
    false,
    showSplits,
    showCurrentSplit,
    secondTimeDisplay,
    showTickTime,
    showRealTime,
    showPbPace,
    showCountValue,
    countSource,
    localRunSummary,
), HudElement {
    @Volatile
    private var splitRows: List<DungeonSplitTracker.Split?> = emptyList()

    @Volatile
    private var supplementalRows: List<String> = emptyList()

    @Volatile
    private var displayedFloor: DungeonFloor? = null

    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, MAX_ENTRY_START_EVENTS_PER_TICK, 100L, 1024)

    init {
        group(
            SettingGroup.switched("Split Display", showSplits, showCurrentSplit, secondTimeDisplay),
            SettingGroup.switched("Completion Summary", localRunSummary),
        )
    }

    /** Structured adapter input keeps labels, colors, and time formatting in the HUD module. */
    fun publishSplitRows(floor: DungeonFloor?, rows: List<DungeonSplitTracker.Split?>?) {
        displayedFloor = floor
        splitRows = immutableCopy(rows ?: emptyList())
    }

    fun publishSplitRows(
        floor: DungeonFloor?,
        rows: List<DungeonSplitTracker.Split?>?,
        clientTick: Long,
        trackerRunning: Boolean,
        activeSplitId: String?,
    ) {
        publishSplitRows(floor, rows, clientTick, trackerRunning, activeSplitId, 0L)
    }

    fun publishSplitRows(
        floor: DungeonFloor?,
        rows: List<DungeonSplitTracker.Split?>?,
        clientTick: Long,
        trackerRunning: Boolean,
        activeSplitId: String?,
        serverTickCount: Long,
    ) {
        publishSplitRows(floor, rows)
        recordDiagnostics(clientTick, floor, trackerRunning, activeSplitId, serverTickCount)
    }

    fun recordEntryStart(clientTick: Long, floor: DungeonFloor?, source: String?, priorState: String?) {
        val safeSource = if (source == "buffered") "buffered" else "direct"
        val safePriorState = if (priorState == "running" || priorState == "complete") priorState else "idle"
        diagnostics.event(
            "ENTRY_START floor=" + (floor?.name ?: "none") +
                " source=" + safeSource +
                " priorState=" + safePriorState,
        )
    }

    /** Boss timers can be appended without flattening the colored split rows into one string style. */
    fun publishSupplementalRows(rows: List<String?>?) {
        supplementalRows = immutableCopy(
            rows.orEmpty().filterNotNull().filter { it.isNotBlank() },
        )
    }

    fun clearSnapshots() {
        displayedFloor = null
        splitRows = emptyList()
        supplementalRows = emptyList()
    }

    override fun onEnable() {
        clearSnapshots()
        diagnostics.enable("secondDisplay=${secondTimeDisplay.value()}")
    }

    override fun onDisable() {
        diagnostics.disable()
        clearSnapshots()
    }

    override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

    override fun id(): String = "dungeon_run_splits"

    override fun displayName(): String = "Run Splits"

    override fun previewMode(): String = if (buildRows(false).isEmpty()) "sample" else "live"

    override fun visible(): Boolean = isEnabled() && buildRows(false).isNotEmpty()

    override fun width(font: Font?): Int =
        measureWidth(font!!, buildRows(false).ifEmpty { buildRows(true) })

    override fun previewWidth(font: Font?): Int = measureWidth(font!!, previewRows())

    override fun height(font: Font?): Int {
        val rows = buildRows(false).ifEmpty { buildRows(true) }
        return maxOf(1, rows.size * (font!!.lineHeight + ROW_GAP))
    }

    override fun previewHeight(font: Font?): Int =
        maxOf(1, previewRows().size * (font!!.lineHeight + ROW_GAP))

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawRows(graphics, font, x, y, buildRows(false))
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawRows(graphics, font, x, y, previewRows())
    }

    private fun previewRows(): List<DisplayRow> {
        val current = buildRows(false)
        return if (current.isEmpty()) buildRows(true) else current
    }

    fun buildRows(preview: Boolean): List<DisplayRow> {
        val result = ArrayList<DisplayRow>()
        if (preview) {
            val ids = arrayOf(
                "BLOOD", "CAMP", "PORTAL", "MAXOR", "STORM", "TERMINALS", "S1", "S2", "S3", "S4",
                "GOLDOR", "NECRON", "RUN",
            )
            val labels = arrayOf(
                "Blood", "Camp", "Portal", "Maxor", "Storm", "Terminals", "S1", "S2", "S3", "S4",
                "Goldor", "Necron", "Run",
            )
            val real = arrayOf(
                "13.08s", "47.22s", "17.74s", "24.63s", "39.12s", "1m 18.03s", "18.11s", "19.20s",
                "18.42s", "22.30s", "35.42s", "1m 02.73s", "5m 46.91s",
            )
            val tickEstimate = arrayOf(
                "12.55s", "46.95s", "17.45s", "23.90s", "38.65s", "1m 16.90s", "17.85s",
                "18.95s", "18.40s", "21.75s", "34.85s", "1m 01.20s", "5m 40.35s",
            )
            val personalBest = arrayOf(
                "[12.55s]", "[46.95s]", "[17.45s]", "[23.88s]", "[38.64s]", "[1m 16.90s]", "[17.83s]",
                "[18.95s]", "[18.40s]", "[21.72s]", "[34.86s]", "[1m 01.20s]", "[5m 40.33s]",
            )
            val timeLost = arrayOf(
                "+0.50s", "+0.25s", "+0.25s", "+0.70s", "+0.45s", "+1.10s", "+0.25s", "+0.25s",
                "+0.00s", "+0.55s", "+0.55s", "+1.50s", "+6.55s",
            )
            for (index in labels.indices) {
                val secondary = when (secondTimeDisplay.value()) {
                    "CLIENT_TICK_ESTIMATE" -> tickEstimate[index]
                    "LOCAL_PB" -> personalBest[index]
                    "TIME_LOST" -> timeLost[index]
                    else -> ""
                }
                result.add(
                    DisplayRow(
                        labels[index],
                        real[index],
                        secondary,
                        labelColor(ids[index]),
                        TIME_GREEN,
                        secondaryTimeColor(),
                    ),
                )
            }
            return immutableCopy(result)
        }

        val floor = displayedFloor
        if (showSplits.value()) {
            for (split in orderedDisplaySplits(splitRows, floor)) {
                if (!split.complete && !showCurrentSplit.value() && split.id != "RUN") continue
                val label = splitLabel(split.id, floor)
                if (label != null) result.add(displayRow(label, split, floor))
            }
        }
        for (row in supplementalRows) {
            result.add(DisplayRow(row, "", "", AUXILIARY, AUXILIARY, AUXILIARY))
        }
        return immutableCopy(result)
    }

    private fun displayRow(label: String, split: DungeonSplitTracker.Split, floor: DungeonFloor?): DisplayRow {
        val realTime = formatSplitTime(label, split.elapsedNanos)
        val secondary = secondTimeValue(split, floor)
        return DisplayRow(label, realTime, secondary, labelColor(split.id), TIME_GREEN, secondaryTimeColor())
    }

    private fun secondTimeValue(split: DungeonSplitTracker.Split, floor: DungeonFloor?): String {
        if (secondTimeDisplay.value() == "NONE") return ""
        if (secondTimeDisplay.value() == "CLIENT_TICK_ESTIMATE") {
            return formatSplitTime(split.label, serverTickEstimateNanos(split.elapsedServerTicks))
        }
        if (secondTimeDisplay.value() == "TIME_LOST") return formatTimeLost(split)
        if (floor == null) return "[--]"
        val bestNanos = bestRealTimeNanos(floor, split.id)
        return if (bestNanos == null) "[--]" else "[" + formatSplitTime(split.label, bestNanos) + "]"
    }

    private fun recordDiagnostics(
        clientTick: Long,
        floor: DungeonFloor?,
        trackerRunning: Boolean,
        activeSplitId: String?,
        serverTickCount: Long,
    ) {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.statusOnChange(20L) {
            val rows = buildRows(false).take(32).joinToString(",") { row ->
                row.label + "=" + row.primaryValue +
                    if (row.secondaryValue.isEmpty()) "" else "/" + row.secondaryValue
            }.ifEmpty { "none" }
            val serverTicksByRow = splitRows.asSequence().filterNotNull().take(32)
                .joinToString(",") { split -> split.id + ":" + maxOf(0L, split.elapsedServerTicks) }
                .ifEmpty { "none" }
            "dungeon=" + (floor != null) +
                " floor=" + (floor?.name ?: "none") +
                " trackerRunning=" + trackerRunning +
                " active=" + (activeSplitId ?: "none") +
                " primary=real_time secondDisplay=" + secondTimeDisplay.value() +
                " rows=" + rows +
                " serverTickSource=world_time_anchor_ping_fallback serverTickCount=" + maxOf(0L, serverTickCount) +
                " serverTicksByRow=" + serverTicksByRow
        }
    }

    companion object {
        private const val LABEL_RED: Int = -43691
        private const val LABEL_LIGHT_RED: Int = -31355
        private const val LABEL_PINK: Int = -43521
        private const val LABEL_GRAY: Int = -5592406
        private const val LABEL_LIGHT_BLUE: Int = -11154177
        private const val LABEL_ORANGE: Int = -22016
        private const val LABEL_YELLOW: Int = -171
        private const val LABEL_PURPLE: Int = -5614081
        private const val LABEL_WHITE: Int = -1
        private const val LABEL_BONZO_SIKE: Int = -17562
        private const val LABEL_BONZO: Int = -30635
        private const val LABEL_SCARF_MINIONS: Int = -2716417
        private const val LABEL_SCARF: Int = -4495651
        private const val LABEL_GUARDIANS: Int = -11149859
        private const val LABEL_PROFESSOR: Int = -10035832
        private const val LABEL_THORN: Int = -34902
        private const val LABEL_LIVID: Int = -2263109
        private const val LABEL_TERRACOTTAS: Int = -3691920
        private const val LABEL_SADAN_GIANTS: Int = -4813569
        private const val LABEL_SADAN: Int = -2267444
        private const val TIME_GREEN: Int = -11141291
        private const val PB_GRAY: Int = -5592406
        private const val LOST_RED: Int = -43691
        private const val SERVER_TICK_ESTIMATE_NANOS = 50_000_000L
        private const val AUXILIARY: Int = -1710101
        private const val ROW_GAP = 0
        private const val COLUMN_GAP = 5
        private const val MAX_ENTRY_START_EVENTS_PER_TICK = 16

        @JvmField
        val showSplits = BooleanSetting("Show Splits", true)

        @JvmField
        val showCurrentSplit = BooleanSetting("Show Current Split", true)

        @JvmField
        val secondTimeDisplay = ChoiceSetting(
            "Second Time Display",
            "LOCAL_PB",
            mapOf(
                "NONE" to "Off",
                "CLIENT_TICK_ESTIMATE" to "Srv Ticks",
                "LOCAL_PB" to "Local PB",
                "TIME_LOST" to "Time Lost",
            ),
            "NONE",
            "CLIENT_TICK_ESTIMATE",
            "LOCAL_PB",
            "TIME_LOST",
        )

        // These saved settings only drive the independent completion summary. They are deliberately
        // omitted from the visible groups, and the split HUD never consults them.
        @JvmField
        val showTickTime = BooleanSetting("Show Tick Time", true)

        @JvmField
        val showRealTime = BooleanSetting("Show Real Time", true)

        @JvmField
        val showPbPace = BooleanSetting("Show PB Pace", false)

        @JvmField
        val showCountValue = BooleanSetting("Show Count Value", true)

        @JvmField
        val countSource = ChoiceSetting(
            "Count Source",
            "CLIENT_TICKS",
            mapOf(
                "CLIENT_TICKS" to "Client ticks (local)",
                "SERVER_PING_PACKETS" to "Odin ping-event proxy (not server ticks)",
            ),
            "CLIENT_TICKS",
            "SERVER_PING_PACKETS",
        )

        @JvmField
        val localRunSummary = BooleanSetting("Local Run Summary", true)

        @JvmField
        val INSTANCE = DungeonRunSplitsModule()

        @JvmStatic
        fun formatTimeLost(split: DungeonSplitTracker.Split): String =
            "+" + formatSplitTime("Time Lost", timeLostNanos(split.elapsedNanos, split.elapsedServerTicks))

        @JvmStatic
        fun timeLostNanos(elapsedNanos: Long, serverTicks: Long): Long {
            val lostNanos = maxOf(0L, maxOf(0L, elapsedNanos) - serverTickEstimateNanos(serverTicks))
            return lostNanos - lostNanos % SERVER_TICK_ESTIMATE_NANOS
        }

        @JvmStatic
        fun serverTickEstimateNanos(ticks: Long): Long {
            val safeTicks = maxOf(0L, ticks)
            return if (safeTicks > Long.MAX_VALUE / SERVER_TICK_ESTIMATE_NANOS) {
                Long.MAX_VALUE
            } else {
                safeTicks * SERVER_TICK_ESTIMATE_NANOS
            }
        }

        private fun bestRealTimeNanos(floor: DungeonFloor?, splitId: String?): Long? {
            if (floor != null && floor.number() == 3 && splitId == "PROFESSOR") {
                val professor = DungeonSplitPersonalBestStore.find(floor, "PROFESSOR")
                var transformed = DungeonSplitPersonalBestStore.find(floor, "PROFESSOR_TRANSFORMED")
                if (transformed == null) {
                    transformed = DungeonSplitPersonalBestStore.find(floor, "TRANSFORMED_PROFESSOR")
                }
                if (professor == null || transformed == null) return null
                return saturatingAdd(professor.bestRealTimeNanos, transformed.bestRealTimeNanos)
            }
            val best = DungeonSplitPersonalBestStore.find(floor, pbSplitId(splitId))
            return best?.bestRealTimeNanos
        }

        private fun saturatingAdd(first: Long, second: Long): Long {
            if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE
            return first + second
        }

        private fun pbSplitId(id: String?): String? = if (id == "RUN") "COMPLETE" else id

        private fun orderedDisplaySplits(
            source: List<DungeonSplitTracker.Split?>,
            floor: DungeonFloor?,
        ): List<DungeonSplitTracker.Split> {
            if (source.isEmpty() || floor == null) return emptyList()
            val byId = LinkedHashMap<String?, DungeonSplitTracker.Split>()
            for (split in source.filterNotNull()) byId[split.id] = split
            val ordered = ArrayList<DungeonSplitTracker.Split>(20)
            addIfPresent(ordered, byId, "BLOOD")
            addIfPresent(ordered, byId, "CAMP")
            addIfPresent(ordered, byId, "PORTAL")
            for (segment in DungeonGuideSegments.builtIn(floor)) {
                val id = segment.id
                if (id in setOf("ENTRY", "CLEAR", "BLOOD_OPEN", "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE")) {
                    continue
                }
                if (floor.number() == 3 && id == "PROFESSOR") {
                    val professor = byId["PROFESSOR"]
                    val transformed = byId["PROFESSOR_TRANSFORMED"] ?: byId["TRANSFORMED_PROFESSOR"]
                    if (professor != null || transformed != null) {
                        ordered.add(combineProfessorSplits(professor, transformed))
                    }
                    continue
                }
                if (floor.number() == 3 && id in setOf("PROFESSOR_TRANSFORMED", "TRANSFORMED_PROFESSOR")) {
                    continue
                }
                addIfPresent(ordered, byId, id)
                if (id == "TERMINALS") {
                    for (section in 1..4) addIfPresent(ordered, byId, "S" + section)
                }
            }
            addIfPresent(ordered, byId, "RUN")
            addIfPresent(ordered, byId, "COMPLETE")
            return immutableCopy(ordered)
        }

        private fun combineProfessorSplits(
            professor: DungeonSplitTracker.Split?,
            transformed: DungeonSplitTracker.Split?,
        ): DungeonSplitTracker.Split {
            val elapsedNanos = saturatingAdd(professor?.elapsedNanos ?: 0L, transformed?.elapsedNanos ?: 0L)
            val elapsedTicks = saturatingAdd(professor?.elapsedTicks ?: 0L, transformed?.elapsedTicks ?: 0L)
            val elapsedPingPackets =
                saturatingAdd(professor?.elapsedPingPackets ?: 0L, transformed?.elapsedPingPackets ?: 0L)
            val elapsedServerTicks =
                saturatingAdd(professor?.elapsedServerTicks ?: 0L, transformed?.elapsedServerTicks ?: 0L)
            val complete = (professor == null || professor.complete) &&
                (transformed == null || transformed.complete)
            return DungeonSplitTracker.Split(
                "PROFESSOR",
                "Professor",
                elapsedNanos,
                elapsedTicks,
                elapsedPingPackets,
                elapsedServerTicks,
                complete,
            )
        }

        private fun addIfPresent(
            target: MutableList<DungeonSplitTracker.Split>,
            byId: Map<String?, DungeonSplitTracker.Split>,
            id: String,
        ) {
            val split = byId[id]
            if (split != null) target.add(split)
        }

        private fun splitLabel(id: String?, floor: DungeonFloor?): String? = when (val nonNullId = id!!) {
            "BLOOD" -> "Blood"
            "CAMP" -> "Camp"
            "PORTAL" -> "Portal"
            "RUN", "COMPLETE" -> "Run"
            "SCARF_MINIONS" -> "Minions"
            "SADAN_GIANTS" -> "Giants"
            "PROFESSOR" -> "Professor"
            "S1", "S2", "S3", "S4" -> nonNullId
            else -> if (DungeonGuideSegments.contains(floor, nonNullId)) {
                DungeonGuideSegments.label(floor, nonNullId)
            } else null
        }

        private fun labelColor(id: String?): Int = when (val nonNullId = id!!) {
            "BLOOD", "NECRON" -> LABEL_RED
            "CAMP" -> LABEL_LIGHT_RED
            "PORTAL" -> LABEL_PINK
            "MAXOR" -> LABEL_GRAY
            "STORM" -> LABEL_LIGHT_BLUE
            "TERMINALS", "GOLDOR" -> LABEL_ORANGE
            "S1", "S2", "S3", "S4" -> LABEL_YELLOW
            "WITHER_KING" -> LABEL_PURPLE
            "RUN", "COMPLETE" -> LABEL_WHITE
            "BONZO_SIKE" -> LABEL_BONZO_SIKE
            "BONZO" -> LABEL_BONZO
            "SCARF_MINIONS" -> LABEL_SCARF_MINIONS
            "SCARF" -> LABEL_SCARF
            "GUARDIANS" -> LABEL_GUARDIANS
            "PROFESSOR", "PROFESSOR_TRANSFORMED", "TRANSFORMED_PROFESSOR" -> LABEL_PROFESSOR
            "THORN" -> LABEL_THORN
            "LIVID" -> LABEL_LIVID
            "TERRACOTTAS" -> LABEL_TERRACOTTAS
            "SADAN_GIANTS" -> LABEL_SADAN_GIANTS
            "SADAN" -> LABEL_SADAN
            else -> LABEL_ORANGE
        }

        private fun secondaryTimeColor(): Int = when (secondTimeDisplay.value()) {
            "LOCAL_PB" -> PB_GRAY
            "TIME_LOST" -> LOST_RED
            else -> AUXILIARY
        }

        private fun drawRows(
            graphics: GuiGraphicsExtractor,
            font: Font,
            x: Int,
            y: Int,
            rows: List<DisplayRow>,
        ) {
            val labelWidth = rows.filter {
                it.primaryValue.isNotEmpty() || it.secondaryValue.isNotEmpty()
            }.maxOfOrNull { font.width(it.label) } ?: 0
            val primaryWidth = rows.maxOfOrNull { font.width(it.primaryValue) } ?: 0
            for ((index, row) in rows.withIndex()) {
                val lineY = y + index * (font.lineHeight + ROW_GAP)
                if (row.primaryValue.isEmpty() && row.secondaryValue.isEmpty()) {
                    graphics.text(font, Component.literal(row.label), x, lineY, row.labelColor)
                    continue
                }
                graphics.text(font, Component.literal(row.label), x, lineY, row.labelColor)
                val primaryX = x + labelWidth + COLUMN_GAP
                graphics.text(font, Component.literal(row.primaryValue), primaryX, lineY, row.primaryColor)
                if (row.secondaryValue.isNotEmpty()) {
                    val secondaryX = primaryX + primaryWidth + COLUMN_GAP
                    graphics.text(font, Component.literal(row.secondaryValue), secondaryX, lineY, row.secondaryColor)
                }
            }
        }

        private fun measureWidth(font: Font, rows: List<DisplayRow>): Int {
            if (rows.isEmpty()) return 1
            val labelWidth = rows.filter {
                it.primaryValue.isNotEmpty() || it.secondaryValue.isNotEmpty()
            }.maxOfOrNull { font.width(it.label) } ?: 0
            val primaryWidth = rows.maxOfOrNull { font.width(it.primaryValue) } ?: 0
            val secondaryWidth = rows.maxOfOrNull { font.width(it.secondaryValue) } ?: 0
            val columnsWidth = labelWidth + COLUMN_GAP + primaryWidth +
                (if (secondaryWidth == 0) 0 else COLUMN_GAP + secondaryWidth)
            val standaloneWidth = rows.filter {
                it.primaryValue.isEmpty() && it.secondaryValue.isEmpty()
            }.maxOfOrNull { font.width(it.label) } ?: 0
            return maxOf(1, columnsWidth, standaloneWidth)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun formatSplitTime(label: String?, nanos: Long): String {
            val centiseconds = maxOf(0L, nanos) / 10_000_000L
            val seconds = centiseconds / 100L
            val fraction = centiseconds % 100L
            return if (seconds < 60L) {
                String.format(Locale.ROOT, "%d.%02ds", seconds, fraction)
            } else {
                String.format(Locale.ROOT, "%dm %02d.%02ds", seconds / 60L, seconds % 60L, fraction)
            }
        }

        private fun <T> immutableCopy(items: Collection<T>): List<T> {
            items.forEach { Objects.requireNonNull(it) }
            return Collections.unmodifiableList(ArrayList(items))
        }
    }

    @JvmRecord
    data class DisplayRow(
        val label: String,
        val primaryValue: String,
        val secondaryValue: String,
        val labelColor: Int,
        val primaryColor: Int,
        val secondaryColor: Int,
    )
}
