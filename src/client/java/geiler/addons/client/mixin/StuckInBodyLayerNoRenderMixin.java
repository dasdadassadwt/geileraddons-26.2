package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.projectile.ArrowModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.StuckInBodyLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: arrows stuck in the player model. */
@Mixin(StuckInBodyLayer.class)
public abstract class StuckInBodyLayerNoRenderMixin {
	@Shadow
	@Final
	private Model<?> model;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void geileraddons$noStuckArrows(PoseStack poseStack, SubmitNodeCollector collector, int light,
		AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (this.model instanceof ArrowModel && NoRenderModule.INSTANCE.active(Flag.STUCK_ARROWS)) ci.cancel();
	}
}
