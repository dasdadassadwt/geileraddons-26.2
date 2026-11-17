package geiler.addons.client.module.impl

import geiler.addons.client.fishing.FishingBobberRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items

/**
 * A movable "Bobber: 5.3s" line while a fishing rod is held and the bobber is out, after SkyHanni's
 * Fishing Bobber Timer. The clock starts at the cast, or when the bobber first touches liquid.
 */
class FishingBobberTimerModule private constructor(settings: Settings) : Module(
    "Fishing Bobber Timer",
    "Shows how long your fishing bobber has been out.",
    Category.FISHING,
    false,
    settings.startOnLiquidTouch,
), HudElement {
    private val startOnLiquidTouch = settings.startOnLiquidTouch
    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 240)

    private var deployNanos = FishingBobberRules.INACTIVE
    private var bobberPresent = false
    private var holdingRod = false
    private var inLiquid = false

    override fun id(): String = "fishing_bobber_timer"

    override fun displayName(): String = "Fishing Bobber Timer"

    override fun width(font: Font?): Int = font!!.width(Component.literal(FishingBobberRules.text(0L)))

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun visible(): Boolean = isEnabled() && running()

    override fun previewMode(): String = if (running()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(FishingBobberRules.text(elapsed())), x, y, WHITE)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val shown = if (running()) elapsed() else SAMPLE_NANOS
        graphics.text(font, Component.literal(FishingBobberRules.text(shown)), x, y, WHITE)
    }

    override fun onEnable() {
        reset()
        diagnostics.enable("startOnLiquidTouch=${startOnLiquidTouch.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val player = Minecraft.getInstance().player
        val hook = if (SkyblockItems.inSkyblock()) player?.fishing else null
        val present = hook != null && !hook.isRemoved
        holdingRod = player != null && player.mainHandItem.`is`(Items.FISHING_ROD)
        inLiquid = present && (hook!!.isInWater || hook.isInLava)

        val before = deployNanos
        deployNanos = FishingBobberRules.step(
            deployNanos, bobberPresent, present, inLiquid, startOnLiquidTouch.value(), System.nanoTime(),
        )
        if (before == FishingBobberRules.INACTIVE && deployNanos != FishingBobberRules.INACTIVE) {
            diagnostics.event("START liquidTouch=${startOnLiquidTouch.value()} inLiquid=$inLiquid")
        } else if (before != FishingBobberRules.INACTIVE && deployNanos == FishingBobberRules.INACTIVE) {
            diagnostics.event("RESET afterMs=${(System.nanoTime() - before) / 1_000_000L} bobberGone=${!present}")
        }
        bobberPresent = present
        diagnostics.status {
            "enabled=true bobber=$bobberPresent holdingRod=$holdingRod inLiquid=$inLiquid " +
                "running=${running()} startOnLiquidTouch=${startOnLiquidTouch.value()} suppressedEvents=$it"
        }
    }

    private fun running(): Boolean = deployNanos != FishingBobberRules.INACTIVE && bobberPresent && holdingRod

    private fun elapsed(): Long =
        if (deployNanos == FishingBobberRules.INACTIVE) 0L else System.nanoTime() - deployNanos

    private fun reset() {
        deployNanos = FishingBobberRules.INACTIVE
        bobberPresent = false
        holdingRod = false
        inLiquid = false
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Fishing Bobber Timer")
        val startOnLiquidTouch = spec.toggle("startOnLiquidTouch")
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SAMPLE_NANOS = 5_300_000_000L

        @JvmField
        val INSTANCE = FishingBobberTimerModule(Settings())
    }
}
