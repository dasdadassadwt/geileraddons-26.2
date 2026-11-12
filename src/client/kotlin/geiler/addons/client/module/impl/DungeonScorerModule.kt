package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Score milestone alerts for dungeon runs. */
class DungeonScorerModule private constructor() : Module(
    "Score Titles",
    "Shows a brief title when the dungeon score reaches configured milestones.",
    Category.F7,
    false,
    alerts,
    firstThreshold,
    secondThreshold,
    alertText,
    durationTicks,
    playSound,
    soundId,
    paulBonus,
), ModulePreview {
    private var notice: String? = null
    private var noticeUntilTick = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 16, 100L, 1024)

    init {
        group(*spec.groups.toTypedArray())
    }

    /** Keep the former module key so existing profiles retain their enable state and settings. */
    override fun configName(): String = "Scorer"

    fun showAlert(text: String?, untilTick: Long) {
        notice = text
        noticeUntilTick = Math.max(0L, untilTick)
        TitleAlertStack.submit("score.titles", text, durationTicks.intValue(), ALERT_COLOR)
        diagnostics.event(
            "TITLE result=submitted,durationTicks=${durationTicks.intValue()},text=${bounded(text)}",
        )
    }

    fun recordSoundOutcome(clientTick: Long, result: String?, configuredSoundId: String?) {
        diagnostics.event(
            "SOUND result=${bounded(result)},soundId=${bounded(configuredSoundId)}",
        )
    }

    override fun onEnable() {
        diagnostics.enable("alerts=${alerts.value()},firstThreshold=${firstThreshold.intValue()}")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    fun tick(clientTick: Long) {
        if (isEnabled()) {
            diagnostics.advance()
            diagnostics.status { suppressed ->
                "alerts=${alerts.value()},firstThreshold=${firstThreshold.intValue()}" +
                    ",secondThreshold=${secondThreshold.intValue()},durationTicks=${durationTicks.intValue()}" +
                    ",playSound=${playSound.value()},soundId=${bounded(soundId.value())}" +
                    ",paulBonus=${paulBonus.value()},suppressedEvents=$suppressed"
            }
        }
        if (clientTick > noticeUntilTick) notice = null
    }

    fun clearAlert() {
        notice = null
    }

    @Suppress("UNUSED_PARAMETER")
    override fun previewLines(font: Font, width: Int): List<Component> = listOf(
        Component.literal("Milestones: ${firstThreshold.intValue()} / ${secondThreshold.intValue()}"),
        Component.literal(previewNotice()),
    )

    @Suppress("UNUSED_PARAMETER")
    override fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        graphics.text(
            font,
            Component.literal("Milestones: ${firstThreshold.intValue()} / ${secondThreshold.intValue()}"),
            x,
            y,
            0xFFB8C0CC.toInt(),
        )
        graphics.text(font, Component.literal(previewNotice()), x, y + font.lineHeight + 5, ALERT_COLOR)
        return true
    }

    private fun previewNotice(): String {
        val template = alertText.value().trim().ifEmpty { "Score {score}" }
        return template.replace("{score}", firstThreshold.intValue().toString())
    }

    private fun bounded(value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val safe = value.replace('\r', ' ').replace('\n', ' ')
        return if (safe.length <= 96) safe else safe.substring(0, 93) + "..."
    }

    companion object {
        private const val ALERT_COLOR = -43691

        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        private val spec = ModuleSettingsSpec.of("Score Titles")

        @JvmField
        val alerts = spec.toggle("alerts")

        @JvmField
        val firstThreshold = spec.slider("firstThreshold")

        @JvmField
        val secondThreshold = spec.slider("secondThreshold")

        @JvmField
        val alertText = spec.text("alertText")

        @JvmField
        val durationTicks = spec.slider("durationTicks")

        @JvmField
        val playSound = spec.toggle("playSound")

        @JvmField
        val soundId = spec.text("soundId")

        @JvmField
        val paulBonus = spec.choice("paulBonus")

        @JvmField
        val INSTANCE = DungeonScorerModule()
    }
}
