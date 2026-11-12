package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps secondary extraction isolated from the main camera and shared client caches. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorSpiritLeapMixin {
	@Redirect(method = "extract", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/Minecraft;crosshairPickEntity:Lnet/minecraft/world/entity/Entity;"))
	private Entity geiler$excludeLocalCrosshairFromLeapView(Minecraft client) {
		LevelRenderer renderer = ((LevelExtractorSpiritLeapAccessor) (Object) this).geiler$getLevelRenderer();
		boolean secondary = SpiritLeapPovRenderer.INSTANCE.isSecondary(renderer);
		return SpiritLeapPovRenderer.shouldUseMainCrosshair(secondary) ? client.crosshairPickEntity : null;
	}

	@Redirect(method = "allChanged", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;clearTintCaches()V"))
	private void geiler$preserveSharedTintCaches(ClientLevel level) {
		LevelRenderer renderer = ((LevelExtractorSpiritLeapAccessor) (Object) this).geiler$getLevelRenderer();
		if (!SpiritLeapPovRenderer.INSTANCE.isSecondary(renderer)) level.clearTintCaches();
	}

	@Redirect(method = "allChanged", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;mainCamera()Lnet/minecraft/client/Camera;"))
	private Camera geiler$useLeapPovCamera(GameRenderer gameRenderer) {
		LevelRenderer renderer = ((LevelExtractorSpiritLeapAccessor) (Object) this).geiler$getLevelRenderer();
		return SpiritLeapPovRenderer.INSTANCE.cameraFor(renderer, gameRenderer.mainCamera());
	}
}
