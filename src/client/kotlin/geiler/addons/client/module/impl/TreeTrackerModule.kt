package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.tree.TreeStats
import geiler.addons.client.tree.TreeTracker
import geiler.addons.client.tree.TreeType
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Counts tree gifts from chat and shows the running rate for whichever tree gifted last. */
class TreeTrackerModule private constructor(settings: Settings) : Module(
    "Tree Tracker",
    "Counts Helix, Fig and Mangrove gifts and shows gifts per hour.",
    Category.FORAGING,
    settings.pauseTimeout, settings.totalMode, settings.showBackground, settings.resetSession,
), HudElement {
    private val pauseTimeout = settings.pauseTimeout
    private val totalMode = settings.totalMode
    private val showBackground = settings.showBackground

    private val tracker = TreeTracker()

    private var cachedLines: Array<String>? = null
    private var cachedSecond = -1L
    private var cachedTotals = false

    private var diagnosticTick = 0L
    private var lastStatusTick = 0L
    private var eventBudgetTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var acceptedGifts = 0
    private var duplicateGiftLines = 0
    private var unmatchedChatLines = 0
    private var liveRenderCalls = 0
    private var lastRenderX = 0
    private var lastRenderY = 0
    private var lastRenderWidth = 0
    private var lastRenderHeight = 0
    private var lastRenderLines = "none"

    init {
        group(
            SettingGroup("Counting", settings.pauseTimeout, settings.totalMode, settings.resetSession),
            SettingGroup("Display", settings.showBackground),
        )
    }

    /** Just a carrier so the constructor can build the settings and keep references to them. */
    private class Settings {
        val pauseTimeout = TextSetting("Pause Timeout (s)", DEFAULT_PAUSE_SECONDS.toString(), 6)
        val totalMode = BooleanSetting("Show Totals", false)
        val showBackground = BooleanSetting("Background", true)
        val resetSession = ModuleAction("Reset Session", Runnable { TreeTrackerModule.resetSession() })
    }

    /** Kept as a method for the Java config persistence and transfer callers. */
    fun tracker(): TreeTracker = tracker

    // ---- counting ---------------------------------------------------------------------

    fun onChatMessage(message: String) {
        if (!isEnabled()) return

        val now = System.currentTimeMillis()
        val type = TreeType.fromMessage(message)
        if (type == null) {
            unmatchedChatLines = saturatedIncrement(unmatchedChatLines)
            return
        }

        if (tracker.onChatMessage(message, now, timeoutMillis()) == null) {
            duplicateGiftLines = saturatedIncrement(duplicateGiftLines)
            val stats = tracker.stats(type)
            diagnosticEvent("GIFT_REPEAT type=${type.name} sessionCount=${stats.sessionCount()} " +
                "totalCount=${stats.totalCount()}")
            return
        }

        ModConfig.markDirty()
        acceptedGifts = saturatedIncrement(acceptedGifts)
        val stats = tracker.stats(type)
        diagnosticEvent("GIFT_COUNTED type=${type.name} sessionCount=${stats.sessionCount()} " +
            "totalCount=${stats.totalCount()}")
    }

    private fun resetSession() {
        tracker.resetSession(System.currentTimeMillis(), timeoutMillis())
        ModConfig.markDirty()
        if (isEnabled()) diagnosticEvent("SESSION_RESET")
    }

    /** Ends whatever session was restored from disk, crediting it to the totals. */
    fun rollOverLoadedSession() {
        tracker.rollOverLoadedSession()
    }

    private fun timeoutMillis(): Long {
        var seconds = pauseTimeout.intValue(DEFAULT_PAUSE_SECONDS)
        // A zero or negative timeout would freeze the clock permanently and read as a broken
        // module rather than as a setting mistake, so treat anything unusable as the default.
        if (seconds <= 0) seconds = DEFAULT_PAUSE_SECONDS
        return minOf(seconds, MAX_PAUSE_SECONDS) * 1000L
    }

    // ---- diagnostics ------------------------------------------------------------------

    override fun onEnable() {
        lastStatusTick = diagnosticTick
        acceptedGifts = 0
        duplicateGiftLines = 0
        unmatchedChatLines = 0
        liveRenderCalls = 0
        suppressedEvents = 0
        eventBudgetTick = Long.MIN_VALUE
        eventsThisTick = 0
        lastRenderX = 0
        lastRenderY = 0
        lastRenderWidth = 0
        lastRenderHeight = 0
        lastRenderLines = "none"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FORAGING, name(), true)
        recordDiagnostic("EVENT FEATURE_ENABLED ${diagnosticState()} suppressedEvents=$suppressedEvents")
    }

    override fun onDisable() {
        recordDiagnostic("EVENT FEATURE_DISABLED ${diagnosticState()} suppressedEvents=$suppressedEvents")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FORAGING, name(), false)
    }

    private fun clientTick() {
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        if (!isEnabled()) return
        if (diagnosticTick - lastStatusTick < STATUS_INTERVAL_TICKS) return

        recordDiagnostic("STATUS intervalTicks=$STATUS_INTERVAL_TICKS ${diagnosticState()} " +
            "suppressedEvents=$suppressedEvents")
        lastStatusTick = diagnosticTick
        acceptedGifts = 0
        duplicateGiftLines = 0
        unmatchedChatLines = 0
        liveRenderCalls = 0
        suppressedEvents = 0
    }

    private fun diagnosticEvent(event: String) {
        if (eventBudgetTick != diagnosticTick) {
            eventBudgetTick = diagnosticTick
            eventsThisTick = 0
        }
        if (eventsThisTick >= MAX_DIAGNOSTIC_EVENTS_PER_TICK) {
            suppressedEvents = saturatedIncrement(suppressedEvents)
            return
        }
        eventsThisTick++
        recordDiagnostic("EVENT $event ${diagnosticState()} suppressedEvents=$suppressedEvents")
    }

    private fun diagnosticState(): String {
        val now = System.currentTimeMillis()
        val timeout = timeoutMillis()
        val lastType = tracker.lastGifted()
        val paused = lastType != null && tracker.stats(lastType).paused(now, timeout)
        val perType = TreeType.values().joinToString(";") { type ->
            val stats = tracker.stats(type)
            "${type.name}{sessionCount=${stats.sessionCount()}," +
                "sessionMillis=${stats.sessionMillis(now, timeout)}," +
                "totalCount=${stats.totalCount()},totalMillis=${stats.totalMillis(now, timeout)}}"
        }
        return "enabled=${isEnabled()} pauseTimeoutSeconds=${timeout / 1000L} " +
            "showTotals=${totalMode.value()} background=${showBackground.value()} " +
            "lastGifted=${lastType?.name ?: "none"} paused=$paused hudVisible=${visible()} " +
            "treeStats=[$perType] acceptedGifts=$acceptedGifts duplicateGiftLines=$duplicateGiftLines " +
            "unmatchedChatLines=$unmatchedChatLines liveRenderCalls=$liveRenderCalls " +
            "lastRender=${lastRenderX},${lastRenderY},${lastRenderWidth}x$lastRenderHeight " +
            "lastRenderLines=$lastRenderLines"
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.FORAGING, name(), diagnosticTick,
            line.take(MAX_DIAGNOSTIC_LINE_CHARS))
    }

    // ---- HUD --------------------------------------------------------------------------

    override fun hudIslands(): Set<Island> = setOf(Island.THE_PARK, Island.GALATEA, Island.TORRHUS_CANYON)

    override fun id(): String = HUD_ID

    override fun displayName(): String = "Tree Tracker"

    /**
     * Shown only while gifts are actually coming in.
     *
     * Hides on the same timeout that freezes the clock, so the panel is on screen exactly when it
     * is counting - there is no state where a visible read-out is quietly stale. Coming back is a
     * resume rather than a restart: the session was never cleared, only paused.
     */
    override fun visible(): Boolean {
        if (!isEnabled()) return false
        val type = tracker.lastGifted() ?: return false
        return !tracker.stats(type).paused(System.currentTimeMillis(), timeoutMillis())
    }

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun width(font: Font?): Int = width(font!!, lines())

    override fun previewWidth(font: Font?): Int = width(font!!, previewLines())

    private fun width(font: Font, lines: Array<String>): Int {
        var width = MIN_WIDTH
        for (line in lines) width = maxOf(width, font.width(line))
        return width + PADDING * 2
    }

    override fun height(font: Font?): Int = contentHeight(lines())

    override fun previewHeight(font: Font?): Int = contentHeight(previewLines())

    private fun contentHeight(lines: Array<String>): Int = lines.size * LINE_HEIGHT + PADDING * 2

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val currentLines = lines()
        val enabled = isEnabled()
        val renderedWidth = renderLines(graphics, font, x, y, currentLines, enabled)
        if (enabled) recordLiveRender(currentLines, x, y, renderedWidth)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        renderLines(graphics, font, x, y, previewLines(), false)
    }

    private fun renderLines(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        lines: Array<String>,
        collectGeometry: Boolean,
    ): Int {
        val background = showBackground.value()
        val panelWidth = if (background || collectGeometry) width(font, lines) else 0
        if (background) {
            GuiTheme.roundedRect(graphics, x, y, panelWidth, contentHeight(lines), 3, GuiTheme.PANEL_TOP)
        }
        var textY = y + PADDING
        for (i in lines.indices) {
            // The header is dimmed so the numbers under it are what the eye lands on first.
            val color = if (i == 0) GuiTheme.TEXT_SECONDARY else GuiTheme.TEXT_PRIMARY
            graphics.text(font, lines[i], x + PADDING, textY, color)
            textY += LINE_HEIGHT
        }
        return panelWidth
    }

    private fun recordLiveRender(lines: Array<String>, x: Int, y: Int, width: Int) {
        liveRenderCalls = saturatedIncrement(liveRenderCalls)
        lastRenderX = x
        lastRenderY = y
        lastRenderWidth = width
        lastRenderHeight = contentHeight(lines)
        lastRenderLines = lines.joinToString("|") { it.replace('\r', ' ').replace('\n', ' ').take(96) }
            .take(MAX_DIAGNOSTIC_RENDER_TEXT_CHARS)
    }

    private fun previewLines(): Array<String> {
        if (visible()) return lines()
        val name = TreeType.HELIX.displayName()
        return arrayOf(
            if (totalMode.value()) TOTAL_HEADER else HEADER,
            "$name  24/h",
            "$name  2  04:52",
        )
    }

    /**
     * The panel's text, rebuilt at most once a second.
     *
     * Cached because width, height and render each ask for it, so an uncached build would run
     * several times a frame to produce the same three strings - nothing in them changes faster
     * than the seconds in the duration.
     */
    private fun lines(): Array<String> {
        val second = System.currentTimeMillis() / 1000L
        val totals = totalMode.value()
        val current = cachedLines
        if (current == null || second != cachedSecond || totals != cachedTotals) {
            cachedSecond = second
            cachedTotals = totals
            cachedLines = buildLines(totals)
        }
        return checkNotNull(cachedLines)
    }

    private fun buildLines(totals: Boolean): Array<String> {
        val header = if (totals) TOTAL_HEADER else HEADER
        val type = tracker.lastGifted() ?: return arrayOf(header, IDLE)

        val now = System.currentTimeMillis()
        val timeout = timeoutMillis()
        val stats = tracker.stats(type)
        val count = if (totals) stats.totalCount() else stats.sessionCount()
        val millis = if (totals) stats.totalMillis(now, timeout) else stats.sessionMillis(now, timeout)
        val rate = TreeStats.ratePerHour(count, millis)
        val name = type.displayName()
        return arrayOf(
            header,
            "$name  ${if (rate < 0) NO_RATE else rate}/h",
            "$name  $count  ${formatDuration(millis)}",
        )
    }

    private fun saturatedIncrement(value: Int): Int =
        if (value >= MAX_DIAGNOSTIC_COUNT) MAX_DIAGNOSTIC_COUNT else value + 1

    companion object {
        private const val HUD_ID = "tree_tracker"
        private const val HEADER = "Tree Gifts"
        private const val TOTAL_HEADER = "Tree Gifts (Total)"
        /** Used when there isn't enough elapsed time to divide by yet. */
        private const val NO_RATE = "-"
        /** Never drawn in the world - the panel is hidden before a first gift - but sizes its box in Move Elements. */
        private const val IDLE = "No gifts yet"

        private const val LINE_HEIGHT = 10
        private const val PADDING = 4
        /** Wide enough for the longest line the panel can produce, so the box doesn't resize as it counts. */
        private const val MIN_WIDTH = 96

        private const val DEFAULT_PAUSE_SECONDS = 60
        private const val MAX_PAUSE_SECONDS = 86400

        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 4096
        private const val MAX_DIAGNOSTIC_RENDER_TEXT_CHARS = 320

        @JvmField
        val INSTANCE = TreeTrackerModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { INSTANCE.clientTick() }
        }

        private fun resetSession() {
            INSTANCE.resetSession()
        }

        /** mm:ss until it passes an hour, then h:mm:ss. */
        @JvmStatic
        fun formatDuration(millis: Long): String {
            val totalSeconds = maxOf(0L, millis) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                java.lang.String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                java.lang.String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}
