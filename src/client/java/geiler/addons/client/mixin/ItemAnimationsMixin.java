package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.ItemAnimationsModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the local Item Animations settings only to the first-person item renderer. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemAnimationsMixin {
	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
	private void geileraddons$applyItemTransform(AbstractClientPlayer player, float partialTick, float pitch,
		InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress, PoseStack pose,
		SubmitNodeCollector collector, int packedLight, CallbackInfo ci) {
		ItemAnimationsModule.INSTANCE.applyTransform(pose, stack);
		ItemAnimationsModule.INSTANCE.applyPullOut(pose, hand == InteractionHand.MAIN_HAND);
	}

	@WrapMethod(method = "renderItem")
	private void geileraddons$withItemScaleScope(net.minecraft.world.entity.LivingEntity entity, ItemStack stack,
		net.minecraft.world.item.ItemDisplayContext displayContext, PoseStack pose,
		SubmitNodeCollector collector, int packedLight, Operation<Void> original) {
		ItemAnimationsModule.INSTANCE.beginItemScale(stack, displayContext);
		try {
			original.call(entity, stack, displayContext, pose, collector, packedLight);
		} finally {
			ItemAnimationsModule.INSTANCE.endItemScale();
		}
	}

	@Inject(method = "renderMap", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V",
		ordinal = 0, shift = At.Shift.BEFORE))
	private void geileraddons$applyMapScaleAtCenter(PoseStack pose, SubmitNodeCollector collector, int packedLight,
		ItemStack stack, CallbackInfo ci) {
		ItemAnimationsModule.INSTANCE.applyMapScaleAtCenter(pose, stack);
	}

	@Redirect(method = "submitHandsWithItems", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"))
	private float geileraddons$useConfiguredSwing(LocalPlayer player, float partialTick) {
		float vanilla = player.getAttackAnim(partialTick);
		return ItemAnimationsModule.INSTANCE.swingProgress(partialTick, vanilla);
	}

	@Redirect(method = "submitHandsWithItems", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V", ordinal = 0))
	private void geileraddons$removeFirstHandSway(PoseStack pose, Quaternionfc rotation) {
		if (!ItemAnimationsModule.INSTANCE.noHandSway()) pose.mulPose(rotation);
	}

	@Redirect(method = "submitHandsWithItems", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V", ordinal = 1))
	private void geileraddons$removeSecondHandSway(PoseStack pose, Quaternionfc rotation) {
		if (!ItemAnimationsModule.INSTANCE.noHandSway()) pose.mulPose(rotation);
	}

	@Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
	private void geileraddons$skipReequipReset(ItemStack previous, ItemStack next,
		CallbackInfoReturnable<Boolean> cir) {
		if (ItemAnimationsModule.INSTANCE.noReequipReset() || ItemAnimationsModule.INSTANCE.customPullOut()) cir.setReturnValue(true);
	}
}
