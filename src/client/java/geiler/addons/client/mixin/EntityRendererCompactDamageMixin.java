package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import geiler.addons.client.module.impl.CompactDamageModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Compact Damage: swaps a damage armor stand's name tag for its shortened version. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCompactDamageMixin {
	@ModifyReturnValue(method = "getNameTag", at = @At("RETURN"))
	private <T extends Entity> Component geileraddons$compactDamageName(Component original, T entity) {
		return CompactDamageModule.INSTANCE.nameFor(entity, original);
	}
}
