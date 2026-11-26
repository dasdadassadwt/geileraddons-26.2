package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.LegacyTexturesModule;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Legacy Textures: every item model lookup goes through {@code appendItemLayers}, so one swap covers GUI, hand and world. */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverLegacyTexturesMixin {
	@ModifyVariable(method = "appendItemLayers", at = @At("HEAD"), argsOnly = true)
	private ItemStack geileraddons$legacyModel(ItemStack stack) {
		return LegacyTexturesModule.INSTANCE.remap(stack);
	}
}
