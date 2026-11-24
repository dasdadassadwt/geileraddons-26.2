package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.misc.QuiverRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component

/** A movable HUD line with the arrows left in the quiver, read from the quiver item's lore (Odin's Quiver Display). */
class QuiverDisplayModule private constructor() : Module(
    "Quiver Display",
    "Shows how many arrows are left in your quiver.",
    Category.MISCELLANEOUS,
    false,
), HudElement {
    private var line: String? = null
    private var ticks = 0
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 240)

    override fun id(): String = "quiver_display"

    override fun displayName(): String = "Quiver Display"

    private fun text(): String = line ?: QuiverRules.text("Flint Arrow", 100)

    override fun width(font: Font?): Int = font!!.width(text())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun visible(): Boolean = isEnabled() && line != null

    override fun previewMode(): String = if (line != null) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    override fun onEnable() {
        ticks = 0
        diagnostics.enable("source=quiver item lore, every 10 ticks")
    }

    override fun onDisable() {
        line = null
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (++ticks % 10 != 0) return
        val player = Minecraft.getInstance().player
        var found: String? = null
        if (player != null) {
            val inventory = player.inventory
            // Hotbar first (the quiver sits in its last slot), then the rest of the inventory.
            for (slot in 0 until 36) {
                val stack = inventory.getItem(slot)
                if (stack.isEmpty || !QuiverRules.isArrowItem(BuiltInRegistries.ITEM.getKey(stack.item).toString())) continue
                val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: continue
                val count = QuiverRules.arrowsRemaining(lore) ?: continue
                found = QuiverRules.text(geiler.addons.client.utility.SkyblockItems.realName(stack), count)
                break
            }
        }
        if (found != line) diagnostics.event(if (found == null) "QUIVER none" else "QUIVER shown")
        line = found
        diagnostics.status { "enabled=true shown=${line != null} suppressedEvents=$it" }
    }

    companion object {
        @JvmField
        val INSTANCE = QuiverDisplayModule()
    }
}
