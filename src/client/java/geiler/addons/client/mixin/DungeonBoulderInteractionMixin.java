package geiler.addons.client.mixin;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.helpers.DungeonBoulderRules;
import geiler.addons.client.module.impl.DungeonAssistModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports consumed local block interactions to display-only Boulder route progress tracking. */
@Mixin(MultiPlayerGameMode.class)
abstract class DungeonBoulderInteractionMixin {
	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void geileraddons$recordBoulderInteraction(LocalPlayer player, InteractionHand hand,
		BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
		if (player == null || hit == null || player != Minecraft.getInstance().player) return;
		InteractionResult result = callback.getReturnValue();
		if (DungeonBoulderRules.shouldAdvanceInteraction(hand == InteractionHand.MAIN_HAND,
			DungeonContextTracker.isClearPhase()))
			DungeonAssistModule.INSTANCE.observeBoulderClick(hit.getBlockPos());
		if (hand == InteractionHand.MAIN_HAND && result == InteractionResult.SUCCESS)
			DungeonAssistModule.INSTANCE.observeWaterBoardClick(hit.getBlockPos());
	}
}
