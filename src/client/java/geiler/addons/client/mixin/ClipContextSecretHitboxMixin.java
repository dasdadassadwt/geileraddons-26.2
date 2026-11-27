package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.BiggerSecretHitboxesModule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bigger Secret Hitboxes: only OUTLINE raycasts (aiming, clicking) see the enlarged shape; collision is untouched. */
@Mixin(ClipContext.class)
public abstract class ClipContextSecretHitboxMixin {
	@Shadow @Final private ClipContext.Block block;

	@Inject(method = "getBlockShape", at = @At("RETURN"), cancellable = true)
	private void geileraddons$secretHitbox(BlockState state, BlockGetter level, BlockPos pos,
			CallbackInfoReturnable<VoxelShape> result) {
		if (this.block != ClipContext.Block.OUTLINE) return;
		VoxelShape grown = BiggerSecretHitboxesModule.INSTANCE.outlineShape(state, level, pos, result.getReturnValue());
		if (grown != null) result.setReturnValue(grown);
	}
}
