package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.ItemAnimationsModule;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the swing adjustment chainable with default-priority renderer mixins such as NoFrills 0.4.15.
 * MixinExtras can wrap an earlier redirect, while another redirect cannot claim the same invocation.
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1100)
public abstract class ItemSwingAnimationMixin {
	@WrapOperation(method = "swingArm", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void geileraddons$swingInPlace(PoseStack pose, float x, float y, float z,
		Operation<Void> original) {
		if (!ItemAnimationsModule.INSTANCE.inPlaceSwing()) original.call(pose, x, y, z);
	}
}
