package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.misc.SpeedRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.ai.attributes.Attributes

/** A movable HUD line with the player's speed stat, read the way Devonian's Speed Display reads it. */
class SpeedDisplayModule private constructor() : Module(
    "Speed Display",
    "Shows your current speed stat.",
    Category.MISCELLANEOUS,
    false,
), HudElement {
    private var speed = 0
    private var live = false
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 240)

    override fun id(): String = "speed_display"

    override fun displayName(): String = "Speed Display"

    private fun text(): String = if (live) SpeedRules.text(speed) else SpeedRules.PREVIEW

    override fun width(font: Font?): Int = font!!.width(text())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun visible(): Boolean = isEnabled() && live

    override fun previewMode(): String = if (live) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    override fun onEnable() {
        diagnostics.enable("source=base-movement-speed")
    }

    override fun onDisable() {
        live = false
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val player = Minecraft.getInstance().player
        if (player == null) {
            live = false
            return
        }
        val value = SpeedRules.speed(player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED))
        if (value != speed || !live) diagnostics.event("SPEED $value")
        speed = value
        live = true
        diagnostics.status { "enabled=true speed=$speed suppressedEvents=$it" }
    }

    companion object {
        @JvmField
        val INSTANCE = SpeedDisplayModule()
    }
}
