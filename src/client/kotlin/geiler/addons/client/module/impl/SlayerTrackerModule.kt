package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.farming.PersistentHourlyStatsStore
import geiler.addons.client.farming.SlayerAlertRules
import geiler.addons.client.farming.SlayerAlertRules.BossObservation
import geiler.addons.client.farming.SlayerAlertRules.BossType
import geiler.addons.client.farming.SlayerChatParser
import geiler.addons.client.farming.SlayerHourlyStats
import geiler.addons.client.farming.SlayerQuestKillTimeAverage
import geiler.addons.client.farming.SlayerMobClassifier
import geiler.addons.client.farming.SlayerQuestSidebarLifecycle
import geiler.addons.client.farming.SlayerScoreboardParser
import geiler.addons.client.farming.SlayerTimerState
import geiler.addons.client.farming.SlayerTimeDisplay
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.ArrayList
import java.util.EnumMap
import java.util.HashSet
import java.util.Locale
import java.util.UUID

/** Slayer boss rate/timer HUD with filtered, configurable boss and miniboss spawn alerts. */
class SlayerTrackerModule private constructor(settings: Settings) : Module(
    "Slayer Tracker",
    "Counts Slayer bosses per hour, times spawns and kills, and configures spawn alerts.",
    Category.SLAYER,
    false,
    *settings.allSettings.toTypedArray(),
), HudElement {
    private val totalMode = settings.totalMode
    private val showBackground = settings.showBackground
    private val timeDisplay = settings.timeDisplay
    private val bossSpawnAlert = settings.bossSpawnAlert
    private val minibossSpawnAlert = settings.minibossSpawnAlert
    private val unclassifiedBossAlert = settings.unclassifiedBossAlert
    private val alertChat = settings.alertChat
    private val alertTitle = settings.alertTitle
    private val alertSound = settings.alertSound
    private val alertTitleDuration = settings.alertTitleDuration
    private val bossAlertText = settings.bossAlertText
    private val minibossAlertText = settings.minibossAlertText
    private val bossTypeAlerts = EnumMap<BossType, BooleanSetting>(settings.bossTypeAlerts)

    private val stats = SlayerHourlyStats()
    private val timers = SlayerTimerState()
    private val killTimeAverage = SlayerQuestKillTimeAverage()
    private val sidebarLifecycle = SlayerQuestSidebarLifecycle()
    private val visibleMinibosses: MutableSet<UUID> = HashSet()
    private var lastLevel: ClientLevel? = null
    private var scoreboardPollTicks = 0
    private var minibossScanTicks = 0
    private var questActive = false
    private var lastQuestActiveNanos = 0L
    private var bossCountedForQuest = false
    private var lastStatsSaveNanos = 0L
    private var lastSavedTotalNanos = -1L
    private var lastSavedTotalKills = -1
    private var cachedLines: Array<String>? = null
    private var cachedSecond = -1L
    private var cachedTotals = false
    private var cachedTimeDisplay: String? = null

    // Diagnostics are bounded and contain only local state. Raw chat, names, UUIDs, and alert text
    // are deliberately omitted.
    private var diagnosticTick = 0L
    private var lastStatusTick = 0L
    private var eventBudgetTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var lastObservedBoardState: SlayerScoreboardParser.State? = null
    private var bossSpawnTransitions = 0
    private var bossCompletions = 0
    private var failedQuests = 0
    private var questStartMessages = 0
    private var questCompleteMessages = 0
    private var questFailMessages = 0
    private var ignoredChatMessages = 0
    private var bossAlertsFiltered = 0
    private var bossAlertDecisions = 0
    private var minibossAlertDecisions = 0
    private var minibossScans = 0
    private var minibossCandidates = 0
    private var liveHudRenderCalls = 0
    private var lastHudX = 0
    private var lastHudY = 0
    private var lastHudWidth = 0
    private var lastHudHeight = 0
    private var lastHudText = "none"

    init {
        val alertSettings = ArrayList<Setting>(3 + settings.bossTypeAlerts.size + 4)
        alertSettings.add(settings.bossSpawnAlert)
        alertSettings.add(settings.minibossSpawnAlert)
        alertSettings.add(settings.unclassifiedBossAlert)
        settings.bossTypeAlerts.values.forEach { alertSettings.add(it) }
        alertSettings.add(settings.alertChat)
        alertSettings.add(settings.alertTitle)
        alertSettings.add(settings.alertSound)
        alertSettings.add(settings.alertTitleDuration)

        val saved = PersistentHourlyStatsStore.loadSlayer()
        stats.restoreTotal(saved.kills, saved.activeNanos)

        group(
            SettingGroup("Stats", settings.totalMode, settings.resetSessionAction),
            SettingGroup("Display", settings.showBackground, settings.timeDisplay),
            SettingGroup("Alerts", *alertSettings.toTypedArray()),
            SettingGroup("Alert Text", settings.bossAlertText, settings.minibossAlertText),
        )
    }

    override fun isWorkInProgress(): Boolean = true

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
            timers.reset()
            killTimeAverage.reset()
            visibleMinibosses.clear()
            stats.stop()
            scoreboardPollTicks = 0
            minibossScanTicks = 0
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
            val board = SlayerScoreboardParser.parse(readSidebar(level.scoreboard))
            if (board != lastObservedBoardState) {
                diagnosticEvent("SIDEBAR state=${board.name} previous=${lastObservedBoardState?.name ?: "none"}")
                lastObservedBoardState = board
            }
            when (board) {
                SlayerScoreboardParser.State.BOSS_ACTIVE -> {
                    val wasQuestActive = questActive
                    questActive = sidebarLifecycle.observe(board).questActive
                    if (questActive && !wasQuestActive) beginKillTimeQuest("scoreboard")
                    if (timers.onBossSpawn(now)) {
                        bossCountedForQuest = false
                        bossSpawnTransitions = saturatedIncrement(bossSpawnTransitions)
                        diagnosticEvent("BOSS_SPAWN questActive=$questActive alertsEnabled=${bossSpawnAlert.value()}")
                        if (bossSpawnAlert.value()) alertBossSpawn(mc)
                    }
                }
                SlayerScoreboardParser.State.QUEST_ACTIVE -> {
                    val wasQuestActive = questActive
                    questActive = sidebarLifecycle.observe(board).questActive
                    if (questActive && !wasQuestActive) beginKillTimeQuest("scoreboard")
                }
                SlayerScoreboardParser.State.BOSS_SLAIN -> {
                    val update = sidebarLifecycle.observe(board)
                    if (update.bossSlain) {
                        completeBoss(now)
                        killTimeAverage.completeQuest()
                    }
                    questActive = update.questActive
                    visibleMinibosses.clear()
                }
                SlayerScoreboardParser.State.NO_QUEST -> {
                    val update = sidebarLifecycle.observe(board)
                    questActive = update.questActive
                    if (update.questFailed) {
                        bossCountedForQuest = false
                        timers.onQuestFailed()
                        failKillTimeQuest("scoreboard")
                        visibleMinibosses.clear()
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

        if (minibossScanTicks++ >= MINIBOSS_SCAN_TICKS) {
            minibossScanTicks = 0
            scanMinibosses(mc)
        }
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
                beginKillTimeQuest("chat")
                bossCountedForQuest = false
                timers.onQuestFailed()
                diagnosticEvent("QUEST_STARTED source=chat")
            }
            SlayerChatParser.Event.QUEST_COMPLETE -> {
                questCompleteMessages = saturatedIncrement(questCompleteMessages)
                val update = sidebarLifecycle.onQuestCompleted()
                if (update.bossSlain) {
                    completeBoss(now)
                    killTimeAverage.completeQuest()
                }
                questActive = update.questActive
                diagnosticEvent("QUEST_COMPLETED source=chat bossSlain=${update.bossSlain}")
            }
            SlayerChatParser.Event.QUEST_FAILED -> {
                questFailMessages = saturatedIncrement(questFailMessages)
                questActive = sidebarLifecycle.onQuestFailed().questActive
                bossCountedForQuest = false
                timers.onQuestFailed()
                failKillTimeQuest("chat")
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
        timers.reset()
        killTimeAverage.reset()
        visibleMinibosses.clear()
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
        bossSpawnTransitions = 0
        bossCompletions = 0
        failedQuests = 0
        questStartMessages = 0
        questCompleteMessages = 0
        questFailMessages = 0
        ignoredChatMessages = 0
        bossAlertsFiltered = 0
        bossAlertDecisions = 0
        minibossAlertDecisions = 0
        minibossScans = 0
        minibossCandidates = 0
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
        timers.reset()
        killTimeAverage.reset()
        visibleMinibosses.clear()
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

    fun timers(): SlayerTimerState = timers

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
        val timeLines = displayMode().lines("00:18", "01:12")
        val result = ArrayList<String>(2 + timeLines.size)
        result.add("Slayer ${if (totalMode.value()) "total" else "session"}")
        result.add("4.2/h  18 bosses")
        result.addAll(timeLines)
        return result.toTypedArray()
    }

    private fun completeBoss(nowNanos: Long) {
        if (bossCountedForQuest) return
        val duration = timers.onBossKill(nowNanos)
        if (duration < 0L && !questActive) return
        if (duration >= 0L) {
            if (killTimeAverage.record(duration)) {
                cachedLines = null
                diagnosticEvent(
                    "KILL_TIME_SAMPLE samples=${killTimeAverage.sampleCount()} " +
                        "averageSeconds=${killTimeAverage.averageNanos() / SECOND_NANOS}",
                )
            } else {
                diagnosticEvent("KILL_TIME_SAMPLE_IGNORED reason=no-current-quest durationSeconds=${duration / SECOND_NANOS}")
            }
        }
        stats.recordKill(nowNanos)
        bossCountedForQuest = true
        bossCompletions = saturatedIncrement(bossCompletions)
        diagnosticEvent("BOSS_COMPLETED durationSeconds=${duration / SECOND_NANOS} questActive=$questActive")
        persistTotalsNow()
    }

    private fun beginKillTimeQuest(source: String) {
        if (!killTimeAverage.beginQuest()) return
        cachedLines = null
        diagnosticEvent("KILL_TIME_WINDOW_STARTED source=$source")
    }

    private fun failKillTimeQuest(source: String) {
        killTimeAverage.failQuest()
        cachedLines = null
        diagnosticEvent("KILL_TIME_WINDOW_CLEARED source=$source")
    }

    private fun scanMinibosses(mc: Minecraft) {
        if (!shouldScanMinibosses(questActive, minibossSpawnAlert.value(), mc.level != null, mc.player != null)) {
            visibleMinibosses.clear()
            return
        }
        val level = mc.level ?: return
        val player = mc.player ?: return
        val found = HashSet<UUID>()
        var candidateCount = 0
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(MINIBOSS_RANGE))) {
            if (entity === player || entity.distanceToSqr(player) > MINIBOSS_RANGE * MINIBOSS_RANGE) continue
            val name = entity.customName?.string ?: entity.name.string
            if (SlayerMobClassifier.classify(name) != SlayerMobClassifier.Kind.MINIBOSS) continue
            candidateCount = saturatedIncrement(candidateCount)
            val id = entity.uuid
            found.add(id)
            if (!visibleMinibosses.contains(id)) alertMiniboss(name)
        }
        minibossScans = saturatedIncrement(minibossScans)
        minibossCandidates = saturatedAdd(minibossCandidates, candidateCount)
        diagnosticEvent("MINIBOSS_SCAN candidates=$candidateCount newlyVisible=${found.count { it !in visibleMinibosses }} " +
            "tracked=${found.size}")
        visibleMinibosses.clear()
        visibleMinibosses.addAll(found)
    }

    private fun lines(): Array<String> {
        val now = System.nanoTime()
        val second = now / SECOND_NANOS
        val totals = totalMode.value()
        val selectedTimeDisplay = timeDisplay.value()
        if (cachedLines == null || cachedSecond != second || cachedTotals != totals
            || selectedTimeDisplay != cachedTimeDisplay) {
            cachedSecond = second
            cachedTotals = totals
            cachedTimeDisplay = selectedTimeDisplay
            val kills = if (totals) stats.totalKills() else stats.sessionKills()
            val rate = if (totals) stats.totalPerHour() else stats.sessionPerHour()
            val spawn = if (timers.bossActive()) formatDuration(timers.secondsSinceSpawn(now).toLong()) else "-"
            val averageKill = if (killTimeAverage.sampleCount() == 0) "-"
                else formatDuration((killTimeAverage.averageNanos() + SECOND_NANOS / 2) / SECOND_NANOS)
            val displayLines = displayMode().lines(spawn, averageKill)
            val result = ArrayList<String>(2 + displayLines.size)
            result.add("Slayer ${if (totals) "total" else "session"}")
            result.add(String.format(Locale.ROOT, "%.1f/h  %d bosses", rate, kills))
            result.addAll(displayLines)
            cachedLines = result.toTypedArray()
        }
        return checkNotNull(cachedLines)
    }

    private fun displayMode(): SlayerTimeDisplay = SlayerTimeDisplay.valueOf(timeDisplay.value().uppercase(Locale.ROOT))

    private fun formatDuration(seconds: Long): String {
        val safe = maxOf(0L, seconds)
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
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

    private fun alertBossSpawn(mc: Minecraft) {
        val observation = observedBossType(mc)
        if (!SlayerAlertRules.bossFilterAllows(observation, enabledBossFilters(), unclassifiedBossAlert.value())) {
            bossAlertsFiltered = saturatedIncrement(bossAlertsFiltered)
            diagnosticEvent("BOSS_ALERT_FILTERED type=${observation.type?.name ?: "none"} ambiguous=${observation.ambiguous}")
            return
        }
        val bossName = observation.type?.displayName()
        val presentation = SlayerAlertRules.presentation(alertChat.value(), alertTitle.value(), alertSound.value(),
            alertTitleDuration.intValue(), bossAlertText.value(), observation.type, bossName, null)
        bossAlertDecisions = saturatedIncrement(bossAlertDecisions)
        recordAlertDecision("boss", presentation, mc.gui != null)
        emitAlert(presentation)
    }

    private fun alertMiniboss(rawName: String) {
        val presentation = SlayerAlertRules.presentation(alertChat.value(), alertTitle.value(), alertSound.value(),
            alertTitleDuration.intValue(), minibossAlertText.value(), null, null, rawName)
        minibossAlertDecisions = saturatedIncrement(minibossAlertDecisions)
        recordAlertDecision("miniboss", presentation, Minecraft.getInstance().gui != null)
        emitAlert(presentation)
    }

    private fun recordAlertDecision(kind: String, presentation: SlayerAlertRules.Presentation, guiAvailable: Boolean) {
        diagnosticEvent("ALERT kind=$kind guiAvailable=$guiAvailable chat=${presentation.showChat} " +
            "title=${presentation.showTitle} sound=${presentation.playSound} " +
            "durationTicks=${presentation.durationTicks} messageChars=${presentation.message?.length ?: 0}")
    }

    private fun observedBossType(mc: Minecraft): BossObservation {
        val level = mc.level
        val player = mc.player
        if (level == null || player == null) return SlayerAlertRules.observeBossNames(emptyList())
        val names = ArrayList<String>()
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java,
            player.boundingBox.inflate(MINIBOSS_RANGE))) {
            if (entity === player || entity.distanceToSqr(player) > MINIBOSS_RANGE * MINIBOSS_RANGE) continue
            val name = entity.customName?.string ?: entity.name.string
            if (SlayerAlertRules.classifyBossName(name) != null) names.add(name)
        }
        return SlayerAlertRules.observeBossNames(names)
    }

    private fun enabledBossFilters(): EnumMap<BossType, Boolean> {
        val enabled = EnumMap<BossType, Boolean>(BossType::class.java)
        for ((type, setting) in bossTypeAlerts) enabled[type] = setting.value()
        return enabled
    }

    private fun readSidebar(scoreboard: Scoreboard?): String {
        if (scoreboard == null) return ""
        val sidebar: Objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
        val text = StringBuilder(ChatText.plain(sidebar.displayName.string))
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            if (entry == null || entry.isHidden()) continue
            val display = entry.display()
            val line = display ?: run {
                val team: PlayerTeam? = scoreboard.getPlayersTeam(entry.owner())
                team?.getFormattedName(entry.ownerName()) ?: entry.ownerName()
            }
            text.append('\n').append(ChatText.plain(line.string))
        }
        return text.toString()
    }

    private fun emitAlert(presentation: SlayerAlertRules.Presentation) {
        val mc = Minecraft.getInstance()
        val gui = mc.gui
        val message = presentation.message ?: ""
        if (gui != null) {
            if (presentation.showChat) {
                gui.hud.chat.addClientSystemMessage(Component.literal("[Slayer] $message"))
            }
            if (presentation.showTitle) {
                gui.hud.setTimes(0, presentation.durationTicks, 0)
                gui.hud.setTitle(Component.literal(message))
            }
        }
        if (presentation.playSound) {
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f))
        }
    }

    private fun tickDiagnostics(nowNanos: Long) {
        if (!isEnabled() || diagnosticTick - lastStatusTick < STATUS_INTERVAL_TICKS) return
        recordDiagnostic("STATUS intervalTicks=$STATUS_INTERVAL_TICKS ${diagnosticState(nowNanos)} " +
            "suppressedEvents=$suppressedEvents")
        lastStatusTick = diagnosticTick
        bossSpawnTransitions = 0
        bossCompletions = 0
        failedQuests = 0
        questStartMessages = 0
        questCompleteMessages = 0
        questFailMessages = 0
        ignoredChatMessages = 0
        bossAlertsFiltered = 0
        bossAlertDecisions = 0
        minibossAlertDecisions = 0
        minibossScans = 0
        minibossCandidates = 0
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
        val spawnSeconds = if (timers.bossActive()) timers.secondsSinceSpawn(nowNanos).toLong() else -1L
        val killSeconds = if (timers.hasLastKill()) timers.lastKillDurationNanos() / SECOND_NANOS else -1L
        val averageKillSeconds = if (killTimeAverage.sampleCount() == 0) -1L
            else killTimeAverage.averageNanos() / SECOND_NANOS
        val bossFilters = bossTypeAlerts.entries.joinToString(",") { (type, setting) ->
            "${type.name}=${setting.value()}"
        }
        return "enabled=${isEnabled()} active=${isActive()} worldPresent=${mc.level != null} " +
            "playerPresent=${mc.player != null} inDungeon=${DungeonContextTracker.inDungeon()} " +
            "hudVisible=${visible()} questActive=$questActive idleAgeSeconds=$idleAgeSeconds " +
            "sidebar=${lastObservedBoardState?.name ?: "none"} sidebarPollTicks=$scoreboardPollTicks " +
            "minibossScanTicks=$minibossScanTicks visibleMinibossCount=${visibleMinibosses.size} " +
            "bossTimerActive=${timers.bossActive()} spawnSeconds=$spawnSeconds lastMeasuredKillSeconds=$killSeconds " +
            "killAverageQuestActive=${killTimeAverage.questActive()} " +
            "averageKillSamples=${killTimeAverage.sampleCount()} averageKillSeconds=$averageKillSeconds " +
            "sessionKills=${stats.sessionKills()} sessionActiveSeconds=${stats.sessionActiveNanos() / SECOND_NANOS} " +
            "sessionPerHour=${String.format(Locale.ROOT, "%.1f", stats.sessionPerHour())} " +
            "totalKills=${stats.totalKills()} totalActiveSeconds=${stats.totalActiveNanos() / SECOND_NANOS} " +
            "totalPerHour=${String.format(Locale.ROOT, "%.1f", stats.totalPerHour())} " +
            "showTotals=${totalMode.value()} background=${showBackground.value()} timeDisplay=${timeDisplay.value()} " +
            "bossSpawnAlert=${bossSpawnAlert.value()} minibossSpawnAlert=${minibossSpawnAlert.value()} " +
            "unclassifiedBossAlert=${unclassifiedBossAlert.value()} bossTypeFilters=[$bossFilters] " +
            "chatAlert=${alertChat.value()} " +
            "titleAlert=${alertTitle.value()} soundAlert=${alertSound.value()} " +
            "titleDurationTicks=${alertTitleDuration.intValue()} " +
            "bossSpawnTransitions=$bossSpawnTransitions bossCompletions=$bossCompletions failedQuests=$failedQuests " +
            "questStartMessages=$questStartMessages questCompleteMessages=$questCompleteMessages " +
            "questFailMessages=$questFailMessages ignoredChatMessages=$ignoredChatMessages " +
            "bossAlertsFiltered=$bossAlertsFiltered bossAlertDecisions=$bossAlertDecisions " +
            "minibossAlertDecisions=$minibossAlertDecisions minibossScans=$minibossScans " +
            "minibossCandidates=$minibossCandidates liveHudRenderCalls=$liveHudRenderCalls " +
            "lastHud=${lastHudX},${lastHudY},${lastHudWidth}x$lastHudHeight hudText=$lastHudText"
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.SLAYER, name(), diagnosticTick,
            line.replace('\r', ' ').replace('\n', ' ').take(MAX_DIAGNOSTIC_LINE_CHARS))
    }

    private fun saturatedIncrement(value: Int): Int = if (value >= MAX_DIAGNOSTIC_COUNT) MAX_DIAGNOSTIC_COUNT else value + 1

    private fun saturatedAdd(left: Int, right: Int): Int =
        if (MAX_DIAGNOSTIC_COUNT - left < right) MAX_DIAGNOSTIC_COUNT else left + right

    companion object {
        private const val SECOND_NANOS = 1_000_000_000L
        private const val STATS_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val SCOREBOARD_POLL_TICKS = 5
        private const val MINIBOSS_SCAN_TICKS = 10
        private const val MINIBOSS_RANGE = 64.0
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

        @JvmStatic
        fun shouldScanMinibosses(
            questActive: Boolean,
            alertEnabled: Boolean,
            levelPresent: Boolean,
            playerPresent: Boolean,
        ): Boolean = questActive && alertEnabled && levelPresent && playerPresent

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
        val bossTypeAlerts = EnumMap<BossType, BooleanSetting>(BossType::class.java)
        val totalMode = BooleanSetting("Show Totals", false)
        val showBackground = BooleanSetting("Background", true)
        val timeDisplay = ChoiceSetting("Time Display", "Both", "Spawn", "Kill", "Both")
        val bossSpawnAlert = BooleanSetting("Boss Spawn Alert", SlayerAlertRules.DEFAULT_BOSS_ALERT)
        val minibossSpawnAlert = BooleanSetting("Miniboss Spawn Alert", SlayerAlertRules.DEFAULT_MINIBOSS_ALERT)
        val unclassifiedBossAlert = BooleanSetting(
            "Unclassified Boss Alert", SlayerAlertRules.DEFAULT_UNCLASSIFIED_BOSS_ALERT,
        )
        val alertChat = BooleanSetting("Chat Alert", SlayerAlertRules.DEFAULT_CHAT_ALERT)
        val alertTitle = BooleanSetting("Title Alert", SlayerAlertRules.DEFAULT_TITLE_ALERT)
        val alertSound = BooleanSetting("Sound Alert", SlayerAlertRules.DEFAULT_SOUND_ALERT)
        val alertTitleDuration = NumberSetting(
            "Title Duration (ticks)",
            SlayerAlertRules.MIN_TITLE_DURATION_TICKS.toFloat(),
            SlayerAlertRules.MAX_TITLE_DURATION_TICKS.toFloat(),
            SlayerAlertRules.DEFAULT_TITLE_DURATION_TICKS.toFloat(),
            true,
        )
        val bossAlertText = TextSetting("Boss Alert Text", SlayerAlertRules.DEFAULT_BOSS_TEXT, 192)
        val minibossAlertText = TextSetting("Miniboss Alert Text", SlayerAlertRules.DEFAULT_MINIBOSS_TEXT, 192)
        val resetSessionAction = ModuleAction("Reset Session Stats", Runnable { SlayerTrackerModule.resetSession() })

        init {
            allSettings.addAll(listOf(
                totalMode,
                showBackground,
                timeDisplay,
                bossSpawnAlert,
                minibossSpawnAlert,
                resetSessionAction,
                unclassifiedBossAlert,
                alertChat,
                alertTitle,
                alertSound,
                alertTitleDuration,
                bossAlertText,
                minibossAlertText,
            ))
            for (type in BossType.values()) {
                val filter = BooleanSetting(type.settingName() + " Alert", SlayerAlertRules.DEFAULT_BOSS_TYPE_FILTER)
                bossTypeAlerts[type] = filter
                allSettings.add(filter)
            }
        }
    }
}
