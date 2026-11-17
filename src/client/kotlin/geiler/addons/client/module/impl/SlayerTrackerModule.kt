package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.farming.PersistentHourlyStatsStore
import geiler.addons.client.farming.SlayerChatParser
import geiler.addons.client.farming.SlayerHourlyStats
import geiler.addons.client.farming.SlayerQuestSidebarLifecycle
import geiler.addons.client.farming.SlayerScoreboardParser
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.slayer.SlayerSidebar
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import java.util.ArrayList
import java.util.Locale

/** Slayer boss rate HUD. Spawn alerts live in the Slayer Alerts module. */
class SlayerTrackerModule private constructor(settings: Settings) : Module(
    "Slayer Tracker",
    "Counts Slayer bosses per hour.",
    Category.SLAYER,
    false,
    *settings.allSettings.toTypedArray(),
), HudElement {
    private val totalMode = settings.totalMode
    private val showBackground = settings.showBackground
    private val stats = SlayerHourlyStats()
    private val sidebarLifecycle = SlayerQuestSidebarLifecycle()
    private var lastLevel: ClientLevel? = null
    private var scoreboardPollTicks = 0
    private var questActive = false
    private var lastQuestActiveNanos = 0L
    private var bossCountedForQuest = false
    private var bossSeenForQuest = false
    private var lastStatsSaveNanos = 0L
    private var lastSavedTotalNanos = -1L
    private var lastSavedTotalKills = -1
    private var cachedLines: Array<String>? = null
    private var cachedSecond = -1L
    private var cachedTotals = false

    // Diagnostics are bounded and contain only local state. Raw chat, names, UUIDs, and alert text
    // are deliberately omitted.
    private var diagnosticTick = 0L
    private var lastStatusTick = 0L
    private var eventBudgetTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var lastObservedBoardState: SlayerScoreboardParser.State? = null
    private var bossCompletions = 0
    private var failedQuests = 0
    private var questStartMessages = 0
    private var questCompleteMessages = 0
    private var questFailMessages = 0
    private var ignoredChatMessages = 0
    private var liveHudRenderCalls = 0
    private var lastHudX = 0
    private var lastHudY = 0
    private var lastHudWidth = 0
    private var lastHudHeight = 0
    private var lastHudText = "none"

    init {
        val saved = PersistentHourlyStatsStore.loadSlayer()
        stats.restoreTotal(saved.kills, saved.activeNanos)

        group(
            SettingGroup("Stats", settings.totalMode, settings.resetSessionAction),
            SettingGroup("Display", settings.showBackground),
        )
    }

    /** Client END tick hook. Scoreboard reads and entity scans are deliberately throttled. */
    fun tick() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        val now = System.nanoTime()
        val level = mc.level
        if (level !== lastLevel) {
            val previousLevelPresent = lastLevel != null
            lastLevel = level
            questActive = false
            lastQuestActiveNanos = 0L
            sidebarLifecycle.reset()
            bossCountedForQuest = false
            bossSeenForQuest = false
            stats.stop()
            scoreboardPollTicks = 0
            lastObservedBoardState = null
            diagnosticEvent("WORLD_CHANGED previousWorldPresent=$previousLevelPresent currentWorldPresent=${level != null}")
        }
        if (!isEnabled() || level == null || mc.player == null) {
            stats.tick(false, now)
            persistTotalsIfDue(now)
            tickDiagnostics(now)
            return
        }

        if (scoreboardPollTicks++ >= SCOREBOARD_POLL_TICKS) {
            scoreboardPollTicks = 0
            val board = SlayerScoreboardParser.parse(SlayerSidebar.read(level.scoreboard))
            if (board != lastObservedBoardState) {
                diagnosticEvent("SIDEBAR state=${board.name} previous=${lastObservedBoardState?.name ?: "none"}")
                lastObservedBoardState = board
            }
            when (board) {
                SlayerScoreboardParser.State.BOSS_ACTIVE -> {
                    questActive = sidebarLifecycle.observe(board).questActive
                    if (!bossSeenForQuest) {
                        bossSeenForQuest = true
                        bossCountedForQuest = false
                        diagnosticEvent("BOSS_SPAWN questActive=$questActive")
                    }
                }
                SlayerScoreboardParser.State.QUEST_ACTIVE -> {
                    questActive = sidebarLifecycle.observe(board).questActive
                }
                SlayerScoreboardParser.State.BOSS_SLAIN -> {
                    val update = sidebarLifecycle.observe(board)
                    if (update.bossSlain) completeBoss(now)
                    questActive = update.questActive
                }
                SlayerScoreboardParser.State.NO_QUEST -> {
                    val update = sidebarLifecycle.observe(board)
                    questActive = update.questActive
                    if (update.questFailed) {
                        bossCountedForQuest = false
                        bossSeenForQuest = false
                        failedQuests = saturatedIncrement(failedQuests)
                        diagnosticEvent("QUEST_FAILED source=sidebar")
                    }
                }
                SlayerScoreboardParser.State.INCOMPLETE -> {
                    questActive = sidebarLifecycle.observe(board).questActive
                }
            }
        }
        stats.tick(questActive, now)
        if (questActive) lastQuestActiveNanos = now

        persistTotalsIfDue(now)
        tickDiagnostics(now)
    }

    /** Feed raw inbound chat from the existing client message hook. */
    fun onChatMessage(rawMessage: String?) {
        if (rawMessage == null) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(rawMessage) }
            return
        }
        if (!isEnabled() || mc.level == null || mc.player == null) return
        val now = System.nanoTime()
        when (SlayerChatParser.parse(rawMessage)) {
            SlayerChatParser.Event.QUEST_STARTED -> {
                questStartMessages = saturatedIncrement(questStartMessages)
                questActive = sidebarLifecycle.onQuestStarted().questActive
                bossCountedForQuest = false
                bossSeenForQuest = false
                diagnosticEvent("QUEST_STARTED source=chat")
            }
            SlayerChatParser.Event.QUEST_COMPLETE -> {
                questCompleteMessages = saturatedIncrement(questCompleteMessages)
                val update = sidebarLifecycle.onQuestCompleted()
                if (update.bossSlain) completeBoss(now)
                questActive = update.questActive
                diagnosticEvent("QUEST_COMPLETED source=chat bossSlain=${update.bossSlain}")
            }
            SlayerChatParser.Event.QUEST_FAILED -> {
                questFailMessages = saturatedIncrement(questFailMessages)
                questActive = sidebarLifecycle.onQuestFailed().questActive
                bossCountedForQuest = false
                bossSeenForQuest = false
                diagnosticEvent("QUEST_FAILED source=chat")
            }
            SlayerChatParser.Event.NONE -> {
                ignoredChatMessages = saturatedIncrement(ignoredChatMessages)
            }
        }
    }

    fun onWorldChanged() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onWorldChanged() }
            return
        }
        lastLevel = null
        questActive = false
        sidebarLifecycle.reset()
        bossCountedForQuest = false
        bossSeenForQuest = false
        stats.stop()
        diagnosticEvent("WORLD_CHANGE_HOOK")
    }

    override fun onEnable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.SLAYER, name(), true)
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute {
                if (isEnabled()) beginDiagnosticsOnClientThread()
            }
            return
        }
        beginDiagnosticsOnClientThread()
    }

    private fun beginDiagnosticsOnClientThread() {
        if (!isEnabled()) return
        lastStatusTick = diagnosticTick
        eventsThisTick = 0
        suppressedEvents = 0
        eventBudgetTick = Long.MIN_VALUE
        bossCompletions = 0
        failedQuests = 0
        questStartMessages = 0
        questCompleteMessages = 0
        questFailMessages = 0
        ignoredChatMessages = 0
        liveHudRenderCalls = 0
        lastHudX = 0
        lastHudY = 0
        lastHudWidth = 0
        lastHudHeight = 0
        lastHudText = "none"
        diagnosticEvent("FEATURE_ENABLED ${diagnosticState(System.nanoTime())}")
    }

    override fun onDisable() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { disableAndPersistOnClientThread() }
            return
        }
        disableAndPersistOnClientThread()
    }

    private fun disableAndPersistOnClientThread() {
        diagnosticEvent("FEATURE_DISABLED ${diagnosticState(System.nanoTime())}", allowDisabled = true)
        questActive = false
        sidebarLifecycle.reset()
        bossCountedForQuest = false
        bossSeenForQuest = false
        stats.tick(false, System.nanoTime())
        persistTotalsNow()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.SLAYER, name(), false)
    }

    /** Captures the current Slayer elapsed time and enqueues the durable totals snapshot. */
    fun persistFinalTotalsSnapshot() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { persistFinalTotalsSnapshot() }
            return
        }
        val activeQuest = isEnabled() && mc.level != null && mc.player != null && questActive
        stats.tick(activeQuest, System.nanoTime())
        persistTotalsNow()
        if (isEnabled()) diagnosticEvent("FINAL_TOTALS_SNAPSHOT activeQuest=$activeQuest")
    }

    fun stats(): SlayerHourlyStats = stats

    override fun configName(): String = "Slayer Tracker"

    override fun isActive(): Boolean {
        val mc = Minecraft.getInstance()
        return isEnabled() && mc.level != null && mc.player != null
    }

    override fun id(): String = "slayer_tracker"

    override fun displayName(): String = "Slayer Tracker"

    override fun visible(): Boolean {
        return shouldDisplayForContext(
            isActive(), DungeonContextTracker.inDungeon(), questActive, lastQuestActiveNanos, System.nanoTime(),
        )
    }

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun width(font: Font?): Int = width(font!!, lines())

    override fun previewWidth(font: Font?): Int = width(font!!, previewLines())

    private fun width(font: Font, lines: Array<String>): Int {
        var width = 104
        for (line in lines) width = maxOf(width, font.width(line))
        return width + 10
    }

    private fun height(lines: Array<String>, font: Font): Int = lines.size * (font.lineHeight + 1) + 8

    override fun height(font: Font?): Int = height(lines(), font!!)

    override fun previewHeight(font: Font?): Int = height(previewLines(), font!!)

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val currentLines = lines()
        renderLines(graphics, font, x, y, currentLines)
        if (isEnabled()) recordLiveHudRender(currentLines, font, x, y)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        renderLines(graphics, font, x, y, previewLines())
    }

    private fun renderLines(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: Array<String>) {
        if (showBackground.value()) {
            GuiTheme.roundedRect(graphics, x, y, width(font, lines), height(lines, font), 3, GuiTheme.PANEL_TOP)
        }
        var textY = y + 4
        for (index in lines.indices) {
            graphics.text(font, lines[index], x + 5, textY,
                if (index == 0) GuiTheme.TEXT_SECONDARY else GuiTheme.TEXT_PRIMARY)
            textY += font.lineHeight + 1
        }
    }

    private fun recordLiveHudRender(lines: Array<String>, font: Font, x: Int, y: Int) {
        liveHudRenderCalls = saturatedIncrement(liveHudRenderCalls)
        lastHudX = x
        lastHudY = y
        lastHudWidth = width(font, lines)
        lastHudHeight = height(lines, font)
        lastHudText = lines.joinToString("|") { it.replace('\r', ' ').replace('\n', ' ').take(96) }
            .take(MAX_DIAGNOSTIC_HUD_TEXT_CHARS)
    }

    private fun previewLines(): Array<String> {
        if (visible()) return lines()
        return arrayOf("Slayer ${if (totalMode.value()) "total" else "session"}", "4.2/h  18 bosses")
    }

    private fun completeBoss(nowNanos: Long) {
        if (bossCountedForQuest) return
        if (!bossSeenForQuest && !questActive) return
        stats.recordKill(nowNanos)
        bossCountedForQuest = true
        bossSeenForQuest = false
        cachedLines = null
        bossCompletions = saturatedIncrement(bossCompletions)
        diagnosticEvent("BOSS_COMPLETED questActive=$questActive")
        persistTotalsNow()
    }

    private fun lines(): Array<String> {
        val second = System.nanoTime() / SECOND_NANOS
        val totals = totalMode.value()
        if (cachedLines == null || cachedSecond != second || cachedTotals != totals) {
            cachedSecond = second
            cachedTotals = totals
            val kills = if (totals) stats.totalKills() else stats.sessionKills()
            val rate = if (totals) stats.totalPerHour() else stats.sessionPerHour()
            cachedLines = arrayOf(
                "Slayer ${if (totals) "total" else "session"}",
                String.format(Locale.ROOT, "%.1f/h  %d bosses", rate, kills),
            )
        }
        return checkNotNull(cachedLines)
    }

    private fun persistTotalsIfDue(now: Long) {
        if (stats.totalKills() == lastSavedTotalKills && stats.totalActiveNanos() == lastSavedTotalNanos) return
        if (lastStatsSaveNanos == 0L || now - lastStatsSaveNanos >= STATS_SAVE_INTERVAL_NANOS) persistTotalsNow()
    }

    private fun persistTotalsNow() {
        PersistentHourlyStatsStore.saveSlayer(stats.totalKills(), stats.totalActiveNanos())
        lastSavedTotalKills = stats.totalKills()
        lastSavedTotalNanos = stats.totalActiveNanos()
        lastStatsSaveNanos = System.nanoTime()
    }

    private fun tickDiagnostics(nowNanos: Long) {
        if (!isEnabled() || diagnosticTick - lastStatusTick < STATUS_INTERVAL_TICKS) return
        recordDiagnostic("STATUS intervalTicks=$STATUS_INTERVAL_TICKS ${diagnosticState(nowNanos)} " +
            "suppressedEvents=$suppressedEvents")
        lastStatusTick = diagnosticTick
        bossCompletions = 0
        failedQuests = 0
        questStartMessages = 0
        questCompleteMessages = 0
        questFailMessages = 0
        ignoredChatMessages = 0
        liveHudRenderCalls = 0
        suppressedEvents = 0
    }

    private fun diagnosticEvent(event: String, allowDisabled: Boolean = false) {
        if (!isEnabled() && !allowDisabled) return
        if (eventBudgetTick != diagnosticTick) {
            eventBudgetTick = diagnosticTick
            eventsThisTick = 0
        }
        if (eventsThisTick >= MAX_DIAGNOSTIC_EVENTS_PER_TICK) {
            suppressedEvents = saturatedIncrement(suppressedEvents)
            return
        }
        eventsThisTick++
        recordDiagnostic("EVENT $event ${diagnosticState(System.nanoTime())} suppressedEvents=$suppressedEvents")
    }

    private fun diagnosticState(nowNanos: Long): String {
        val mc = Minecraft.getInstance()
        val idleAgeSeconds = if (lastQuestActiveNanos <= 0L) -1L
            else maxOf(0L, nowNanos - lastQuestActiveNanos) / SECOND_NANOS
        return "enabled=${isEnabled()} active=${isActive()} worldPresent=${mc.level != null} " +
            "playerPresent=${mc.player != null} inDungeon=${DungeonContextTracker.inDungeon()} " +
            "hudVisible=${visible()} questActive=$questActive idleAgeSeconds=$idleAgeSeconds " +
            "sidebar=${lastObservedBoardState?.name ?: "none"} sidebarPollTicks=$scoreboardPollTicks " +
            "bossSeenForQuest=$bossSeenForQuest bossCounted=$bossCountedForQuest " +
            "sessionKills=${stats.sessionKills()} sessionActiveSeconds=${stats.sessionActiveNanos() / SECOND_NANOS} " +
            "sessionPerHour=${String.format(Locale.ROOT, "%.1f", stats.sessionPerHour())} " +
            "totalKills=${stats.totalKills()} totalActiveSeconds=${stats.totalActiveNanos() / SECOND_NANOS} " +
            "totalPerHour=${String.format(Locale.ROOT, "%.1f", stats.totalPerHour())} " +
            "showTotals=${totalMode.value()} background=${showBackground.value()} " +
            "bossCompletions=$bossCompletions failedQuests=$failedQuests " +
            "questStartMessages=$questStartMessages questCompleteMessages=$questCompleteMessages " +
            "questFailMessages=$questFailMessages ignoredChatMessages=$ignoredChatMessages " +
            "liveHudRenderCalls=$liveHudRenderCalls " +
            "lastHud=${lastHudX},${lastHudY},${lastHudWidth}x$lastHudHeight hudText=$lastHudText"
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.SLAYER, name(), diagnosticTick,
            line.replace('\r', ' ').replace('\n', ' ').take(MAX_DIAGNOSTIC_LINE_CHARS))
    }

    private fun saturatedIncrement(value: Int): Int = if (value >= MAX_DIAGNOSTIC_COUNT) MAX_DIAGNOSTIC_COUNT else value + 1

    companion object {
        private const val SECOND_NANOS = 1_000_000_000L
        private const val STATS_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val SCOREBOARD_POLL_TICKS = 5
        private const val IDLE_HIDE_NANOS = 30_000_000_000L
        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 4096
        private const val MAX_DIAGNOSTIC_HUD_TEXT_CHARS = 320

        @JvmField
        val INSTANCE = SlayerTrackerModule(Settings())

        @JvmStatic
        fun shouldDisplayForQuestState(questActive: Boolean, lastQuestActiveNanos: Long, nowNanos: Long): Boolean {
            if (questActive) return true
            val idleNanos = nowNanos - lastQuestActiveNanos
            return lastQuestActiveNanos > 0L && idleNanos >= 0L && idleNanos < IDLE_HIDE_NANOS
        }

        @JvmStatic
        fun shouldDisplayForContext(
            active: Boolean,
            inDungeon: Boolean,
            questActive: Boolean,
            lastQuestActiveNanos: Long,
            nowNanos: Long,
        ): Boolean = active && !inDungeon && shouldDisplayForQuestState(questActive, lastQuestActiveNanos, nowNanos)

        private fun resetSession() {
            INSTANCE.resetSessionStats()
        }
    }

    private fun resetSessionStats() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { resetSessionStats() }
            return
        }
        stats.resetSession(System.nanoTime())
        persistTotalsNow()
        if (isEnabled()) diagnosticEvent("SESSION_RESET")
    }

    private class Settings {
        val allSettings = ArrayList<Setting>()
        val totalMode = BooleanSetting("Show Totals", false)
        val showBackground = BooleanSetting("Background", true)
        val resetSessionAction = ModuleAction("Reset Session Stats", Runnable { SlayerTrackerModule.resetSession() })

        init {
            allSettings.addAll(listOf(totalMode, showBackground, resetSessionAction))
        }
    }
}
