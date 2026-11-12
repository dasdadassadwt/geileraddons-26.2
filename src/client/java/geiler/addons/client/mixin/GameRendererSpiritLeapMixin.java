package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets deferred Leap renderer cleanup progress on client frames that have no world render pass. */
@Mixin(GameRenderer.class)
public abstract class GameRendererSpiritLeapMixin {
	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
		shift = At.Shift.BEFORE))
	private void geiler$renderLeapPovBeforeMainCameraExtraction(DeltaTracker deltaTracker,
		boolean renderLevel, CallbackInfo ci) {
		GraphicsResourceAllocator allocator = ((GameRendererResourcePoolSpiritLeapAccessor) (Object) this)
			.geiler$getResourcePool();
		SpiritLeapPovRenderer.INSTANCE.beforeMainCameraExtraction(allocator, deltaTracker, renderLevel);
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void geiler$maintainLeapCleanupAfterFrame(DeltaTracker deltaTracker, boolean renderLevel,
		CallbackInfo ci) {
		// At render tail, deferred GUI blits are consumed before pending POV targets are published.
		SpiritLeapPovRenderer.INSTANCE.maintainDeferredCleanupAfterFrame();
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void geiler$tickLeapFeedCameras(CallbackInfo ci) {
		SpiritLeapPovRenderer.INSTANCE.tickCameras(Minecraft.getInstance());
	}
}
