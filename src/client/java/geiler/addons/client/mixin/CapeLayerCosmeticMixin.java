package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.cosmetics.LiveCosmetics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player who wears a Cosmetics cape does not also get the vanilla cape; the cosmetic one is drawn by the Cosmetics layer. */
@Mixin(CapeLayer.class)
public abstract class CapeLayerCosmeticMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideForCosmeticCape(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
		float yRot, float xRot, CallbackInfo ci) {
		if (LiveCosmetics.replacesVanillaCape(state)) ci.cancel();
	}
}
