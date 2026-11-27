package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import geiler.addons.client.module.impl.SimonSaysModule;
import geiler.addons.client.module.impl.SpiritBearModule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every client block change, as Odin's {@code LevelChunkMixin} posts its {@code BlockUpdateEvent}: single and multi-block
 * server updates and the client's own predicted button press all pass through here.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSimonSaysMixin {
	@WrapOperation(method = "setBlockState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState geileraddons$onSimonSaysBlockChange(LevelChunkSection section, int x, int y, int z, BlockState state,
		Operation<BlockState> original, @Local(argsOnly = true) BlockPos pos) {
		BlockState old = original.call(section, x, y, z, state);
		// Only the client level on the client thread; the integrated server shares this class.
		if (old != state && ((LevelChunk) (Object) this).getLevel().isClientSide() && Minecraft.getInstance().isSameThread()) {
			SimonSaysModule.INSTANCE.onBlockChange(pos, old, state);
			SpiritBearModule.INSTANCE.onBlockChange(pos, old, state);
		}
		return old;
	}
}
