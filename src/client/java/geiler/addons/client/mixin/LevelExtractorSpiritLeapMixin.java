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

	/** A private extractor's section tracker must be sized like its renderer's capped view. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = {"allChanged", "extract"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"), require = 0)
	private int geiler$capLeapViewDistance(int distance) {
		LevelRenderer renderer = ((LevelExtractorSpiritLeapAccessor) (Object) this).geiler$getLevelRenderer();
		return SpiritLeapPovRenderer.INSTANCE.isSecondary(renderer)
			? geiler.addons.client.module.impl.SpiritLeapPrivateRenderers.cappedViewDistance(distance) : distance;
	}

	/**
	 * The chunk cache collects load changes between two flips. Only the main extraction may consume them; a private one
	 * that flipped them would leave holes in the player's own view.
	 */
	@com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "extract", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;flipUpdateTrackingSets()V"), require = 0)
	private boolean geiler$onlyMainConsumesChunkUpdates(net.minecraft.client.multiplayer.ClientChunkCache chunks) {
		LevelRenderer renderer = ((LevelExtractorSpiritLeapAccessor) (Object) this).geiler$getLevelRenderer();
		if (SpiritLeapPovRenderer.INSTANCE.isSecondary(renderer)) return false;
		geiler.addons.client.module.impl.SpiritLeapPrivateRenderers.beforeMainFlip(chunks);
		return true;
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "setSectionDirty(IIIZ)V", at = @At("TAIL"), require = 0)
	private void geiler$forwardDirtySectionToLeapViews(int x, int y, int z, boolean fromPlayer,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		geiler.addons.client.module.impl.SpiritLeapPrivateRenderers.forwardDirty(
			(net.minecraft.client.renderer.extract.LevelExtractor) (Object) this, x, y, z, fromPlayer);
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
