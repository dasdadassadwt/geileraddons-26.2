package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Render: the fire drawn on burning entities. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNoRenderMixin {
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"))
	private boolean geileraddons$noEntityFire(boolean original) {
		return !NoRenderModule.INSTANCE.active(Flag.ENTITY_FIRE) && original;
	}
}
