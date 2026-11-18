package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.terminal.AutoStep
import geiler.addons.client.dungeon.terminal.AutoTermsConfig
import geiler.addons.client.dungeon.terminal.AutoTermsEngine
import geiler.addons.client.dungeon.terminal.AutoTermsView
import geiler.addons.client.dungeon.terminal.TerminalType
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Solves the floor 7 terminals Terminal Solver shows, the way the person in the recordings solved them: a reaction delay, the
 * next slot chosen as they chose it, a cursor that travels to it and rests on it, and a click at a random spot inside the slot.
 * Terminal Solver owns the screen and the clicks; this module owns the settings and the diagnostics and feeds
 * [AutoTermsEngine] once per rendered frame. Defaults are from 34 recorded simulator runs (see [AutoTermsConfig]).
 */
class AutoTermsModule private constructor(private val s: Settings) : Module(
    "Auto Terms",
    "Solves the floor 7 terminals for you with human timing, slot choice and cursor movement. Needs Terminal Solver.",
    Category.F7,
    false,
    s.panes, s.rubix, s.numbers, s.startsWith, s.select, s.melody,
    s.firstClick, s.firstSpread, s.cursorStart, s.base, s.perBit, s.spread, s.minGap, s.dwell, s.melodyReaction, s.speed, s.timeScale,
    s.nearest, s.firstReading,
    s.moveCursor, s.landingSpread, s.landingY, s.overshoot, s.overshootSize, s.curve,
    s.corrections, s.accuracy, s.tremor, s.drift, s.shake, s.burstGap, s.finishTile,
    s.pauseOnManual, s.chat,
) {
    private val config = AutoTermsConfig()
    private val engine = AutoTermsEngine(config)
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 10, 100L, 320)
    private var type: TerminalType? = null
    private var autoClicks = 0
    private var terminals = 0
    private var pausedNoticed = false

    init {
        group(
            SettingGroup("Terminals", s.panes, s.rubix, s.numbers, s.startsWith, s.select, s.melody),
            SettingGroup("Reaction", s.firstClick, s.firstSpread, s.cursorStart, s.melodyReaction),
            SettingGroup("Click Timing", s.speed, s.base, s.perBit, s.spread, s.minGap, s.dwell, s.timeScale),
            SettingGroup("Target Choice", s.nearest, s.firstReading, s.finishTile, s.burstGap),
            SettingGroup(
                "Cursor", s.moveCursor, s.landingSpread, s.landingY, s.overshoot, s.overshootSize, s.curve, s.corrections, s.accuracy,
                s.tremor, s.drift, s.shake,
            ),
            SettingGroup("Safety", s.pauseOnManual, s.chat),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean = when (setting) {
        s.overshootSize -> s.moveCursor.value() && s.overshoot.value() > 0f
        s.accuracy -> s.moveCursor.value() && s.corrections.value() > 0f
        s.overshoot, s.curve, s.corrections, s.tremor, s.drift -> s.moveCursor.value()
        else -> true
    }

    override fun onEnable() {
        terminals = 0
        autoClicks = 0
        diagnostics.enable(
            "panes=${s.panes.value()} rubix=${s.rubix.value()} numbers=${s.numbers.value()} startsWith=${s.startsWith.value()} " +
                "select=${s.select.value()} melody=${s.melody.value()} first=${s.firstClick.intValue()}ms " +
                "gap=${s.base.intValue()}+${s.perBit.intValue()}/bit spread=${s.spread.value()} dwell=${s.dwell.intValue()}ms " +
                "nearest=${s.nearest.intValue()}% speed=${s.speed.value()} scale=${s.timeScale.intValue()}% moveCursor=${s.moveCursor.value()} " +
                "solverEnabled=${TerminalSolverModule.INSTANCE.isEnabled()}",
        )
        if (!TerminalSolverModule.INSTANCE.isEnabled()) say("Auto Terms needs Terminal Solver switched on.")
    }

    override fun onDisable() {
        engine.reset()
        type = null
        diagnostics.disable()
    }

    private fun say(text: String) {
        if (!s.chat.value()) return
        Minecraft.getInstance().gui?.hud?.chat?.addClientSystemMessage(Component.literal("[GeilerAddons] $text"))
    }

    /** True when Auto Terms plays [type]. */
    fun handles(type: TerminalType): Boolean = isEnabled() && when (type) {
        TerminalType.PANES -> s.panes.value()
        TerminalType.RUBIX -> s.rubix.value()
        TerminalType.NUMBERS -> s.numbers.value()
        TerminalType.STARTS_WITH -> s.startsWith.value()
        TerminalType.SELECT -> s.select.value()
        TerminalType.MELODY -> s.melody.value()
    }

    fun onTerminalOpened(opened: TerminalType) {
        if (!isEnabled()) return
        engine.reset()
        type = opened
        autoClicks = 0
        pausedNoticed = false
        diagnostics.event("OPEN type=$opened handled=${handles(opened)}")
    }

    fun onTerminalClosed() {
        val closed = type ?: return
        type = null
        if (!isEnabled()) return
        terminals++
        diagnostics.event("CLOSE type=$closed autoClicks=$autoClicks paused=${engine.isPaused}")
        engine.reset()
    }

    /** The person clicked in the terminal: Auto Terms stops for the rest of it. */
    fun manualInput() {
        if (!isEnabled() || type == null || !s.pauseOnManual.value() || engine.isPaused) return
        engine.pause()
        diagnostics.event("PAUSED manual click type=$type autoClicks=$autoClicks")
        if (!pausedNoticed) {
            pausedNoticed = true
            say("Auto Terms paused for this terminal after a manual click.")
        }
    }

    private fun syncConfig() {
        config.firstClickMs = s.firstClick.value().toDouble()
        config.firstClickSpread = s.firstSpread.value().toDouble()
        config.cursorStartMs = s.cursorStart.value().toDouble()
        config.baseMs = s.base.value().toDouble()
        config.perBitMs = s.perBit.value().toDouble()
        config.spread = s.spread.value().toDouble()
        config.minGapMs = s.minGap.value().toDouble()
        config.dwellMs = s.dwell.value().toDouble()
        config.melodyReactionMs = s.melodyReaction.value().toDouble()
        config.timeScale = presetFactor(s.speed.value()) * s.timeScale.value() / 100.0
        config.landingSpread = s.landingSpread.value().toDouble()
        config.landingY = s.landingY.value().toDouble()
        config.overshootChance = s.overshoot.value() / 100.0
        config.overshootSize = s.overshootSize.value().toDouble()
        config.pathCurve = s.curve.value().toDouble()
        config.correctionChance = s.corrections.value() / 100.0
        config.primaryAccuracy = s.accuracy.value().toDouble()
        config.tremorPx = s.tremor.value().toDouble()
        config.driftSlots = s.drift.value().toDouble()
        config.shakePx = s.shake.value().toDouble()
        config.burstMs = s.burstGap.value().toDouble()
        config.finishTileChance = s.finishTile.value() / 100.0
        config.moveCursor = s.moveCursor.value()
        config.nearestChance = s.nearest.value() / 100.0
        config.firstReadingChance = s.firstReading.value() / 100.0
    }

    /** One rendered frame of an open terminal: what to do now, or null when Auto Terms is not playing it. */
    fun step(view: AutoTermsView, nowNanos: Long): AutoStep? {
        if (!handles(view.type)) return null
        syncConfig()
        val step = engine.step(view, nowNanos)
        var event = engine.pollEvent()
        while (event != null) {
            diagnostics.event(event)
            event = engine.pollEvent()
        }
        if (step.clicks) {
            autoClicks++
            diagnostics.event("CLICK slot=${step.clickSlot} button=${step.clickButton} n=$autoClicks left=${view.remaining.size}")
        }
        diagnostics.status {
            "terminal=$type autoClicks=$autoClicks terminals=$terminals paused=${engine.isPaused} " +
                "remaining=${view.remaining.size} protected=${view.protectedNow} suppressedEvents=$it"
        }
        return step
    }

    /** Called once per client tick: drives the diagnostics clock. */
    fun tick() {
        if (isEnabled()) diagnostics.advance()
    }

    /** Every time is multiplied by this: Slow is the recorded speed, Fast 20% quicker, Very Fast 50% quicker. */
    private fun presetFactor(choice: String): Double = when (choice) {
        FAST -> 0.8
        VERY_FAST -> 0.5
        else -> 1.0
    }

    class Settings {
        val panes = BooleanSetting("Panes", true)
        val rubix = BooleanSetting("Rubix", true)
        val numbers = BooleanSetting("Numbers", true)
        val startsWith = BooleanSetting("Starts With", true)
        val select = BooleanSetting("Select All", true)
        val melody = BooleanSetting("Melody", false)

        val firstClick = NumberSetting("First Click Delay (ms)", 0f, 2000f, 450f, true)
        val firstSpread = NumberSetting("First Click Variation", 0f, 1f, 0.25f)
        val cursorStart = NumberSetting("Cursor Start Delay (ms)", 0f, 1000f, 160f, true)
        val melodyReaction = NumberSetting("Melody Reaction (ms)", 0f, 1000f, 220f, true)

        val base = NumberSetting("Click Time Base (ms)", 0f, 600f, 130f, true)
        val perBit = NumberSetting("Click Time Per Distance (ms)", 0f, 300f, 70f, true)
        val spread = NumberSetting("Click Time Variation", 0f, 1.5f, 0.45f)
        val minGap = NumberSetting("Minimum Click Gap (ms)", 0f, 500f, 60f, true)
        val dwell = NumberSetting("Rest On Slot (ms)", 0f, 250f, 55f, true)
        val speed = ChoiceSetting("Speed", SLOW, SLOW, FAST, VERY_FAST)
        val timeScale = NumberSetting("Speed Scale (%)", 25f, 400f, 100f, true)

        val nearest = NumberSetting("Nearest Slot Chance (%)", 0f, 100f, 70f, true)
        val firstReading = NumberSetting("First Slot In Reading Order (%)", 0f, 100f, 45f, true)
        val finishTile = NumberSetting("Finish Rubix Tile At Once (%)", 0f, 100f, 85f, true)
        val burstGap = NumberSetting("Repeat Click Gap (ms)", 20f, 300f, 85f, true)

        val moveCursor = BooleanSetting("Move Cursor", true)
        val landingSpread = NumberSetting("Landing Spread", 0f, 0.4f, 0.22f)
        val landingY = NumberSetting("Landing Height Bias", -0.4f, 0.4f, -0.07f)
        val overshoot = NumberSetting("Overshoot Chance (%)", 0f, 50f, 12f, true)
        val overshootSize = NumberSetting("Overshoot Size", 0.1f, 2f, 0.5f)
        val curve = NumberSetting("Path Curve", 0f, 0.3f, 0.06f)
        val corrections = NumberSetting("Correction Chance (%)", 0f, 100f, 55f, true)
        val accuracy = NumberSetting("Primary Miss Size", 0f, 0.3f, 0.06f)
        val tremor = NumberSetting("Tremor (px)", 0f, 3f, 0.4f)
        val drift = NumberSetting("Hover Drift", 0f, 0.15f, 0.03f)
        val shake = NumberSetting("Click Shake (px)", 0f, 8f, 1.5f)

        val pauseOnManual = BooleanSetting("Pause On Manual Click", true)
        val chat = BooleanSetting("Chat Messages", true)
    }

    companion object {
        const val SLOW = "Slow"
        const val FAST = "Fast"
        const val VERY_FAST = "Very Fast"

        @JvmField
        val INSTANCE = AutoTermsModule(Settings())
    }
}
