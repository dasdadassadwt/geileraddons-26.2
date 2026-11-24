package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import geiler.addons.client.cosmetics.ArmorBodyWarp;
import geiler.addons.client.cosmetics.BodyShape;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Draws the armed armor body part bent to the wearer's Cosmetics body shape (see {@link ArmorBodyWarp}).
 * Hooks render, not compile, and runs early, so renderers that replace ModelPart.render (Sodium) still see it.
 * Everything but the body cubes renders exactly like vanilla: the children go through their own render.
 */
@Mixin(value = ModelPart.class, priority = 500)
public abstract class ModelPartArmorShapeMixin {
	@Shadow public boolean visible;
	@Shadow public boolean skipDraw;
	@Shadow @Final private List<ModelPart.Cube> cubes;
	@Shadow @Final private Map<String, ModelPart> children;

	@Shadow public abstract void translateAndRotate(PoseStack pose);

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At("HEAD"), cancellable = true)
	private void geileraddons$renderShapedArmor(PoseStack pose, VertexConsumer consumer, int light, int overlay, int color,
		CallbackInfo ci) {
		ModelPart self = (ModelPart) (Object) this;
		BodyShape shape = ArmorBodyWarp.shapeFor(self);
		if (shape == null || !visible || cubes.isEmpty()) return;
		ci.cancel();
		pose.pushPose();
		translateAndRotate(pose);
		if (!skipDraw) ArmorBodyWarp.compile(cubes, shape, pose.last(), consumer, light, overlay, color);
		for (ModelPart child : children.values()) child.render(pose, consumer, light, overlay, color);
		pose.popPose();
	}
}
