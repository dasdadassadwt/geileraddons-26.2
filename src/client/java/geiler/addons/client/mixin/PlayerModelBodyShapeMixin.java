package geiler.addons.client.mixin;

import geiler.addons.client.cosmetics.ArmorBodyWarp;
import geiler.addons.client.cosmetics.BodyShape;
import geiler.addons.client.cosmetics.CosmeticsRenderLayer;
import geiler.addons.client.module.impl.CosmeticsModule;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player skin and player armor share PlayerModel. For the skin model, hides the vanilla torso and jacket of a
 * player with a Cosmetics body shape (the cosmetics layer draws the shaped one). For an armor model, arms
 * {@link ArmorBodyWarp} so the chestplate and leggings bend to the same shape. setupAnim runs right before
 * each render, so the state set here is current when the part draws.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelBodyShapeMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void geileraddons$applyBodyShape(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel self = (PlayerModel) (Object) this;
		BodyShape shape = state == null ? null : CosmeticsModule.INSTANCE.bodyShape(state.id);
		if (CosmeticsRenderLayer.isSkinModel(self)) {
			// Set every frame: the model is shared by every player drawn with it.
			boolean shaped = shape != null && !state.isInvisible;
			self.body.skipDraw = shaped;
			self.jacket.skipDraw = shaped;
		} else {
			ArmorBodyWarp.arm(self.body, shape);
		}
	}
}
