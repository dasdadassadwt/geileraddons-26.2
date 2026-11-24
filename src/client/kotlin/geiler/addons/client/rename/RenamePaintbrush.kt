package geiler.addons.client.rename

import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.gui.PlayerInventorySlotScreenAccess
import geiler.addons.client.module.impl.AnyRenameModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The brush button in the top-right corner of the player-model area of the inventory. Click it, then click an item to open
 * the editor for that item (the cursor is on the button when you click it, so it arms a one-shot pick instead).
 */
object RenamePaintbrush {
    private const val SIZE = 12
    private const val MODEL_RIGHT = 75
    private const val MODEL_TOP = 9
    private var armed: InventoryScreen? = null

    private fun x(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getLeftPos`() + MODEL_RIGHT - SIZE - 1

    private fun y(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getTopPos`() + MODEL_TOP

    private fun visible(screen: AbstractContainerScreen<*>): Boolean {
        val module = AnyRenameModule.INSTANCE
        return screen is InventoryScreen && module.isEnabled() && module.brushEnabled()
    }

    @JvmStatic
    fun render(screen: InventoryScreen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!visible(screen)) return
        if (armed != null && Minecraft.getInstance().gui.screen() !== armed) armed = null
        val x = x(screen)
        val y = y(screen)
        val hovered = mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE
        val on = armed === screen
        graphics.fill(x, y, x + SIZE, y + SIZE, if (on) 0xFF6B4E00.toInt() else if (hovered) 0xFF3A3F55.toInt() else 0xFF22252F.toInt())
        graphics.outline(x, y, SIZE, SIZE, if (on) 0xFFFFD65A.toInt() else 0xFFC5C7D2.toInt())
        graphics.centeredText(Minecraft.getInstance().font, "✎", x + SIZE / 2, y + 2, 0xFFFFFFFF.toInt())
        if (hovered) {
            graphics.setTooltipForNextFrame(
                Minecraft.getInstance().font,
                Component.literal(if (on) "Click an item to edit it (click again to cancel)" else "Any Rename: click, then click an item"),
                mouseX,
                mouseY,
            )
        } else if (on) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, Component.literal("Click the item to rename"), mouseX, mouseY)
        }
    }

    /** Returns true when the click was consumed (the brush itself, or the item pick while armed). */
    @JvmStatic
    fun handleClick(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        if (screen !is InventoryScreen || !visible(screen)) return false
        val x = x(screen)
        val y = y(screen)
        val mx = event.x()
        val my = event.y()
        if (event.button() == 0 && mx >= x && mx < x + SIZE && my >= y && my < y + SIZE) {
            armed = if (armed === screen) null else screen
            return true
        }
        if (armed !== screen) return false
        armed = null
        if (event.button() != 0) return false
        val slot = (screen as PlayerInventorySlotScreenAccess).`geileraddons$hoveredSlot`()
        if (slot == null || slot.item.isEmpty) return false
        AnyRenameModule.INSTANCE.edit(screen, slot.item)
        return true
    }
}
