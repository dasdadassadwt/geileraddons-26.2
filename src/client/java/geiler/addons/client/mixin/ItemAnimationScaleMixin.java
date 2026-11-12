package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.ItemAnimationsModule;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies held-item size after vanilla's display transform so its hand-relative position is preserved. */
@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public abstract class ItemAnimationScaleMixin {
	@Inject(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;applyTransform(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V",
		shift = At.Shift.AFTER))
	private void geileraddons$scaleAfterDisplayTransform(PoseStack pose, SubmitNodeCollector collector,
		int packedLight, int packedOverlay, int flags, CallbackInfo ci) {
		ItemAnimationsModule.INSTANCE.applyDeferredItemScale(pose);
	}
}
