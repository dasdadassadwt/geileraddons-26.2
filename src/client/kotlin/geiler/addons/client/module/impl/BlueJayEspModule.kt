package geiler.addons.client.module.impl

import geiler.addons.client.hunting.BlueJay
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * Boxes Blue Jays in Torrhus Canyon, coloured by their name tag: red `/!\` (it saw you), yellow `/!\` (settling),
 * no mark (safe to lasso). The Blue Jay sits high in trees and flees when lassoed while alert.
 */
class BlueJayEspModule private constructor(private val s: Settings) : Module(
    "Blue Jay ESP",
    "Highlights Blue Jays in Torrhus Canyon and shows when one is safe to lasso.",
    Category.HUNTING,
    s.alert, s.almost, s.safe, s.tracer, s.labels, s.range,
) {
    class Settings {
        val alert = ColorSetting("Alert Color", 255, 85, 85, 255)
        val almost = ColorSetting("Settling Color", 255, 255, 85, 255)
        val safe = ColorSetting("Safe Color", 85, 255, 85, 255)
        val tracer = BooleanSetting("Tracer", true)
        val labels = BooleanSetting("Labels", true)
        val range = NumberSetting("Max Distance", 16f, 128f, 96f, true)
    }

    private val tracker = LabeledMobTracker { name -> BlueJay.state(name.string, markColor(name)) }
    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 480)
    private var lastLevel: ClientLevel? = null
    private var ticks = 0

    init {
        group(SettingGroup("Colors", s.alert, s.almost, s.safe), SettingGroup("Display", s.tracer, s.labels, s.range))
    }

    override fun isActive(): Boolean = isEnabled() && HypixelModApi.currentIsland() == Island.TORRHUS_CANYON
    override fun inactiveReason(): String? =
        if (isEnabled() && !isActive()) HypixelModApi.reasonNotOn(Island.TORRHUS_CANYON) else null

    override fun onEnable() = diagnostics.enable("tracer=${s.tracer.value()} labels=${s.labels.value()} range=${s.range.intValue()}")

    override fun onDisable() {
        tracker.clear()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level !== lastLevel) {
            lastLevel = level
            tracker.clear()
        }
        if (isActive() && level != null && player != null) {
            tracker.prune()
            // The alert mark changes within a second, so rescan often enough for the colour to keep up.
            if (++ticks >= SCAN_TICKS) {
                ticks = 0
                tracker.scan(level, player, s.range.value().toDouble())
            }
        }
        diagnostics.statusOnChangeBy(20L, { "${isActive()}|${summary()}" }) {
            "active=${isActive()} states=${summary()} scan=${tracker.lastScan}"
        }
    }

    private fun summary(): String = tracker.sightings.groupingBy { it.tag }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "-" }

    private fun color(state: BlueJay.State): Int = when (state) {
        BlueJay.State.ALERT -> s.alert.argb()
        BlueJay.State.ALMOST -> s.almost.argb()
        BlueJay.State.SAFE -> s.safe.argb()
    }

    fun render(context: LevelRenderContext) {
        if (isActive()) tracker.render(context, s.tracer.value(), ::color)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive() || !s.labels.value()) return
        tracker.renderLabels(graphics, { if (it.tag == BlueJay.State.SAFE) "Blue Jay - lasso now" else "Blue Jay - wait" }, ::color)
    }

    companion object {
        @JvmField
        val INSTANCE = BlueJayEspModule(Settings())

        private const val SCAN_TICKS = 5

        /** Colour of the part of [name] that holds the alert mark, or null. */
        private fun markColor(name: Component): Int? =
            name.visit(FormattedText.StyledContentConsumer<Int> { style: Style, text: String ->
                if (text.contains(BlueJay.ALERT_MARK)) Optional.ofNullable(style.color?.value) else Optional.empty()
            }, Style.EMPTY).orElse(null)
    }
}
