package geiler.addons.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Equipment In Inventory move the offhand slot out of the equipment column. */
@Mixin(Slot.class)
public interface SlotPositionAccessor {
	@Mutable
	@Accessor("x")
	void geileraddons$setX(int x);
}
