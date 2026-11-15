package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.HidePlayersModule;
import geiler.addons.client.module.impl.NoRenderModule;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Render: entities removed from the visible set before they are extracted for rendering. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorNoRenderMixin {
	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
	private boolean geileraddons$noRenderEntity(LevelExtractor instance, Entity entity, Frustum frustum, double x,
		double y, double z, Operation<Boolean> original) {
		if (NoRenderModule.INSTANCE.shouldCancelRender(entity)) return false;
		if (HidePlayersModule.INSTANCE.shouldHide(entity)) return false;
		return original.call(instance, entity, frustum, x, y, z);
	}
}
