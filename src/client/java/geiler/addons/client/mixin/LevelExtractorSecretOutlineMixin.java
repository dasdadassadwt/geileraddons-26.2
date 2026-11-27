package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.BiggerSecretHitboxesModule;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bigger Secret Hitboxes: the block outline (selection box) shows the enlarged hitbox, matching what the raycast hits. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorSecretOutlineMixin {
	@WrapOperation(method = "extractBlockOutline", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/BlockState;getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape geileraddons$secretOutline(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
			Operation<VoxelShape> original) {
		VoxelShape vanilla = original.call(state, level, pos, context);
		VoxelShape grown = BiggerSecretHitboxesModule.INSTANCE.outlineShape(state, level, pos, vanilla);
		return grown != null ? grown : vanilla;
	}
}
