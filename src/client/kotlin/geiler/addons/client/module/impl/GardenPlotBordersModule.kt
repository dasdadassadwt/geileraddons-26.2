package geiler.addons.client.module.impl

import geiler.addons.client.config.ModConfig
import geiler.addons.client.farming.GardenPlotGrid
import geiler.addons.client.farming.GardenPlotScoreboardParser
import geiler.addons.client.farming.GardenPlotState
import geiler.addons.client.farming.GardenPlotState.PlotStatus
import geiler.addons.client.farming.PestSpawnChatParser
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.GardenPlotBorderRenderer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.ArrayList
import java.util.HashMap
import java.util.Objects
import java.util.Optional
import java.util.OptionalInt

/** Draws status-aware Garden plot borders from client-visible pest observations. */
class GardenPlotBordersModule private constructor(private val settings: Settings) : Module(
    "Infested plot",
    "Finds exact infested Garden plots and draws labeled 3D borders.",
    Category.FARMING,
    settings.showClear,
    settings.showUnknown,
    settings.showLabels,
    settings.depthCheck,
    settings.lineWidth,
    settings.borderY,
    settings.wallHeight,
    settings.captureHeight,
    settings.labelScale,
    settings.staleAfterSeconds,
    settings.infestedColor,
    settings.clearColor,
    settings.unknownColor,
    settings.fillEnabled,
    settings.fillColor,
) {
    companion object {
        @JvmField
        val INSTANCE = GardenPlotBordersModule(Settings())

        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** How often the tab list is re-read. Identical reads refresh evidence without replacing newer plot evidence. */
        private const val WIDGET_READ_INTERVAL_TICKS = 10
        private const val SCOREBOARD_READ_INTERVAL_TICKS = 10

        /** Roughly two seconds of unconfirmed context before the session is treated as over. */
        private const val CONTEXT_GRACE_TICKS = 40

        /** How long the island-detection notice stays quiet before it may say the same thing again. */
        private const val ISLAND_NOTICE_COOLDOWN_TICKS = 1_200

        /** The config row's own name, so the chat line points at something the player can find. */
        private const val ISLAND_API_SETTING = "Island Detection API"

        /** Whether a height seen right now is a sound border floor rather than a fall or a jump. */
        @JvmStatic
        fun canCaptureHeight(gardenContext: Boolean, onGround: Boolean, y: Double, alreadyCaptured: Boolean): Boolean {
            if (!gardenContext || alreadyCaptured || !onGround || !java.lang.Double.isFinite(y)) return false
            return y >= GardenPlotGrid.MIN_BUILD_Y && y < GardenPlotGrid.MAX_BUILD_Y
        }

        /** Retains the original pure height-boundary check for existing offline coverage. */
        @JvmStatic
        fun canCaptureHeight(onGround: Boolean, y: Double, alreadyCaptured: Boolean): Boolean =
            canCaptureHeight(true, onGround, y, alreadyCaptured)

        private fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

        /** Returns only a fresh, known Garden status for client-side macro conditions. */
        @JvmStatic
        fun freshPlotStatus(plotId: Int): Optional<PlotStatus> {
            if (!INSTANCE.isActive() || !GardenPlotGrid.isValidId(plotId)) return Optional.empty()
            for (status in INSTANCE.renderSnapshot) {
                if (status.plotId == plotId && !status.stale &&
                    status.status != GardenPlotState.Status.UNKNOWN
                ) {
                    return Optional.of(status)
                }
            }
            return Optional.empty()
        }

        private fun runOnClientThread(action: Runnable) {
            val mc = Minecraft.getInstance()
            if (mc.isSameThread()) action.run() else mc.execute(action)
        }
    }

    private class Settings {
        val showClear = BooleanSetting("Show Clear Plots", false)
        val showUnknown = BooleanSetting("Show Unknown Plots", false)
        val showLabels = BooleanSetting("Show Labels", true)
        val depthCheck = BooleanSetting("Depth Check", true)
        val lineWidth = NumberSetting("Border Width", 0.5f, 5.0f, 4.410061f)
        val wallHeight = NumberSetting("Wall Height", 0.5f, 16.0f, 10.943598f)

        /** Fixed floor height keeps the border in place while the player moves. */
        val borderY = NumberSetting("Border Y", 0.0f, 320.0f, 72.0f, true)
        val captureHeight = ModuleAction(
            "Capture Current Y",
            "Store your current block height as the border's fixed world height.",
            Runnable { GardenPlotBordersModule.INSTANCE.captureHeight() },
        )
        val labelScale = NumberSetting("Label Scale", 0.55f, 2.0f, 1.4208841f)
        val staleAfterSeconds = NumberSetting("Data Max Age", 15.0f, 600.0f, 120.0f, true)
        val infestedColor = ColorSetting("Infested Colour", 255, 94, 80, 255)
        val clearColor = ColorSetting("Clear Colour", 65, 218, 167, 205)
        val unknownColor = ColorSetting("Unknown Colour", 249, 183, 75, 205)
        val fillEnabled = BooleanSetting("Fill Plots", false)
        val fillColor = ColorSetting("Fill Colour", 255, 94, 80, 51)
    }

    private val showClear = settings.showClear
    private val showUnknown = settings.showUnknown
    private val showLabels = settings.showLabels
    private val depthCheck = settings.depthCheck
    private val lineWidth = settings.lineWidth
    private val borderY = settings.borderY
    private val wallHeight = settings.wallHeight
    private val labelScale = settings.labelScale
    private val staleAfterSeconds = settings.staleAfterSeconds
    private val infestedColor = settings.infestedColor
    private val clearColor = settings.clearColor
    private val unknownColor = settings.unknownColor
    private val fillEnabled = settings.fillEnabled
    private val fillColor = settings.fillColor

    /** Session state prevents automatic height capture from writing a user preference. */
    private var heightCapturedThisSession = false

    private val state = GardenPlotState()
    private val widgetReader = PestWidgetReader()

    /** The tick hook publishes immutable status snapshots for render hooks. */
    @Volatile
    private var renderSnapshot: List<PlotStatus> = emptyList()

    private var lastWidgetSnapshot: Set<Int>? = null
    private var lastWidgetReadMillis = 0L
    private var widgetReadState = "unread"
    private var lastScoreboardPlotCount: GardenPlotScoreboardParser.PlotCount? = null
    private var lastScoreboardPlotReadMillis = 0L
    private var lastGardenTotal: Int? = null
    private var lastGardenTotalReadMillis = 0L
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, 100L, 1024)
    private var lastWorldRenderSummary = "not-rendered"
    private var lastLabelRenderSummary = "not-rendered"
    private var lastLevel: ClientLevel? = null
    private var wasActive = false
    private var ticksSinceWidgetRead = 0
    private var ticksSinceScoreboardRead = 0

    /** Consecutive ticks without confirmed Garden context; brief location-packet gaps are tolerated. */
    private var ticksWithoutContext = 0

    /** Ticks left before the island-detection notice may be repeated; zero means it may fire. */
    private var islandNoticeCooldown = 0

    init {
        group(
            SettingGroup("Display", settings.showClear, settings.showUnknown, settings.showLabels, settings.depthCheck),
            SettingGroup(
                "Border Style",
                settings.lineWidth,
                settings.wallHeight,
                settings.borderY,
                settings.captureHeight,
                settings.staleAfterSeconds,
            ),
            SettingGroup("Labels", settings.labelScale),
            SettingGroup("Status Colours", settings.infestedColor, settings.clearColor, settings.unknownColor),
            SettingGroup("Fill", settings.fillEnabled, settings.fillColor),
        )
    }

    override fun isActive(): Boolean {
        val mc = Minecraft.getInstance()
        return isEnabled() && mc.level != null && mc.player != null && HypixelModApi.onGarden()
    }

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return "Waiting for a loaded Garden world"
        return HypixelModApi.reasonNotOn(Island.GARDEN)
    }

    override fun onDisable() {
        diagnostics.disable()
        GardenPlotBorderRenderer.releaseExtendedProjection()
        runOnClientThread(Runnable { resetSession() })
    }

    override fun onEnable() {
        diagnostics.enable("evidence=ordered-pests-widget,plot-chat,current-plot-scoreboard")
    }

    /** Client-tick hook: detects world/session changes, reads status evidence, and publishes a snapshot. */
    fun tick() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread()) {
            mc.execute(Runnable { tick() })
            return
        }
        if (isEnabled()) diagnostics.advance()

        // The notice explains why the module is idle when island detection is disabled.
        notifyIfIslandDetectionIsOff(mc)
        if (!ensureGardenContext(true)) {
            sampleDiagnostics(false)
            return
        }

        captureHeightIfNeeded(mc)
        if (++ticksSinceWidgetRead >= WIDGET_READ_INTERVAL_TICKS) {
            ticksSinceWidgetRead = 0
            observePestsWidget(mc)
        }
        if (++ticksSinceScoreboardRead >= SCOREBOARD_READ_INTERVAL_TICKS) {
            ticksSinceScoreboardRead = 0
            observeScoreboard(mc)
        }
        publishSnapshot(nowMillis())
        sampleDiagnostics(true)
    }

    /** Takes the player's height once, the first time they are standing somewhere usable in the Garden. */
    private fun captureHeightIfNeeded(mc: Minecraft) {
        val player = mc.player ?: return
        if (heightCapturedThisSession) return
        if (!canCaptureHeight(HypixelModApi.onGarden(), player.onGround(), player.getY(), heightCapturedThisSession)) {
            return
        }
        heightCapturedThisSession = true
        borderY.setValue(kotlin.math.floor(player.getY()).toFloat())
        ModConfig.markDirty()
    }

    /** Stores the player's current block height as the border's fixed world Y. */
    fun captureHeight() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread()) {
            mc.execute(Runnable { captureHeight() })
            return
        }
        val player = mc.player ?: return
        if (mc.level == null || !HypixelModApi.onGarden()) return
        heightCapturedThisSession = true
        borderY.setValue(kotlin.math.floor(player.getY()).toFloat())
        ModConfig.markDirty()
    }

    /**
     * Authoritative Garden-wide source: the complete ordered Pests widget. An unchanged widget only
     * refreshes its own evidence, so it cannot repeatedly overwrite newer plot-specific observations.
     */
    private fun observePestsWidget(mc: Minecraft) {
        if (mc.level == null || mc.player == null) return
        val found = widgetReader.read()
        if (found.isEmpty()) {
            if (widgetReadState != "unavailable") {
                diagnostics.event("WIDGET_SNAPSHOT unavailable=incomplete-or-absent")
            }
            widgetReadState = "unavailable"
            return
        }
        val infested = found.get()
        val now = nowMillis()
        widgetReadState = "complete"
        lastWidgetReadMillis = now
        if (lastWidgetSnapshot == null || lastWidgetSnapshot != infested) {
            if (!state.observePestsWidgetSnapshot(infested, now)) return
            lastWidgetSnapshot = java.util.Set.copyOf(infested)
            diagnostics.event("WIDGET_SNAPSHOT complete=true infestedIds=${ids(infested)}")
        } else {
            state.refreshWidgetObservation(now)
        }
        publishSnapshot(now)
    }

    /** Reads scoreboard plot rows by their exact server ID; aggregate totals never select a plot. */
    private fun observeScoreboard(mc: Minecraft) {
        val level = mc.level ?: return
        if (mc.player == null) return
        val rows = scoreboardRows(level.scoreboard)
        val current = uniqueCurrentPlotCount(rows)
        if (current.isPresent) {
            val observation = current.get()
            val now = nowMillis()
            if (observation != lastScoreboardPlotCount) {
                if (state.observeCurrentPlotScoreboardCount(observation.plotId, observation.pestCount, now)) {
                    lastScoreboardPlotCount = observation
                    lastScoreboardPlotReadMillis = now
                    diagnostics.event(
                        "CURRENT_PLOT_SCOREBOARD plotId=${observation.plotId} pestCount=${observation.pestCount}",
                    )
                }
            } else {
                state.refreshCurrentPlotScoreboardCount(observation.plotId, observation.pestCount, now)
                lastScoreboardPlotReadMillis = now
            }
        }

        val total = uniqueGardenTotal(rows)
        if (total.isPresent) {
            val value = total.asInt
            if (lastGardenTotal == null || lastGardenTotal != value) {
                lastGardenTotal = value
                diagnostics.event("GARDEN_TOTAL diagnosticOnly=true pestCount=$value")
            }
            lastGardenTotalReadMillis = nowMillis()
        }
    }

    private fun scoreboardRows(scoreboard: Scoreboard): List<String> {
        val sidebar: Objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
        val rows = ArrayList<String>()
        sidebar.getDisplayName()?.let { rows.add(it.getString()) }
        val entries = ArrayList<PlayerScoreEntry>()
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            if (entry != null && !entry.isHidden()) entries.add(entry)
        }
        for (entry in entries) {
            var label = entry.display()
            if (label == null) {
                val team: PlayerTeam? = scoreboard.getPlayersTeam(entry.owner())
                label = if (team == null) entry.ownerName() else team.getFormattedName(entry.ownerName())
            }
            rows.add(label.getString())
        }
        return rows
    }

    private fun uniqueCurrentPlotCount(rows: List<String>): Optional<GardenPlotScoreboardParser.PlotCount> {
        var found: GardenPlotScoreboardParser.PlotCount? = null
        for (row in rows) {
            val parsed = GardenPlotScoreboardParser.parseCurrentPlotCount(row)
            if (parsed.isEmpty()) continue
            val value = parsed.get()
            if (found != null && found != value) return Optional.empty()
            found = value
        }
        return Optional.ofNullable(found)
    }

    private fun uniqueGardenTotal(rows: List<String>): OptionalInt {
        var found = OptionalInt.empty()
        for (row in rows) {
            val parsed = GardenPlotScoreboardParser.parseGardenTotal(row)
            if (parsed.isEmpty) continue
            if (found.isPresent && found.asInt != parsed.asInt) return OptionalInt.empty()
            found = parsed
        }
        return found
    }

    /** Raw inbound chat hook for exact plot spawn/clean messages and the explicit Garden-wide clear line. */
    fun onChatMessage(rawMessage: String?) {
        if (rawMessage == null) return
        runOnClientThread(Runnable {
            if (!ensureGardenContext()) return@Runnable
            val now = nowMillis()
            val maxAge = staleAfterSeconds.intValue().toLong() * MILLIS_PER_SECOND
            val spawn = PestSpawnChatParser.parseSpawn(rawMessage)
            if (spawn.isPresent) {
                val event = spawn.get()
                for (plotName in event.plotNames()) {
                    val plotId = exactPlotId(plotName)
                    if (plotId.isEmpty) continue
                    val id = plotId.asInt
                    val observed = state.observeSpawn(id, event.knownAmount(), now, maxAge)
                    diagnostics.event(
                        "SPAWN_CHAT accepted=$observed plotId=$id spawnedCount=" +
                            if (event.knownAmount() > 0) event.knownAmount().toString() else "unknown",
                    )
                }
                publishSnapshot(now)
                return@Runnable
            }

            val observed = state.observeChatMessage(rawMessage, now, maxAge)
            val cleanPlot = PestSpawnChatParser.parseCleanPlot(rawMessage)
            if (cleanPlot.isPresent) {
                val plotId = exactPlotId(cleanPlot.get())
                if (plotId.isPresent) {
                    diagnostics.event("CLEAN_CHAT accepted=$observed plotId=${plotId.asInt}")
                } else {
                    diagnostics.event("CLEAN_CHAT ignored=non-numeric-plot plotName=${cleanPlot.get()}")
                }
            } else if (observed) {
                diagnostics.event("PLOT_CHAT accepted=explicit-garden-clear")
            }
            if (observed) publishSnapshot(now)
        })
    }

    private fun exactPlotId(plotName: String): OptionalInt {
        if (!plotName.matches(Regex("\\d{1,2}"))) return OptionalInt.empty()
        return try {
            val id = plotName.toInt()
            if (GardenPlotGrid.isValidId(id)) OptionalInt.of(id) else OptionalInt.empty()
        } catch (_: NumberFormatException) {
            OptionalInt.empty()
        }
    }

    /** Optional explicit world-change hook; the tick hook also detects level identity changes. */
    fun onWorldChanged() {
        runOnClientThread(Runnable { resetSession() })
    }

    /** World-render hook. Call from the existing AFTER_TRANSLUCENT_FEATURES listener. */
    fun render(context: LevelRenderContext) {
        val mc = Minecraft.getInstance()
        if (!isEnabled()) {
            GardenPlotBorderRenderer.releaseExtendedProjection()
            lastWorldRenderSummary = "completed=false reason=disabled"
            return
        }
        if (!mc.isSameThread() || !ensureGardenContext()) {
            lastWorldRenderSummary = "completed=false reason=context-or-thread"
            return
        }
        val summary = GardenPlotBorderRenderer.renderWorld(
            context,
            renderSnapshot,
            borderY.value().toDouble(),
            wallHeight.value().toDouble(),
            lineWidth.value(),
            depthCheck.value(),
            showClear.value(),
            showUnknown.value(),
            fillEnabled.value(),
            fillColor.argb(),
            infestedColor.argb(),
            clearColor.argb(),
            unknownColor.argb(),
        )
        lastWorldRenderSummary = "completed=${summary.completed} inRange=${summary.inRangePlotCount}" +
            " submittedPlots=${summary.submittedPlotCount} infestedBorderIds=${summary.submittedInfestedPlotIds}" +
            " culled=${summary.culledPlotCount} invalid=${summary.invalidPlotCount}" +
            " segments=${summary.submittedSegmentCount} extendedProjection=${summary.extendedProjection}"
    }

    /** HUD-label hook. Register beside the other projected world-label renderers. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread() || !ensureGardenContext()) {
            lastLabelRenderSummary = "completed=false reason=context-or-thread"
            return
        }
        if (!showLabels.value()) {
            lastLabelRenderSummary = "completed=false reason=setting-disabled"
            return
        }
        val summary = GardenPlotBorderRenderer.renderLabels(
            graphics,
            renderSnapshot,
            borderY.value().toDouble(),
            wallHeight.value().toDouble(),
            labelScale.value(),
            infestedColor.argb(),
            clearColor.argb(),
            unknownColor.argb(),
        )
        lastLabelRenderSummary = "completed=${summary.completed} eligible=${summary.eligibleInfestedPlotCount}" +
            " submitted=${summary.submittedInfestedPlotIds} missingCount=${summary.missingPestCount}" +
            " distanceCulled=${summary.culledInfestedPlotCount} invalid=${summary.invalidInfestedPlotCount}" +
            " projectionSkipped=${summary.projectionSkippedCount}"
    }

    /**
     * Confirms the module is on, in the Garden, with a loaded world, and owns the session reset.
     * Render hooks do not advance the grace counter; only the client-tick hook does.
     */
    private fun ensureGardenContext(): Boolean = ensureGardenContext(false)

    private fun ensureGardenContext(advanceGraceTick: Boolean): Boolean {
        val mc = Minecraft.getInstance()
        val level: ClientLevel? = mc.level
        if (!isEnabled() || level == null || mc.player == null || !HypixelModApi.onGarden()) {
            if (advanceGraceTick && (wasActive || lastLevel != null) &&
                ++ticksWithoutContext >= CONTEXT_GRACE_TICKS
            ) {
                resetSession()
            }
            return false
        }
        ticksWithoutContext = 0
        if (lastLevel !== level) {
            state.reset()
            renderSnapshot = emptyList()
            resetSourceObservations()
            heightCapturedThisSession = false
            lastLevel = level
        }
        wasActive = true
        return true
    }

    /** Says, in chat, when the module cannot work because island detection is switched off. */
    private fun notifyIfIslandDetectionIsOff(mc: Minecraft) {
        if (!isEnabled() || mc.level == null || mc.player == null) {
            islandNoticeCooldown = 0
            return
        }
        if (ModConfig.hypixelModApi()) {
            islandNoticeCooldown = 0
            return
        }
        if (islandNoticeCooldown > 0) {
            islandNoticeCooldown--
            return
        }
        islandNoticeCooldown = ISLAND_NOTICE_COOLDOWN_TICKS
        val gui = mc.gui ?: return
        gui.hud.getChat().addClientSystemMessage(
            Component.literal(
                "[GeilerAddons] Infested plot needs the Island Detection API, which is off under " +
                    "Miscellaneous → General → $ISLAND_API_SETTING.",
            ),
        )
    }

    override fun configName(): String = "Garden Plot Borders"

    fun fillColor(): ColorSetting = fillColor

    private fun publishSnapshot(now: Long) {
        val maxAge = staleAfterSeconds.intValue().toLong() * MILLIS_PER_SECOND
        val next = state.snapshot(now, maxAge)
        val previous = renderSnapshot
        for (status in next) {
            val before = statusFor(previous, status.plotId)
            if (before == null || before.status != status.status ||
                !Objects.equals(before.pestCount, status.pestCount) ||
                before.stale != status.stale || before.source != status.source
            ) {
                val age = if (status.source == GardenPlotState.Source.NONE) {
                    -1L
                } else {
                    kotlin.math.max(0L, now - status.observedAtMillis)
                }
                diagnostics.event(
                    "PLOT_STATUS plotId=${status.plotId} status=${status.status}" +
                        " pestCount=${status.pestCount ?: "unknown"} source=${status.source}" +
                        " ageMs=${if (age < 0L) "none" else age} stale=${status.stale}",
                )
            }
        }
        renderSnapshot = next
    }

    private fun statusFor(snapshot: List<PlotStatus>, plotId: Int): PlotStatus? =
        snapshot.firstOrNull { it.plotId == plotId }

    private fun sampleDiagnostics(gardenActive: Boolean) {
        if (!isEnabled()) return
        diagnostics.statusOnChange(20L) { "${diagnosticStatus(gardenActive)} sampleTicks=20 heartbeatTicks=100" }
    }

    private fun diagnosticStatus(gardenActive: Boolean): String {
        val now = nowMillis()
        val maxAge = staleAfterSeconds.intValue().toLong() * MILLIS_PER_SECOND
        var infested = 0
        var clear = 0
        var unknown = 0
        var stale = 0
        val plotStates = ArrayList<String>(GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE)
        for (plot in renderSnapshot) {
            when (plot.status) {
                GardenPlotState.Status.INFESTED -> infested++
                GardenPlotState.Status.CLEAR -> clear++
                GardenPlotState.Status.UNKNOWN -> unknown++
                else -> unknown++
            }
            if (plot.stale) stale++
            plotStates.add(
                "${plot.plotId}:${plot.status}:${plot.pestCount ?: "?"}:${plot.source}:" +
                    freshness(plot, now, maxAge),
            )
        }
        val widget = "$widgetReadState:${ageBand(lastWidgetReadMillis, now, maxAge)}"
        val scoreboard = lastScoreboardPlotCount?.let {
            "${it.plotId}:${it.pestCount}:${ageBand(lastScoreboardPlotReadMillis, now, maxAge)}"
        } ?: "none"
        val total = lastGardenTotal?.let {
            "$it:diagnostic-only:${ageBand(lastGardenTotalReadMillis, now, maxAge)}"
        } ?: "none"
        return "enabled=${isEnabled()} garden=$gardenActive" +
            " widget=$widget widgetIds=${ids(lastWidgetSnapshot)}" +
            " currentPlotScoreboard=$scoreboard gardenTotal=$total" +
            " plots(infested=$infested,clear=$clear,unknown=$unknown,stale=$stale)" +
            " plotEvidence=$plotStates worldRender={$lastWorldRenderSummary}" +
            " labelRender={$lastLabelRenderSummary}" +
            " settings(clear=${showClear.value()},unknown=${showUnknown.value()}," +
            "labels=${showLabels.value()},depthCheck=${depthCheck.value()})"
    }

    private fun freshness(status: PlotStatus, now: Long, maxAge: Long): String {
        if (status.source == GardenPlotState.Source.NONE) return "none"
        if (status.stale) return "stale"
        return ageBand(status.observedAtMillis, now, maxAge)
    }

    private fun ageBand(observedAtMillis: Long, now: Long, maxAge: Long): String {
        if (observedAtMillis <= 0L) return "never"
        val age = kotlin.math.max(0L, now - observedAtMillis)
        if (age <= 5_000L) return "fresh"
        if (age <= maxAge) return "aging"
        return "stale"
    }

    private fun ids(values: Collection<Int?>?): String {
        if (values == null || values.isEmpty()) return "[]"
        val valid = values.filter { it != null && GardenPlotGrid.isValidId(it) }.filterNotNull().sorted()
        return valid.joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    private fun resetSession() {
        state.reset()
        renderSnapshot = emptyList()
        resetSourceObservations()
        heightCapturedThisSession = false
        lastLevel = null
        wasActive = false
        ticksSinceWidgetRead = 0
        ticksSinceScoreboardRead = 0
        ticksWithoutContext = 0
    }

    private fun resetSourceObservations() {
        lastWidgetSnapshot = null
        lastWidgetReadMillis = 0L
        widgetReadState = "unread"
        lastScoreboardPlotCount = null
        lastScoreboardPlotReadMillis = 0L
        lastGardenTotal = null
        lastGardenTotalReadMillis = 0L
        lastWorldRenderSummary = "not-rendered"
        lastLabelRenderSummary = "not-rendered"
    }
}
