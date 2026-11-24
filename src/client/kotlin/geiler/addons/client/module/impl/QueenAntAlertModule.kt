package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.hunting.QueenAnt
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.util.UUID

/**
 * Calls out a Queen Ant in Torrhus Canyon (2.5% per Ant when an Anthill breaks in Ant's Cave): title, sound, an
 * optional party line with your coordinates, and a box on the queen. Found by its name tag, like Sparkling Critter.
 */
class QueenAntAlertModule private constructor(private val s: Settings) : Module(
    "Queen Ant Alert",
    "Alerts and highlights a Queen Ant in Torrhus Canyon, with an optional party callout.",
    Category.HUNTING,
    s.title, s.sound, s.party, s.highlight, s.tracer, s.color,
) {
    class Settings {
        val title = BooleanSetting("Title", true)
        val sound = BooleanSetting("Sound", true)
        val party = BooleanSetting("Party Message", false)
        val highlight = BooleanSetting("Highlight", true)
        val tracer = BooleanSetting("Tracer", true)
        val color = ColorSetting("Color", 255, 85, 85, 255)
    }

    private val tracker = LabeledMobTracker { name -> if (QueenAnt.matches(name.string)) true else null }
    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 480)
    private val alerted = HashSet<UUID>()
    private var lastAlertMs = 0L
    private var lastLevel: ClientLevel? = null
    private var ticks = 0
    private var alerts = 0
    private var partySent = 0

    init {
        group(SettingGroup("Alert", s.title, s.sound, s.party), SettingGroup("Highlight", s.highlight, s.tracer, s.color))
    }

    override fun isActive(): Boolean = isEnabled() && HypixelModApi.currentIsland() == Island.TORRHUS_CANYON
    override fun inactiveReason(): String? =
        if (isEnabled() && !isActive()) HypixelModApi.reasonNotOn(Island.TORRHUS_CANYON) else null

    override fun onEnable() {
        alerts = 0
        partySent = 0
        diagnostics.enable("title=${s.title.value()} sound=${s.sound.value()} party=${s.party.value()} highlight=${s.highlight.value()}")
    }

    override fun onDisable() {
        tracker.clear()
        alerted.clear()
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
            alerted.clear()
        }
        if (isActive() && level != null && player != null) {
            tracker.prune()
            if (++ticks >= SCAN_TICKS) {
                ticks = 0
                tracker.scan(level, player, MAX_RANGE)
                for (sighting in tracker.sightings) {
                    if (!alerted.add(sighting.body.uuid)) continue
                    alert(mc, player.blockPosition().x, player.blockPosition().y, player.blockPosition().z)
                }
            }
        }
        diagnostics.statusOnChangeBy(20L, { "${isActive()}|${tracker.sightings.size}" }) {
            "active=${isActive()} sightings=${tracker.sightings.size} scan=${tracker.lastScan} alerts=$alerts party=$partySent"
        }
    }

    private fun alert(mc: Minecraft, x: Int, y: Int, z: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < COOLDOWN_MS) {
            diagnostics.event("ALERT skipped=cooldown")
            return
        }
        lastAlertMs = now
        alerts++
        if (s.title.value()) TitleAlertStack.submit(name(), "§c§lQUEEN ANT!", 60, 0xFFFF5555.toInt())
        if (s.sound.value()) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 1.0f))
        if (s.party.value()) {
            mc.player?.connection?.sendCommand("pc ${QueenAnt.partyMessage(x, y, z)}")
            partySent++
        }
        diagnostics.event("ALERT title=${s.title.value()} sound=${s.sound.value()} party=${s.party.value()}")
    }

    fun render(context: LevelRenderContext) {
        if (isActive() && s.highlight.value()) tracker.render(context, s.tracer.value()) { s.color.argb() }
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (isActive() && s.highlight.value()) tracker.renderLabels(graphics, { QueenAnt.NAME }) { s.color.argb() }
    }

    companion object {
        @JvmField
        val INSTANCE = QueenAntAlertModule(Settings())

        private const val SCAN_TICKS = 5
        private const val MAX_RANGE = 96.0
        private const val COOLDOWN_MS = 30_000L
    }
}
