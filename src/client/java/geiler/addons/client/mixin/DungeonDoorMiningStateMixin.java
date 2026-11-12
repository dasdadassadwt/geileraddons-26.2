package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Uses the authoritative source state for Vanilla mining queries on owned Glass Doors projections. */
@Mixin(MultiPlayerGameMode.class)
public abstract class DungeonDoorMiningStateMixin {
	/** Survival branch state captured for the START action and its destroy-progress check. */
	@WrapOperation(method = "startDestroyBlock", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState geileraddons$useSourceAtStart(ClientLevel level, BlockPos position,
		Operation<BlockState> original) {
		BlockState observed = original.call(level, position);
		return DungeonDoorBlockOverlayController.miningState(level, position, observed);
	}

	/** Survival branch state used for the accumulated per-tick mining progress. */
	@WrapOperation(method = "continueDestroyBlock", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState geileraddons$useSourceWhileContinuing(ClientLevel level, BlockPos position,
		Operation<BlockState> original) {
		BlockState observed = original.call(level, position);
		return DungeonDoorBlockOverlayController.miningState(level, position, observed);
	}

	/** State passed to Vanilla's ordinary permission, destroy, and local prediction path. */
	@WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState geileraddons$useSourceWhenDestroying(Level level, BlockPos position,
		Operation<BlockState> original) {
		BlockState observed = original.call(level, position);
		if (level instanceof ClientLevel clientLevel)
			return DungeonDoorBlockOverlayController.miningState(clientLevel, position, observed);
		return observed;
	}
}
