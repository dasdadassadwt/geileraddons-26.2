package geiler.addons.client.gui

import geiler.addons.client.module.impl.DungeonInvincibilityTimerModule
import geiler.addons.client.module.impl.HotmTreeHelperModule
import geiler.addons.client.module.impl.ItemCooldownsModule
import geiler.addons.client.module.impl.ItemRarityBackgroundsModule
import geiler.addons.client.module.impl.MiningPowderModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/** Per-slot overlays drawn around a slot's item in container screens and the hotbar; x and y are the item's top-left. */
object SlotItemOverlays {
    /**
     * Draws every container background in one burst before the first slot. Vanilla stacks a GUI element above the
     * highest element it overlaps, so a background between each slot's item would build one layer per slot.
     */
    @JvmStatic
    fun beforeSlots(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        HotmTreeHelperModule.INSTANCE.drawSlotHighlights(graphics, slots)
        geiler.addons.client.module.impl.HotfTreeHelperModule.INSTANCE.drawSlotHighlights(graphics, slots)
        geiler.addons.client.module.impl.MiningSolversModule.INSTANCE.drawFossilOverlay(graphics, slots)
        geiler.addons.client.module.impl.GreenhouseHelperModule.INSTANCE.drawSlotHighlights(graphics, slots)
        val module = ItemRarityBackgroundsModule.INSTANCE
        if (!module.isEnabled()) return
        for (slot in slots) if (slot.isActive) module.drawSlotBackground(graphics, slot, slot.x, slot.y)
    }

    /** Hotbar slots 1..9 are drawn in one burst on the first (seed 1); the offhand slot (seed 10) draws its own. */
    @JvmStatic
    fun beforeHotbarSlot(graphics: GuiGraphicsExtractor, player: Player, stack: ItemStack, x: Int, y: Int, seed: Int) {
        val module = ItemRarityBackgroundsModule.INSTANCE
        if (seed == 1) {
            for (slot in 0 until 9) module.drawBackground(graphics, player.inventory.getItem(slot), x + slot * 20, y)
        } else if (seed > 9) {
            module.drawBackground(graphics, stack, x, y)
        }
    }

    /** Called after the item is drawn, so these sit on top of it. */
    @JvmStatic
    fun afterItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        DungeonInvincibilityTimerModule.INSTANCE.drawItemFill(graphics, stack, x, y)
        ItemCooldownsModule.INSTANCE.drawOnItem(graphics, stack, x, y)
        MiningPowderModule.INSTANCE.drawOnItem(graphics, stack, x, y)
        HotmTreeHelperModule.INSTANCE.drawOnItem(graphics, stack, x, y)
        geiler.addons.client.module.impl.HotfTreeHelperModule.INSTANCE.drawOnItem(graphics, stack, x, y)
        geiler.addons.client.module.impl.TunerSolverModule.INSTANCE.drawOnItem(graphics, stack, x, y)
        geiler.addons.client.module.impl.PartyFinderListingsModule.INSTANCE.drawOnItem(graphics, stack, x, y)
    }
}
