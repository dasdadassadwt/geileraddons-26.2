package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.ArmorTransparencyModule;
import geiler.addons.client.module.impl.ArmorTransparencyRules;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.SkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor Transparency for what a player wears on their head that is not armor: mob and player skulls. The Helmet
 * slider applies. 0 hides the head item; a fade redraws a skull with its translucent render type and a scaled alpha.
 * Non-skull head items (a pumpkin, a block) have no tint hook, so they are hidden at 0 and drawn normally otherwise.
 */
@Mixin(CustomHeadLayer.class)
public abstract class CustomHeadLayerArmorTransparencyMixin {
	@Shadow
	private PlayerSkinRenderCache playerSkinRenderCache;

	/** Opacity and translucent skull type of the head layer being drawn; 1 and null outside an affected layer. */
	private static float geileraddons$opacity = 1.0f;
	private static RenderType geileraddons$skullType;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
		+ "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
	private void geileraddons$headOpacity(PoseStack pose, SubmitNodeCollector collector, int light,
		LivingEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
		geileraddons$opacity = 1.0f;
		geileraddons$skullType = null;
		if (!(state instanceof AvatarRenderState player)) return;
		float opacity = ArmorTransparencyModule.INSTANCE.opacityFor(EquipmentSlot.HEAD, player.id);
		if (opacity <= 0.0f) {
			ci.cancel();
			return;
		}
		if (opacity >= 1.0f || state.wornHeadType == null) return;
		Identifier texture = null;
		if (state.wornHeadType == SkullBlock.Types.PLAYER) {
			if (state.wornHeadProfile != null) {
				texture = playerSkinRenderCache.getOrDefault(state.wornHeadProfile).playerSkin().body().texturePath();
			}
		} else {
			texture = SkullBlockRendererAccessor.geileraddons$skinByType().get(state.wornHeadType);
		}
		if (texture == null) return;
		geileraddons$opacity = opacity;
		geileraddons$skullType = RenderTypes.entityTranslucent(texture);
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
		+ "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("RETURN"))
	private void geileraddons$headOpacityDone(PoseStack pose, SubmitNodeCollector collector, int light,
		LivingEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
		geileraddons$opacity = 1.0f;
		geileraddons$skullType = null;
	}

	/** Draws a faded skull: the same model and state as vanilla, with a translucent render type and a scaled tint. */
	@WrapOperation(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
		+ "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/blockentity/SkullBlockRenderer;submitSkull(FLcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/model/object/skull/SkullModelBase;"
			+ "Lnet/minecraft/client/renderer/rendertype/RenderType;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
	private void geileraddons$fadeSkull(float animationPos, PoseStack pose, SubmitNodeCollector collector, int light,
		SkullModelBase model, RenderType type, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling,
		Operation<Void> original) {
		if (geileraddons$skullType == null || geileraddons$opacity >= 1.0f) {
			original.call(animationPos, pose, collector, light, model, type, outlineColor, crumbling);
			return;
		}
		SkullModelBase.State skullState = new SkullModelBase.State();
		skullState.animationPos = animationPos;
		collector.submitModel(model, skullState, pose, geileraddons$skullType, light, OverlayTexture.NO_OVERLAY,
			ArmorTransparencyRules.fade(0xFFFFFFFF, geileraddons$opacity), null, outlineColor, crumbling);
	}
}
