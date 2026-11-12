package geiler.addons.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the immutable asset dependency needed to construct Leap's separate entity dispatcher. */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccess {
	@Accessor("equipmentAssets")
	EquipmentAssetManager geiler$getEquipmentAssets();
}
