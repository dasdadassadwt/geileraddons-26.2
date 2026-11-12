package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.utility.CountdownTimerState
import geiler.addons.client.utility.TimerCompletionPresentation
import geiler.addons.client.utility.TimerDurationParser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Runtime countdown service; player-facing settings live under General → Timer Settings. */
class UtilityTimerModule private constructor() : HudElement {
    companion object {
        @JvmField
        val INSTANCE = UtilityTimerModule()

        private const val PREVIEW_LINE = "Timer 42s (running)"
        private const val DIAGNOSTIC_MODULE = "Timer"
        private const val MAX_DIAGNOSTIC_DETAILS_PER_WINDOW = 8
        private const val MAX_DIAGNOSTIC_SUPPRESSED = 1_000_000L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512
        private const val DIAGNOSTIC_DETAIL_WINDOW_NANOS = 50_000_000L
        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val HUD_COLOR = 0xFFFFD166.toInt()
    }

    private val timer = CountdownTimerState()
    private var diagnosticTick = 0L
    private var diagnosticWindowStartNanos = Long.MIN_VALUE
    private var diagnosticDetailsThisWindow = 0
    private var suppressedDiagnosticDetails = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var lastStatusLine: String? = null

    fun state(): CountdownTimerState = timer

    fun setTimer(seconds: Long) {
        timer.setSeconds(seconds, System.nanoTime())
        syncDiagnosticOwnership()
        lastStatusLine = null
        recordDetail("SET duration_s=$seconds")
    }

    fun pauseTimer(): Boolean {
        val changed = timer.pause(System.nanoTime())
        if (timer.isConfigured()) {
            recordDetail("PAUSE changed=$changed remaining_s=${timer.remainingSeconds(System.nanoTime())}")
        }
        return changed
    }

    fun resumeTimer(): Boolean {
        val changed = timer.resume(System.nanoTime())
        if (timer.isConfigured()) {
            recordDetail("RESUME changed=$changed remaining_s=${timer.remainingSeconds(System.nanoTime())}")
        }
        return changed
    }

    fun resetTimer(): Boolean {
        val changed = timer.reset()
        if (changed) {
            lastStatusLine = null
            recordDetail("RESET duration_s=${timer.initialDurationSeconds()}")
        }
        return changed
    }

    /** Called from the client tick. The state uses nanoTime, so pauses and lag do not skew it. */
    fun tick() {
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        syncDiagnosticOwnership()
        val now = System.nanoTime()
        rollDiagnosticWindow(now)
        if (timer.tick(now)) completeTimer()
        maybeWriteStatus(now)
    }

    override fun id(): String = "utility_timer"

    override fun displayName(): String = "Timer"

    override fun width(font: Font?): Int = font!!.width(line())

    override fun previewWidth(font: Font?): Int = font!!.width(previewLine())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun visible(): Boolean = GeneralModule.INSTANCE.showTimerHud().value() && timer.isConfigured()

