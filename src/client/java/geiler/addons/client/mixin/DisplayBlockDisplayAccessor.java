package geiler.addons.client.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads a block display's state; the vanilla getter is private. */
@Mixin(Display.BlockDisplay.class)
public interface DisplayBlockDisplayAccessor {
	@Invoker("getBlockState")
	BlockState geileraddons$getBlockState();
}
