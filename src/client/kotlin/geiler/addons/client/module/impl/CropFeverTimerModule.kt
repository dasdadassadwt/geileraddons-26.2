package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.farming.CropFeverTimerState
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import java.util.Locale

/** Shows a configurable, previewable countdown for the 60-second Garden Crop Fever buff. */
class CropFeverTimerModule private constructor(settings: Settings) : Module(
    "Crop Fever Timer",
    "Counts down the remaining time on Crop Fever.",
    Category.FARMING,
    false,
    settings.showBackground,
    settings.textColor,
    settings.backgroundColor,
    settings.textScale,
), HudElement, ModulePreview {
    private val timer = CropFeverTimerState()
    private val showBackground = settings.showBackground
    private val textColor = settings.textColor
    private val backgroundColor = settings.backgroundColor
    private val textScale = settings.textScale
    private var lastLevel: ClientLevel? = null

    /** Updated by normal tick/chat handling so editor drawing never has to touch timer state. */
    private var previewText = PREVIEW_TEXT
    private var previewHasLiveData = false
    private var lastObservedTimerActive = false

    private var diagnosticTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var lastStatusSignature: String? = null
    private var diagnosticDetailsThisTick = 0
    private var suppressedDiagnosticDetails = 0
    private var callbacksSinceStatus = 0
    private var contextRejectedSinceStatus = 0
    private var unmatchedChatSinceStatus = 0
    private var matchedChatSinceStatus = 0

    init {
        group(SettingGroup("Appearance", showBackground, textColor, backgroundColor, textScale))
    }

    override fun isWorkInProgress(): Boolean = true

    override fun configName(): String = "Crop Fever Timer"

    override fun onEnable() {
        diagnosticTick = 0L
        lastStatusTick = Long.MIN_VALUE
        lastStatusSignature = null
        diagnosticDetailsThisTick = 0
        suppressedDiagnosticDetails = 0
        resetChatCounters()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), true)
        writeDiagnosticEvent("FEATURE ENABLED ${diagnosticSettings()}")
    }

    override fun onDisable() {
        writeDiagnosticEvent("FEATURE DISABLED lastDiagnosticTick=$diagnosticTick", allowDisabled = true)
        flushSuppressedDiagnostics()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), false)
    }

    fun tick() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }

        val diagnosticsEnabled = isEnabled()
        if (diagnosticsEnabled) advanceDiagnosticTick()

        val level = mc.level
        if (level !== lastLevel) {
            lastLevel = level
            timer.reset()
            previewText = PREVIEW_TEXT
            previewHasLiveData = false
            lastObservedTimerActive = false
            if (diagnosticsEnabled) {
                writeDiagnosticEvent("WORLD_CHANGED levelPresent=${level != null} timerReset=true")
            }
        }

        val now = System.nanoTime()
        val hasLevel = level != null
        val hasPlayer = mc.player != null
        val gardenChecked = hasLevel && hasPlayer
        val inGarden = gardenChecked && HypixelModApi.onGarden()
        val gate = when {
            !hasLevel -> "missing-level"
            !hasPlayer -> "missing-player"
            !inGarden -> "outside-garden"
            else -> "ready"
        }

        val remaining = if (hasLevel && hasPlayer && inGarden) {
            timer.remainingSeconds(now)
        } else {
            val wasActive = timer.active(now)
            timer.reset()
            if (diagnosticsEnabled && wasActive) {
                writeDiagnosticEvent("TIMER_RESET reason=$gate priorActive=true")
            }
            0.0
        }
        val timerActive = remaining > 0.0
        if (diagnosticsEnabled && lastObservedTimerActive && !timerActive && gate == "ready") {
            writeDiagnosticEvent("TIMER_EXPIRED reason=60-second-deadline")
        }
        lastObservedTimerActive = timerActive

        if (!timerActive) {
            previewText = PREVIEW_TEXT
            previewHasLiveData = false
        } else {
            previewHasLiveData = true
            previewText = line()
        }

        if (diagnosticsEnabled) {
            reportAutomaticStatus(
                hasLevel, hasPlayer, gardenChecked, inGarden, gate, timerActive, remaining,
            )
        }
    }

    /** Feed raw inbound chat from the existing client message hook. */
    fun onChatMessage(rawMessage: String) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(rawMessage) }
            return
        }
        if (!isEnabled()) return

        callbacksSinceStatus = incrementCounter(callbacksSinceStatus)
        if (mc.level == null || mc.player == null) {
            contextRejectedSinceStatus = incrementCounter(contextRejectedSinceStatus)
            return
        }
        if (!HypixelModApi.onGarden()) {
            contextRejectedSinceStatus = incrementCounter(contextRejectedSinceStatus)
            return
        }

        val now = System.nanoTime()
        val event = timer.onChatMessage(rawMessage, now)
        if (event == CropFeverTimerState.Event.NONE) {
            unmatchedChatSinceStatus = incrementCounter(unmatchedChatSinceStatus)
        } else {
            matchedChatSinceStatus = incrementCounter(matchedChatSinceStatus)
            lastObservedTimerActive = event == CropFeverTimerState.Event.STARTED
            val remaining = timer.remainingSeconds(now)
            writeDiagnosticEvent(
                "CHAT event=$event remainingSeconds=${formatSeconds(remaining)} messageChars=${rawMessage.length.coerceAtMost(MAX_DIAGNOSTIC_LINE_LENGTH)}",
            )
        }
        previewHasLiveData = timer.active(now)
        previewText = if (previewHasLiveData) line() else PREVIEW_TEXT
    }

    fun onWorldChanged() {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onWorldChanged() }
            return
        }
        timer.reset()
        lastLevel = null
        previewText = PREVIEW_TEXT
        previewHasLiveData = false
        lastObservedTimerActive = false
        if (isEnabled()) writeDiagnosticEvent("WORLD_CHANGED explicitHook=true timerReset=true")
    }

    override fun isActive(): Boolean {
        val mc = Minecraft.getInstance()
        return isEnabled() && mc.level != null && mc.player != null &&
            HypixelModApi.onGarden() && timer.active(System.nanoTime())
    }

    override fun id(): String = "crop_fever_timer"

    override fun displayName(): String = "Crop Fever Timer"

    override fun width(font: Font?): Int = scaledWidth(font!!, line())

    override fun previewWidth(font: Font?): Int = scaledWidth(font!!, previewText)

    override fun height(font: Font?): Int = Math.round(font!!.lineHeight * textScale.value()) + 8

    override fun visible(): Boolean = isActive()

    override fun previewMode(): String = if (previewHasLiveData) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawLine(graphics, font, x, y, line())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawLine(graphics, font, x, y, previewText)
    }

    /** Side-effect-free sample driven by the same appearance settings as the live timer. */
    override fun previewLines(font: Font, width: Int): List<Component> =
        listOf(
            Component.literal(PREVIEW_TEXT)
                .withStyle { style -> style.withColor(TextColor.fromRgb(textColor.opaqueArgb() and 0x00FFFFFF)) },
        )

    override fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val sampleWidth = scaledWidth(font, PREVIEW_TEXT)
        val sampleHeight = this.height(font)
        val drawX = x + (width - sampleWidth).coerceAtLeast(0) / 2
        val drawY = y + (height - sampleHeight).coerceAtLeast(0) / 2
        drawLine(graphics, font, drawX, drawY, PREVIEW_TEXT)
        return true
    }

    private fun drawLine(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, text: String) {
        val width = scaledWidth(font, text)
        val height = height(font)
        if (showBackground.value()) {
            GuiTheme.roundedRect(graphics, x, y, width, height, 3, backgroundColor.argb())
        }
        val scale = textScale.value()
        graphics.pose().pushMatrix()
        graphics.pose().translate((x + 4).toFloat(), (y + 4).toFloat())
        graphics.pose().scale(scale, scale)
        graphics.text(font, text, 0, 0, textColor.argb())
        graphics.pose().popMatrix()
    }

    private fun scaledWidth(font: Font, text: String): Int =
        Math.round(font.width(text) * textScale.value()) + 8

    private fun line(): String =
        "CROP FEVER ${String.format(Locale.ROOT, "%.2fs", timer.remainingSeconds(System.nanoTime()))}"

    private fun advanceDiagnosticTick() {
        diagnosticTick++
        diagnosticDetailsThisTick = 0
        flushSuppressedDiagnostics()
    }

    private fun reportAutomaticStatus(
        hasLevel: Boolean,
        hasPlayer: Boolean,
        gardenChecked: Boolean,
        inGarden: Boolean,
        gate: String,
        timerActive: Boolean,
        remainingSeconds: Double,
    ) {
        val signature = listOf(
            "gate=$gate",
            "level=$hasLevel",
            "player=$hasPlayer",
            "gardenChecked=$gardenChecked",
            "garden=$inGarden",
            "timerActive=$timerActive",
            "settings=${diagnosticSettings()}",
        ).joinToString("|")
        val sinceStatus = if (lastStatusTick == Long.MIN_VALUE) Long.MAX_VALUE else diagnosticTick - lastStatusTick
        val firstStatus = lastStatusTick == Long.MIN_VALUE
        val heartbeatDue = sinceStatus >= DIAGNOSTIC_STATUS_HEARTBEAT_TICKS
        val changedStatusDue = signature != lastStatusSignature && sinceStatus >= DIAGNOSTIC_STATUS_MIN_INTERVAL_TICKS
        if (!firstStatus && !heartbeatDue && !changedStatusDue) return

        val gardenState = if (gardenChecked) inGarden.toString() else "not-checked"
        val line = "STATUS gate=$gate level=$hasLevel player=$hasPlayer garden=$gardenState " +
            "timerActive=$timerActive remainingSeconds=${formatSeconds(remainingSeconds)} " +
            "preview=${if (previewHasLiveData) "live" else "sample"} " +
            "settings{${diagnosticSettings()}} " +
            "chat{callbacks=$callbacksSinceStatus;contextRejected=$contextRejectedSinceStatus;" +
            "unmatched=$unmatchedChatSinceStatus;matched=$matchedChatSinceStatus}"
        GeilerAddonsLog.write(Category.FARMING, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
        lastStatusTick = diagnosticTick
        lastStatusSignature = signature
        resetChatCounters()
    }

    private fun diagnosticSettings(): String =
        "background=${showBackground.value()};textColor=${textColor.hex()};" +
            "backgroundColor=${backgroundColor.hex()};textScale=${formatSeconds(textScale.value().toDouble())}"

    private fun writeDiagnosticEvent(line: String, allowDisabled: Boolean = false) {
        if (!allowDisabled && !isEnabled()) return
        if (diagnosticDetailsThisTick >= MAX_DIAGNOSTIC_DETAILS_PER_TICK) {
            suppressedDiagnosticDetails = incrementCounter(suppressedDiagnosticDetails)
            return
        }
        diagnosticDetailsThisTick++
        GeilerAddonsLog.write(Category.FARMING, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    private fun flushSuppressedDiagnostics() {
        if (suppressedDiagnosticDetails <= 0) return
        GeilerAddonsLog.write(
            Category.FARMING,
            name(),
            diagnosticTick,
            "EVENTS_SUPPRESSED count=$suppressedDiagnosticDetails",
        )
        suppressedDiagnosticDetails = 0
    }

    private fun resetChatCounters() {
        callbacksSinceStatus = 0
        contextRejectedSinceStatus = 0
        unmatchedChatSinceStatus = 0
        matchedChatSinceStatus = 0
    }

    private fun incrementCounter(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun formatSeconds(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private class Settings {
        val showBackground = BooleanSetting("Background", true)
        val textColor = ColorSetting("Text Color", 255, 85, 216, 255)
        val backgroundColor = ColorSetting("Background Color", 20, 16, 28, 190)
        val textScale = NumberSetting("Text Scale", 0.75f, 2.0f, 1.0f)
    }

    companion object {
        private const val PREVIEW_TEXT = "CROP FEVER 42.00s"
        private const val DIAGNOSTIC_STATUS_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_STATUS_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_DETAILS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 480

        @JvmField
        val INSTANCE = CropFeverTimerModule(Settings())
    }
}
