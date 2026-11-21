package geiler.addons.client.module.impl

import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.inventory.InventoryHudRules
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** A movable HUD copy of the player's inventory, in the Click GUI or the vanilla look, so it can be read without opening it. */
class InventoryHudModule private constructor() : Module(
    "Inventory HUD",
    "Shows the contents of your inventory on the HUD.",
    Category.VISUAL,
    false,
    style, opacity, showHotbar, behindVanilla, disableHotbar,
), HudElement {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 240)
    private var drawn = 0L
    private var behindDraws = 0L
    private var lastDx = 0
    private var lastDy = 0

    override fun id(): String = "inventory_hud"

    override fun displayName(): String = "Inventory HUD"

    override fun width(font: Font?): Int = InventoryHudRules.width()

    override fun height(font: Font?): Int = InventoryHudRules.height(hotbarShown())

    override fun rendersBehindVanillaHud(): Boolean = behindVanilla.value()

    private fun hotbarShown(): Boolean = InventoryHudRules.showsHotbar(showHotbar.value(), disableHotbar.value())

    /** True while the vanilla hotbar is replaced by this panel's hotbar row. */
    fun disableHotbarActive(): Boolean = isEnabled() && disableHotbar.value() && Minecraft.getInstance().player != null

    /** [dx, dy] for the vanilla status bars and action bar while Disable Hotbar is on; null when they stay put. */
    fun statusBarOffset(guiWidth: Int, guiHeight: Int): IntArray? {
        if (!disableHotbarActive()) return null
        val font = Minecraft.getInstance().font
        val offset = InventoryHudRules.statusBarOffset(
            guiWidth, guiHeight, HudManager.x(this, font, guiWidth), HudManager.y(this, font, guiHeight),
            HudManager.scaledWidth(this, font),
        )
        lastDx = offset[0]
        lastDy = offset[1]
        return offset
    }

    override fun visible(): Boolean = isEnabled() && Minecraft.getInstance().player != null

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun onEnable() {
        drawn = 0
        behindDraws = 0
        diagnostics.enable("style=${style.value()};opacity=${opacity.intValue()};hotbar=${showHotbar.value()};" +
            "behindVanilla=${behindVanilla.value()};disableHotbar=${disableHotbar.value()}")
    }

    override fun onDisable() = diagnostics.disable()

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "drawn=$drawn behind=${behindVanilla.value()} behindDraws=$behindDraws disableHotbar=${disableHotbar.value()} " +
                "barOffset=$lastDx,$lastDy suppressedEvents=$it"
        }
    }

    /** The live HUD stays out of the way while any screen is open (the real inventory, chat, menus). */
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (Minecraft.getInstance().gui.screen() != null) return
        draw(graphics, font, x, y)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y)

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val player = Minecraft.getInstance().player
        val mc = InventorySurfaces.isMinecraft(style.value())
        val fade = InventoryHudRules.opacity(opacity.value())
        val hotbar = hotbarShown()
        // The Click GUI panel's blue top line looks wrong over the world, so the HUD leaves it out.
        InventorySurfaces.panel(graphics, x, y, InventoryHudRules.width(), InventoryHudRules.height(hotbar), mc, fade, false)
        val rarity = ItemRarityBackgroundsModule.INSTANCE
        val rows = InventoryHudRules.rows(hotbar)
        for (row in 0 until rows) {
            for (column in 0 until InventoryHudRules.COLUMNS) {
                val cx = x + InventoryHudRules.cellX(column)
                val cy = y + InventoryHudRules.cellY(row)
                InventorySurfaces.slot(graphics, cx, cy, mc, fade)
                if (row == 3 && disableHotbar.value() && column == player?.inventory?.selectedSlot) {
                    graphics.outline(cx - 1, cy - 1, 18, 18, SELECTED_SLOT)
                }
                val stack = player?.inventory?.getItem(InventoryHudRules.slotIndex(row, column)) ?: continue
                if (stack.isEmpty) continue
                rarity.drawBackground(graphics, stack, cx, cy)
            }
        }
        // Items after every background, so a background never lands between two items.
        for (row in 0 until rows) {
            for (column in 0 until InventoryHudRules.COLUMNS) {
                val stack = player?.inventory?.getItem(InventoryHudRules.slotIndex(row, column)) ?: continue
                if (stack.isEmpty) continue
                val cx = x + InventoryHudRules.cellX(column)
                val cy = y + InventoryHudRules.cellY(row)
                graphics.item(stack, cx, cy)
                graphics.itemDecorations(font, stack, cx, cy)
            }
        }
        drawn++
        if (behindVanilla.value()) behindDraws++
    }

    companion object {
        private val style = InventorySurfaces.styleSetting()
        private val opacity = NumberSetting("Background Opacity", 0f, 100f, 70f, true)
        private val showHotbar = BooleanSetting("Show Hotbar", false)
        private val behindVanilla = BooleanSetting("Behind Vanilla HUD", false)
        private val disableHotbar = BooleanSetting("Disable Hotbar", false)
        private const val SELECTED_SLOT = 0xFFFFFFFF.toInt()

        @JvmField
        val INSTANCE = InventoryHudModule()
    }
}