    override fun previewMode(): String = if (timer.isConfigured()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, line(), x, y, HUD_COLOR)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, previewLine(), x, y, HUD_COLOR)
    }

    private fun completeTimer() {
        val settings = GeneralModule.INSTANCE
        val presentation = TimerCompletionPresentation.resolve(
            settings.timerCompletionAlert().value(),
            settings.timerCompletionChat().value(),
            settings.timerCompletionTitle().value(),
            settings.timerCompletionSound().value(),
            settings.timerCompletionText().value(),
            settings.timerTitleDurationTicks().intValue(),
        )
        val minecraft = Minecraft.getInstance()
        val gui = minecraft.gui
        var chatSubmitted = false
        var titleSubmitted = false
        var soundSubmitted = false
        if (gui != null) {
            if (presentation.showChat) {
                gui.hud.getChat().addClientSystemMessage(Component.literal("[Timer] ${presentation.message}"))
                chatSubmitted = true
            }
            if (presentation.showTitle) {
                gui.hud.setTimes(0, presentation.titleDurationTicks, 0)
                gui.hud.setTitle(Component.literal(presentation.message))
                titleSubmitted = true
            }
        }
        if (presentation.playSound) {
            minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f),
            )
            soundSubmitted = true
        }

        // The configured completion text is intentionally absent from diagnostics.
        recordDetail(
            "COMPLETE duration_s=${timer.initialDurationSeconds()} " +
            "chat=${presentation.showChat} chat_submitted=$chatSubmitted " +
                "title=${presentation.showTitle} title_submitted=$titleSubmitted " +
                "title_ticks=${presentation.titleDurationTicks} " +
                "sound=${presentation.playSound} sound_submitted=$soundSubmitted",
        )
    }

    private fun previewLine(): String = if (timer.isConfigured()) line() else PREVIEW_LINE

    private fun line(): String {
        val now = System.nanoTime()
        val state = when {
            timer.isRunning() -> "running"
            timer.isComplete() -> "complete"
            else -> "paused"
        }
        return "Timer ${TimerDurationParser.formatSeconds(timer.remainingSeconds(now))} ($state)"
    }

    private fun syncDiagnosticOwnership() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(
            Category.MISCELLANEOUS,
            DIAGNOSTIC_MODULE,
            timer.isConfigured(),
        )
    }

    private fun maybeWriteStatus(now: Long) {
        if (!timer.isConfigured()) {
            lastStatusLine = null
            lastStatusTick = Long.MIN_VALUE
            return
        }

        val lastTick = lastStatusTick
        val elapsedTicks = if (lastTick == Long.MIN_VALUE) Long.MAX_VALUE else diagnosticTick - lastTick
        if (elapsedTicks < DIAGNOSTIC_STATUS_INTERVAL_TICKS) return

        val settings = GeneralModule.INSTANCE
        val line = buildString {
            append("STATUS configured=true")
            append(" running=").append(timer.isRunning())
            append(" complete=").append(timer.isComplete())
            append(" initial_s=").append(timer.initialDurationSeconds())
            append(" remaining_s=").append(timer.remainingSeconds(now))
            append(" hud=").append(settings.showTimerHud().value())
            append(" alert=").append(settings.timerCompletionAlert().value())
            append(" chat=").append(settings.timerCompletionChat().value())
            append(" title=").append(settings.timerCompletionTitle().value())
            append(" sound=").append(settings.timerCompletionSound().value())
        }
        if (line != lastStatusLine || elapsedTicks >= DIAGNOSTIC_HEARTBEAT_TICKS) {
            writeDiagnostic(line)
            lastStatusLine = line
            lastStatusTick = diagnosticTick
        }
    }

    private fun recordDetail(line: String) {
        if (!timer.isConfigured()) return
        rollDiagnosticWindow(System.nanoTime())
        if (diagnosticDetailsThisWindow >= MAX_DIAGNOSTIC_DETAILS_PER_WINDOW) {
            if (suppressedDiagnosticDetails < MAX_DIAGNOSTIC_SUPPRESSED) suppressedDiagnosticDetails++
            return
        }
        writeDiagnostic(line)
        diagnosticDetailsThisWindow++
    }

    private fun rollDiagnosticWindow(nowNanos: Long) {
        val start = diagnosticWindowStartNanos
        if (start != Long.MIN_VALUE && nowNanos - start < DIAGNOSTIC_DETAIL_WINDOW_NANOS) return

        diagnosticWindowStartNanos = nowNanos
        diagnosticDetailsThisWindow = 0
        if (suppressedDiagnosticDetails > 0L && timer.isConfigured()) {
            writeDiagnostic("SUPPRESSED detail_events=$suppressedDiagnosticDetails")
            suppressedDiagnosticDetails = 0L
            diagnosticDetailsThisWindow++
        }
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            DIAGNOSTIC_MODULE,
            diagnosticTick,
            line.replace('\r', ' ').replace('\n', ' ').take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }
}
