package geiler.addons.client.gui

import geiler.addons.client.inventory.PlayerInventoryControls
import geiler.addons.client.inventory.PlayerInventoryControlsData
import geiler.addons.client.module.impl.PlayerInventoryControlsModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

/** Bounded in-screen overlay for locked slots and current-profile slot links. */
object PlayerInventorySlotOverlay {
    private const val SLOT_SIZE = 16

    @JvmStatic
    fun render(screen: AbstractContainerScreen<*>?, graphics: GuiGraphicsExtractor) {
        // extractSlots runs inside extractContents' translate(leftPos, topPos), so slot.x/y are already screen-correct.
        val left = 0
        val top = 0
        val module = PlayerInventoryControlsModule.INSTANCE
        if (!module.isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (screen == null) return
        val inventory = player.inventory
        val slots = playerSlots(screen, inventory)
        val data = PlayerInventoryControls.INSTANCE.settings()!!

        for (link in data.slotLinks()) {
            val inventorySlot = slots[link.inventorySlot()] ?: continue
            val hotbarSlot = slots[link.hotbarSlot()] ?: continue
            val color = PlayerInventoryControls.INSTANCE.slotLinkColor(link.hotbarSlot())
            if (module.drawBoundOutline()) {
                drawBox(graphics, left + inventorySlot.x, top + inventorySlot.y, color, 1)
                drawBox(graphics, left + hotbarSlot.x, top + hotbarSlot.y, color, 1)
            }
            if (module.drawPointingLines()) {
                drawLine(
                    graphics,
                    left + inventorySlot.x + SLOT_SIZE / 2,
                    top + inventorySlot.y + SLOT_SIZE / 2,
                    left + hotbarSlot.x + SLOT_SIZE / 2,
                    top + hotbarSlot.y + SLOT_SIZE / 2,
                    color,
                    module.lineWidth(),
                )
            }
        }

        for (slotIndex in data.lockedSlots()) {
            val slot = slots[slotIndex] ?: continue
            val x = left + slot.x
            val y = top + slot.y
            drawBox(graphics, x, y, module.lockedSlotColor(), 2)
            if (module.lockedSlotUsesIcon()) drawLockIcon(graphics, x, y, module.lockedSlotColor())
        }
    }

    private fun playerSlots(screen: AbstractContainerScreen<*>, inventory: Inventory): Map<Int, Slot> {
        val slots = HashMap<Int, Slot>()
        for (slot in screen.menu.slots) {
            if (slot.container === inventory && slot.containerSlot >= 0 &&
                slot.containerSlot < PlayerInventoryControlsData.PLAYER_SLOT_COUNT
            ) {
                slots.putIfAbsent(slot.containerSlot, slot)
            }
        }
        return slots
    }

    private fun drawBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, color: Int, thickness: Int) {
        val t = maxOf(1, minOf(thickness, 3))
        graphics.fill(x, y, x + SLOT_SIZE, y + t, color)
        graphics.fill(x, y + SLOT_SIZE - t, x + SLOT_SIZE, y + SLOT_SIZE, color)
        graphics.fill(x, y, x + t, y + SLOT_SIZE, color)
        graphics.fill(x + SLOT_SIZE - t, y, x + SLOT_SIZE, y + SLOT_SIZE, color)
    }

    private fun drawLockIcon(graphics: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        // Compact pixel lock that stays readable over the item frame and has no texture dependency.
        val iconX = x + 5
        val iconY = y + 4
        graphics.fill(iconX + 1, iconY, iconX + 5, iconY + 1, color)
        graphics.fill(iconX, iconY + 1, iconX + 2, iconY + 4, color)
        graphics.fill(iconX + 4, iconY + 1, iconX + 6, iconY + 4, color)
        graphics.fill(iconX, iconY + 3, iconX + 6, iconY + 9, color)
        graphics.fill(iconX + 3, iconY + 5, iconX + 4, iconY + 7, 0xFF202020.toInt())
    }

    private fun drawLine(
        graphics: GuiGraphicsExtractor,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        color: Int,
        width: Int,
    ) {
        val dx = (endX - startX).toFloat()
        val dy = (endY - startY).toFloat()
        val length = hypot(dx, dy)
        if (length < 0.5f) return
        val size = maxOf(1, minOf(width, 6))
        // One rotated quad is rasterized at screen resolution; per-GUI-pixel stamps looked chunky at high GUI scales.
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(startX + 0.5f, startY + 0.5f)
        pose.rotate(atan2(dy, dx))
        pose.translate(-size / 2f, -size / 2f)
        graphics.fill(0, 0, ceil(length).toInt() + size, size, color)
        pose.popMatrix()
    }
}
