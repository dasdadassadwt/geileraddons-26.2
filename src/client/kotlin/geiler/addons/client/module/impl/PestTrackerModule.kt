package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.farming.GardenCurrentPlotParser
import geiler.addons.client.farming.GardenPlotGrid
import geiler.addons.client.farming.InfestedPlotTracker
import geiler.addons.client.farming.PestCooldownText
import geiler.addons.client.farming.PestCooldownTimer
import geiler.addons.client.farming.PestHourlyStats
import geiler.addons.client.farming.PestSpawnAverage
import geiler.addons.client.farming.PestSpawnChatParser
import geiler.addons.client.farming.PestToolFilter
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.farming.PersistentHourlyStatsStore
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.KeyMappingMirror
import geiler.addons.client.module.ClientKeyMappingRegistrar
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.macro.MacroScript
import geiler.addons.client.tree.ChatText
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.scores.DisplaySlot
import java.util.Locale
import java.util.Optional
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** Pest cooldown HUD, spawn tracking, persistent kill rate, and chat-derived infested-plot warp. */
class PestTrackerModule private constructor(private val settings: Settings) : Module(
    "Pest Tracker",
    "Shows pest cooldowns, spawn timing, kill rates, and recent infested plots.",
    Category.FARMING,
    false,
    *settings.allSettings.toTypedArray(),
), HudElement {
    private val totalMode = settings.totalMode
    private val showBackground = settings.showBackground
    private val filterByTool = settings.filterByTool
    private val farmingToolFilter = settings.farmingToolFilter
    private val vacuumFilter = settings.vacuumFilter
    private val lassoFilter = settings.lassoFilter
    private val excludeAfk = settings.excludeAfk
    private val afkGapLimitSeconds = settings.afkGapLimitSeconds
    private val useCustomCooldown = settings.useCustomCooldown
    private val customCooldownSeconds = settings.customCooldownSeconds
    private val cooldownWarningLeadSeconds = settings.cooldownWarningLeadSeconds
    private val finnegan = settings.finnegan
    private val spawnAlert = settings.spawnAlert
    private val cooldownWarningAlert = settings.cooldownWarningAlert
    private val cooldownReadyAlert = settings.cooldownReadyAlert
    private val alertChat = settings.alertChat
    private val alertTitle = settings.alertTitle
    private val alertSound = settings.alertSound

    private val widgetReader = PestWidgetReader()
    private val stats = PestHourlyStats()
    private val spawnAverage = PestSpawnAverage()
    private val cooldown = PestCooldownTimer()
    private val infestedPlots = InfestedPlotTracker()
    private var lastLevel: ClientLevel? = null
    private var ticksUntilWidgetRead = 0
    private var ticksWithoutGarden = 0
    private var wasInGarden = false
    private var lastStatsSaveNanos = 0L
    private var lastSavedTotalNanos = -1L
    private var lastSavedTotalKills = -1
    private var cachedLines: Array<String>? = null
    private var cachedSecond = -1L
    private var cachedTotals = false

    // Automatic, bounded diagnostics run while this module is enabled. They omit raw chat and player data.
    private var diagnosticTick = 0L
    private var lastStatusTick = 0L
    private var eventBudgetTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var widgetReadsObserved = 0
    private var widgetReadMisses = 0
    private var spawnEvents = 0
    private var cleanEvents = 0
    private var killEvents = 0
    private var cropActions = 0
    private var warningEvents = 0
    private var readyEvents = 0
    private var warpAttempts = 0
    private var warpSuccesses = 0
    private var lastDiagnosticWidgetState = PestCooldownText.State.UNKNOWN
    private var lastDiagnosticWidgetSeconds = -1

    init {
        val saved = PersistentHourlyStatsStore.loadPests()
        stats.restoreTotal(saved.kills, saved.activeNanos)
        group(
            SettingGroup("Rates", settings.totalMode, settings.resetSessionAction),
            SettingGroup(
                "Pest Timer",
                settings.useCustomCooldown,
                settings.customCooldownSeconds,
                settings.cooldownWarningLeadSeconds,
                settings.finnegan,
                settings.excludeAfk,
                settings.afkGapLimitSeconds,
            ),
            SettingGroup(
                "HUD Filter",
                settings.filterByTool,
                settings.farmingToolFilter,
                settings.vacuumFilter,
                settings.lassoFilter,
                settings.showBackground,
            ),
            SettingGroup(
                "Alerts",
                settings.spawnAlert,
                settings.cooldownWarningAlert,
                settings.cooldownReadyAlert,
                settings.alertChat,
                settings.alertTitle,
                settings.alertSound,
            ),
            SettingGroup("Infested Plots", settings.warpToInfestedAction, settings.warpKey.setting),
        )
    }

    override fun isWorkInProgress(): Boolean = true

    override fun onEnable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), true)
        diagnosticEvent("FEATURE_ENABLED")
    }

    override fun onDisable() {
        diagnosticEvent("FEATURE_DISABLED", allowDisabled = true)
        persistFinalTotalsSnapshot()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), false)
    }

    fun stats(): PestHourlyStats = stats

    fun spawnAverage(): PestSpawnAverage = spawnAverage

    fun infestedPlots(): InfestedPlotTracker = infestedPlots

    /** Client END tick hook. The Garden context, widget, and timer all advance from real client ticks. */
    fun tick() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        val now = System.nanoTime()
        settings.warpKey.sync()
        while (registeredWarpKey.consumeClick()) {
            if (isEnabled() && mc.gui.screen() == null) warpToLatestInfestedPlot()
        }
        val level = mc.level
        if (level !== lastLevel) {
            val previousLevelPresent = lastLevel != null
            lastLevel = level
            resetTransientState()
            if (isEnabled()) {
                diagnosticEvent(
                    "WORLD_CHANGED previousWorldPresent=$previousLevelPresent currentWorldPresent=${level != null}",
                )
            }
        }
        val garden = isEnabled() && level != null && mc.player != null && HypixelModApi.onGarden()
        stats.tick(garden, now)
        if (!garden) {
            ticksUntilWidgetRead = 0
            if (wasInGarden && ++ticksWithoutGarden >= CONTEXT_GRACE_TICKS) {
                spawnAverage.reset()
                cooldown.reset()
                wasInGarden = false
                ticksWithoutGarden = 0
                diagnosticEvent("GARDEN_CONTEXT_EXPIRED")
            }
            persistTotalsIfDue(now)
            tickDiagnostics(now)
            return
        }
        wasInGarden = true
        ticksWithoutGarden = 0
        if (ticksUntilWidgetRead-- <= 0) {
            ticksUntilWidgetRead = WIDGET_POLL_TICKS - 1
            val observed = widgetReader.readCooldown()
            if (observed.isPresent) {
                val value = observed.get()
                cooldown.observeWidget(value, now)
                widgetReadsObserved = saturatedIncrement(widgetReadsObserved)
                val state = value.state()
                val seconds = value.remainingSeconds()
                if (state != lastDiagnosticWidgetState || seconds != lastDiagnosticWidgetSeconds) {
                    diagnosticEvent("WIDGET state=${state.name} remainingSeconds=$seconds")
                    lastDiagnosticWidgetState = state
                    lastDiagnosticWidgetSeconds = seconds
                }
            } else {
                widgetReadMisses = saturatedIncrement(widgetReadMisses)
            }
        }
        val customCooldown = useCustomCooldown.value()
        val customSeconds = effectiveCustomCooldownSeconds()
        if (cooldownWarningAlert.value() && cooldown.consumeWarningEvent(
                now,
                customCooldown,
                customSeconds,
                cooldownWarningLeadSeconds.intValue(),
            )
        ) {
            warningEvents = saturatedIncrement(warningEvents)
            val remaining = ceil(cooldown.remainingSeconds(now, customCooldown, customSeconds)).toInt()
            diagnosticEvent("COOLDOWN_WARNING remainingSeconds=$remaining")
            emitTrackingAlert("Pest cooldown is ready in $remaining seconds.")
        }
        if (cooldown.consumeReadyEvent(now, customCooldown, customSeconds)) {
            readyEvents = saturatedIncrement(readyEvents)
            diagnosticEvent("COOLDOWN_READY macroEvent=true alertEnabled=${cooldownReadyAlert.value()}")
            MacroRunner.triggerExternalEvent(MacroScript.Trigger.PEST_COOLDOWN_READY)
            if (cooldownReadyAlert.value()) emitTrackingAlert("Pest cooldown is ready.")
        }
        persistTotalsIfDue(now)
        tickDiagnostics(now)
    }

    /** Feed raw inbound chat from the one existing client message hook. */
    fun onChatMessage(rawMessage: String?) {
        if (rawMessage == null) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(rawMessage) }
            return
        }
        if (!isEnabled() || mc.level == null || mc.player == null || !HypixelModApi.onGarden()) return
        val now = System.nanoTime()
        val spawn = PestSpawnChatParser.parseSpawn(rawMessage)
        if (spawn.isPresent) {
            val event = spawn.get()
            infestedPlots.observeSpawn(event, now)
            cooldown.onSpawn(now)
            val afkLimit = afkGapLimitSeconds.intValue().toLong() * SECOND_NANOS
            spawnAverage.onSpawn(now, afkLimit, excludeAfk.value(), !event.whileOffline())
            spawnEvents = saturatedIncrement(spawnEvents)
            diagnosticEvent(
                "PEST_SPAWN amount=${event.knownAmount()} offline=${event.whileOffline()} " +
                    "plotCount=${event.plotNames().size} plots=${diagnosticPlots(event.plotNames())}",
            )
            MacroRunner.triggerExternalEvent(MacroScript.Trigger.PEST_SPAWNED)
            if (spawnAlert.value()) emitTrackingAlert(formatSpawnAlert(event))
            return
        }
        val cleanPlot = PestSpawnChatParser.parseCleanPlot(rawMessage)
        if (cleanPlot.isPresent) {
            val plot = cleanPlot.get()
            infestedPlots.observeCleanPlot(plot)
            cleanEvents = saturatedIncrement(cleanEvents)
            diagnosticEvent("PLOT_CLEAN plot=${diagnosticToken(plot)}")
        }
        if (PestSpawnChatParser.isPestKill(rawMessage)) {
            stats.recordKill(now)
            killEvents = saturatedIncrement(killEvents)
            diagnosticEvent("PEST_KILL recorded=true")
            persistTotalsNow()
        }
    }

    /** Hook this from accepted crop-block interactions so long idle gaps can be excluded from averages. */
    fun onCropAction() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onCropAction() }
            return
        }
        if (!isEnabled() || mc.level == null || mc.player == null || !HypixelModApi.onGarden()) return
        spawnAverage.onCropAction(System.nanoTime())
        cropActions = saturatedIncrement(cropActions)
    }

    fun onDisconnect() {
        lastLevel = null
        resetTransientState()
        infestedPlots.onDisconnect()
        if (isEnabled()) diagnosticEvent("DISCONNECT_SESSION_PLOTS_CLEARED")
    }

    /** Chooses the newest known infested plot other than the current one when an alternative exists. */
    fun warpToLatestInfestedPlot(): Boolean {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { warpToLatestInfestedPlot() }
            return false
        }
        warpAttempts = saturatedIncrement(warpAttempts)
        if (!isEnabled() || mc.level == null || mc.player == null || !HypixelModApi.onGarden()) {
            diagnosticEvent("WARP_REJECTED reason=not-on-garden")
            alert("Join the Garden before warping to an infested plot.")
            return false
        }
        val current = readCurrentPlotName(mc).orElse("")
        val target = infestedPlots.newestWarpTarget(current)
        if (target.isEmpty) {
            diagnosticEvent("WARP_REJECTED reason=no-known-infested-plot")
            alert("No infested plot name has been learned from chat yet.")
            return false
        }
        val targetPlot = target.get()
        val commandName = if (targetPlot.name.equals("The Barn", ignoreCase = true)) "barn" else targetPlot.name
        val connection = mc.player?.connection
        if (connection == null) {
            diagnosticEvent("WARP_REJECTED reason=disconnected")
            alert("Cannot warp while disconnected.")
            return false
        }
        connection.sendCommand("plottp $commandName")
        warpSuccesses = saturatedIncrement(warpSuccesses)
        diagnosticEvent("WARP_SUBMITTED plot=${diagnosticToken(targetPlot.name)} current=${diagnosticToken(current)}")
        return true
    }

    /** Captures the current Garden elapsed time and enqueues the durable totals snapshot. */
    fun persistFinalTotalsSnapshot() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { persistFinalTotalsSnapshot() }
            return
        }
        val garden = isEnabled() && mc.level != null && mc.player != null && HypixelModApi.onGarden()
        stats.tick(garden, System.nanoTime())
        persistTotalsNow()
    }

    override fun configName(): String = "Pest Tracker"

    override fun isActive(): Boolean {
        val mc = Minecraft.getInstance()
        return isEnabled() && mc.level != null && mc.player != null && HypixelModApi.onGarden()
    }

    override fun hudIslands(): Set<Island> = setOf(Island.GARDEN)

    override fun id(): String = "pest_tracker"

    override fun displayName(): String = "Pest Tracker"

    override fun visible(): Boolean {
        val mc = Minecraft.getInstance()
        if (!isActive() || mc.player == null) return false
        val main = mc.player?.mainHandItem ?: return false
        val off = mc.player?.offhandItem ?: return false
        return PestToolFilter.shouldShow(
            filterByTool.value(),
            farmingToolFilter.value(),
            vacuumFilter.value(),
            lassoFilter.value(),
            main.hoverName.string,
            off.hoverName.string,
        )
    }

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun width(font: Font?): Int = width(font!!, lines())

    override fun previewWidth(font: Font?): Int = width(font!!, previewLines())

    private fun width(font: Font, lines: Array<String>): Int {
        var width = 100
        for (line in lines) width = max(width, font.width(line))
        return width + 10
    }

    override fun height(font: Font?): Int = height(lines(), font!!)

    override fun previewHeight(font: Font?): Int = height(previewLines(), font!!)

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        renderLines(graphics, font, x, y, lines())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        renderLines(graphics, font, x, y, previewLines())
    }

    private fun renderLines(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: Array<String>) {
        if (showBackground.value()) {
            GuiTheme.roundedRect(graphics, x, y, width(font, lines), height(lines, font), 3, GuiTheme.PANEL_TOP)
        }
        var textY = y + 4
        lines.forEachIndexed { index, line ->
            graphics.text(font, line, x + 5, textY, if (index == 0) GuiTheme.TEXT_SECONDARY else GuiTheme.TEXT_PRIMARY)
            textY += font.lineHeight + 1
        }
    }

    private fun previewLines(): Array<String> =
        if (visible()) {
            lines()
        } else {
            arrayOf(
                "Pests ${if (totalMode.value()) "total" else "session"}",
                "2.4/h  12 kills",
                "Cooldown  00:42",
                "Last spawn  04:18",
                "Average gap  05:36",
            )
        }

    private fun lines(): Array<String> {
        val now = System.nanoTime()
        val second = now / SECOND_NANOS
        val totals = totalMode.value()
        val current = cachedLines
        if (current == null || cachedSecond != second || cachedTotals != totals) {
            val updated = buildLines(now, totals)
            cachedLines = updated
            cachedSecond = second
            cachedTotals = totals
            return updated
        }
        return current
    }

    private fun buildLines(now: Long, totals: Boolean): Array<String> {
        val kills = if (totals) stats.totalKills() else stats.sessionKills()
        val rate = if (totals) stats.totalKillsPerHour() else stats.sessionKillsPerHour()
        val lastSpawn = if (!spawnAverage.hasSpawn()) "-" else formatDuration(
            (now - spawnAverage.lastSpawnNanos()) / SECOND_NANOS,
        )
        val cooldownSeconds = cooldown.remainingSeconds(now, useCustomCooldown.value(), effectiveCustomCooldownSeconds())
        val cooldownText = when {
            cooldown.widgetState() == PestCooldownText.State.MAX_PESTS -> "MAX PESTS"
            cooldownSeconds < 0 -> "unknown"
            cooldownSeconds == 0.0 && cooldown.widgetState() == PestCooldownText.State.READY -> "READY"
            cooldownSeconds == 0.0 -> "Waiting"
            else -> formatDuration(ceil(cooldownSeconds).toLong())
        }
        val average = spawnAverage.averageSeconds()
        return arrayOf(
            "Pests ${if (totals) "total" else "session"}",
            String.format(Locale.ROOT, "%.1f/h  %d kills", rate, kills),
            "Cooldown  $cooldownText",
            "Last spawn  $lastSpawn",
            "Average gap  ${if (average <= 0) "-" else formatDuration(average.roundToLong())}",
        )
    }

    private fun effectiveCustomCooldownSeconds(): Int = if (finnegan.value()) 75 else customCooldownSeconds.intValue()

    private fun persistTotalsIfDue(now: Long) {
        if (stats.totalKills() == lastSavedTotalKills && stats.totalGardenNanos() == lastSavedTotalNanos) return
        if (lastStatsSaveNanos == 0L || now - lastStatsSaveNanos >= STATS_SAVE_INTERVAL_NANOS) persistTotalsNow()
    }

    private fun persistTotalsNow() {
        PersistentHourlyStatsStore.savePests(stats.totalKills(), stats.totalGardenNanos())
        lastSavedTotalKills = stats.totalKills()
        lastSavedTotalNanos = stats.totalGardenNanos()
        lastStatsSaveNanos = System.nanoTime()
    }

    private fun resetTransientState() {
        spawnAverage.reset()
        cooldown.reset()
        ticksUntilWidgetRead = 0
        ticksWithoutGarden = 0
        wasInGarden = false
        lastDiagnosticWidgetState = PestCooldownText.State.UNKNOWN
        lastDiagnosticWidgetSeconds = -1
    }

    private fun formatSpawnAlert(spawn: PestSpawnChatParser.Spawn): String {
        val locations = formatPlotLocations(spawn.plotNames())
        if (spawn.whileOffline()) return "Pests spawned while you were offline in $locations."

        val amount = spawn.knownAmount()
        if (amount <= 0) return "Pests spawned in $locations."
        val pest = if (amount == 1) "pest" else "pests"
        return "$amount $pest spawned in $locations."
    }

    private fun formatPlotLocations(plotNames: List<String>): String {
        val distinctLocations = plotNames.asSequence().map(::displayPlotName).distinct()
        val locations = distinctLocations.take(MAX_SPAWN_TITLE_PLOTS).toList()
        if (locations.isEmpty()) return "an unknown plot"
        val distinctCount = plotNames.asSequence().map(::displayPlotName).distinct().count()
        val joined = when (locations.size) {
            1 -> locations[0]
            2 -> "${locations[0]} and ${locations[1]}"
            else -> locations.dropLast(1).joinToString(", ") + ", and ${locations.last()}"
        }
        val remaining = distinctCount - locations.size
        return if (remaining > 0) "$joined and $remaining more" else joined
    }

    private fun displayPlotName(name: String): String {
        val id = name.toIntOrNull()
        return if (id != null && GardenPlotGrid.isValidId(id)) "Plot $id" else name
    }

    private fun alert(text: String) {
        val mc = Minecraft.getInstance()
        val gui = mc.gui ?: return
        gui.hud.chat.addClientSystemMessage(Component.literal("[Pests] $text"))
    }

    private fun emitTrackingAlert(text: String) {
        val mc = Minecraft.getInstance()
        val gui = mc.gui
        if (gui != null) {
            if (alertChat.value()) gui.hud.chat.addClientSystemMessage(Component.literal("[Pests] $text"))
            if (alertTitle.value()) {
                gui.hud.setTimes(0, 40, 0)
                gui.hud.setTitle(Component.literal(text))
            }
        }
        if (alertSound.value()) {
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
        }
        diagnosticEvent(
            "ALERT_SUBMITTED guiAvailable=${gui != null} chat=${alertChat.value()} " +
                "title=${alertTitle.value()} sound=${alertSound.value()}",
        )
    }

    private fun tickDiagnostics(nowNanos: Long) {
        if (!isEnabled() || diagnosticTick - lastStatusTick < STATUS_INTERVAL_TICKS) return
        val mc = Minecraft.getInstance()
        val remaining = cooldown.remainingSeconds(
            nowNanos,
            useCustomCooldown.value(),
            effectiveCustomCooldownSeconds(),
        )
        val latestPlot = infestedPlots.latest().map { diagnosticToken(it.name) }.orElse("none")
        recordDiagnostic(
            "STATUS intervalTicks=$STATUS_INTERVAL_TICKS enabled=true active=${isActive()} " +
                "worldPresent=${mc.level != null} playerPresent=${mc.player != null} garden=$wasInGarden " +
                "widgetState=${cooldown.widgetState().name} widgetRemainingSeconds=${diagnosticSeconds(remaining)} " +
                "widgetReadsObserved=$widgetReadsObserved widgetReadMisses=$widgetReadMisses " +
                "spawnEvents=$spawnEvents cleanEvents=$cleanEvents killEvents=$killEvents cropActions=$cropActions " +
                "warningEvents=$warningEvents readyEvents=$readyEvents " +
                "sessionKills=${stats.sessionKills()} sessionGardenSeconds=${stats.sessionGardenNanos() / SECOND_NANOS} " +
                "sessionPerHour=${String.format(Locale.ROOT, "%.1f", stats.sessionKillsPerHour())} " +
                "totalKills=${stats.totalKills()} totalGardenSeconds=${stats.totalGardenNanos() / SECOND_NANOS} " +
                "totalPerHour=${String.format(Locale.ROOT, "%.1f", stats.totalKillsPerHour())} " +
                "spawnSeen=${spawnAverage.hasSpawn()} averageSamples=${spawnAverage.sampleCount()} " +
                "averageSeconds=${diagnosticSeconds(spawnAverage.averageSeconds())} " +
                "knownInfestedPlots=${infestedPlots.snapshot().size} latestPlot=$latestPlot " +
                "totalMode=${totalMode.value()} background=${showBackground.value()} " +
                "toolFilter=${filterByTool.value()} farmingTools=${farmingToolFilter.value()} " +
                "vacuum=${vacuumFilter.value()} lasso=${lassoFilter.value()} excludeAfk=${excludeAfk.value()} " +
                "afkGapLimitSeconds=${afkGapLimitSeconds.intValue()} customCooldown=${useCustomCooldown.value()} " +
                "effectiveCooldownSeconds=${effectiveCustomCooldownSeconds()} " +
                "warningLeadSeconds=${cooldownWarningLeadSeconds.intValue()} finnegan=${finnegan.value()} " +
                "spawnAlert=${spawnAlert.value()} warningAlert=${cooldownWarningAlert.value()} " +
                "readyAlert=${cooldownReadyAlert.value()} alertChat=${alertChat.value()} " +
                "alertTitle=${alertTitle.value()} alertSound=${alertSound.value()} " +
                "warpAttempts=$warpAttempts warpSuccesses=$warpSuccesses suppressedEvents=$suppressedEvents",
        )
        lastStatusTick = diagnosticTick
        widgetReadsObserved = 0
        widgetReadMisses = 0
        spawnEvents = 0
        cleanEvents = 0
        killEvents = 0
        cropActions = 0
        warningEvents = 0
        readyEvents = 0
        warpAttempts = 0
        warpSuccesses = 0
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
        recordDiagnostic("EVENT ${event.replace('\r', ' ').replace('\n', ' ')}")
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.FARMING,
            name(),
            diagnosticTick,
            line.replace('\r', ' ').replace('\n', ' ').take(MAX_DIAGNOSTIC_LINE_CHARS),
        )
    }

    private fun diagnosticPlots(plots: List<String>): String {
        val sample = plots.take(MAX_DIAGNOSTIC_PLOT_SAMPLES).joinToString(",") { diagnosticToken(it) }
        return if (plots.size > MAX_DIAGNOSTIC_PLOT_SAMPLES) "$sample,+${plots.size - MAX_DIAGNOSTIC_PLOT_SAMPLES}" else sample
    }

    private fun diagnosticToken(value: String): String =
        value.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' }
            .trim()
            .take(MAX_DIAGNOSTIC_TOKEN_CHARS)
            .ifEmpty { "none" }

    private fun diagnosticSeconds(value: Double): String =
        if (!value.isFinite()) "unknown" else String.format(Locale.ROOT, "%.1f", value)

    private fun saturatedIncrement(value: Int): Int = if (value >= MAX_DIAGNOSTIC_COUNT) MAX_DIAGNOSTIC_COUNT else value + 1

    companion object {
        private const val SECOND_NANOS = 1_000_000_000L
        private const val STATS_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val WIDGET_POLL_TICKS = 10
        private const val CONTEXT_GRACE_TICKS = 40
        private const val MAX_AFK_GAP_SECONDS = 3600
        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 4096
        private const val MAX_DIAGNOSTIC_PLOT_SAMPLES = 4
        private const val MAX_DIAGNOSTIC_TOKEN_CHARS = 48
        private const val MAX_SPAWN_TITLE_PLOTS = 5

        private val registeredWarpKey = ClientKeyMappingRegistrar.register(
            KeyMapping(
                "key.geileraddons.warp_to_latest_infested_plot",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                com.mojang.blaze3d.platform.InputConstants.KEY_G,
                KeyMapping.Category.GAMEPLAY,
            ),
        )

        @JvmField
        val INSTANCE = PestTrackerModule(Settings())

        @JvmStatic
        fun warpToInfestedPlotKeybind(): KeyMapping = registeredWarpKey

        private fun formatDuration(seconds: Long): String {
            val safe = max(0L, seconds)
            return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
        }

        private fun resetSession() {
            val module = INSTANCE
            module.stats.resetSession(System.nanoTime())
            module.persistTotalsNow()
            module.diagnosticEvent("SESSION_RESET")
        }

        private fun height(lines: Array<String>, font: Font): Int = lines.size * (font.lineHeight + 1) + 8

        private fun readCurrentPlotName(mc: Minecraft): Optional<String> {
            val level = mc.level ?: return Optional.empty()
            val player = mc.player ?: return Optional.empty()
            val scoreboard = level.scoreboard
            val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
            if (sidebar != null) {
                val text = StringBuilder(ChatText.plain(sidebar.displayName.string))
                for (entry in scoreboard.listPlayerScores(sidebar)) {
                    if (entry == null || entry.isHidden()) continue
                    var line = entry.display()
                    if (line == null) {
                        val team = scoreboard.getPlayersTeam(entry.owner())
                        line = team?.getFormattedName(entry.ownerName()) ?: entry.ownerName()
                    }
                    text.append('\n').append(ChatText.plain(line.getString()))
                }
                val scoreName = GardenCurrentPlotParser.parse(text.toString())
                if (scoreName.isPresent) return scoreName
            }
            return GardenPlotGrid.plotAt(player.x, player.y, player.z).map { plot -> "Plot ${plot.id}" }
        }
    }

    private class Settings {
        val totalMode = BooleanSetting("Show Totals", false)
        val showBackground = BooleanSetting("Background", true)
        val filterByTool = BooleanSetting("Filter HUD by Held Tool", true)
        val farmingToolFilter = BooleanSetting("Farming Tools", true)
        val vacuumFilter = BooleanSetting("Pest Vacuum", true)
        val lassoFilter = BooleanSetting("Pest Lasso", false)
        val excludeAfk = BooleanSetting("Exclude AFK Gaps", true)
        val afkGapLimitSeconds = NumberSetting("AFK Gap Limit (s)", 5f, MAX_AFK_GAP_SECONDS.toFloat(), 30f, true)
        val useCustomCooldown = BooleanSetting("Use Custom Cooldown", false)
        val customCooldownSeconds = NumberSetting("Custom Cooldown (s)", 30f, 600f, 135f, true)
        val cooldownWarningLeadSeconds = NumberSetting("Cooldown Warning Lead (s)", 1f, 30f, 5f, true)
        val finnegan = BooleanSetting("Finnegan Perk", false)
        val spawnAlert = BooleanSetting("Pest Spawn Alert", false)
        val cooldownWarningAlert = BooleanSetting("Cooldown Warning Alert", false)
        val cooldownReadyAlert = BooleanSetting("Cooldown Ready Alert", false)
        val alertChat = BooleanSetting("Chat Alert", true)
        val alertTitle = BooleanSetting("Title Alert", false)
        val alertSound = BooleanSetting("Sound Alert", false)
        val resetSessionAction = ModuleAction(
            "Reset Session Stats",
            Runnable { PestTrackerModule.resetSession() },
        )
        val warpToInfestedAction = ModuleAction(
            "Warp to Latest Infested Plot",
            Runnable { INSTANCE.warpToLatestInfestedPlot() },
        )
        /** Not persisted: the vanilla key mapping stays the source of truth. */
        val warpKey = KeyMappingMirror(registeredWarpKey, "Warp Key")
        val allSettings: List<Setting> = listOf(
            totalMode,
            showBackground,
            filterByTool,
            farmingToolFilter,
            vacuumFilter,
            lassoFilter,
            excludeAfk,
            afkGapLimitSeconds,
            useCustomCooldown,
            customCooldownSeconds,
            cooldownWarningLeadSeconds,
            finnegan,
            spawnAlert,
            cooldownWarningAlert,
            cooldownReadyAlert,
            alertChat,
            alertTitle,
            alertSound,
            resetSessionAction,
            warpToInfestedAction,
        )
    }
}
