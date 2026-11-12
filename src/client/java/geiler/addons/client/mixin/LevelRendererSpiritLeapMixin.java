package geiler.addons.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.level.block.LeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes the frame-graph targets and shared render state for a scoped Leap renderer pass. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSpiritLeapMixin {
	@Redirect(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget geiler$useLeapPovTarget(GameRenderer gameRenderer) {
		LevelRenderer renderer = (LevelRenderer) (Object) this;
		RenderTarget povTarget = SpiritLeapPovRenderer.INSTANCE.targetFor(renderer);
		return povTarget == null ? gameRenderer.mainRenderTarget() : povTarget;
	}

	@Redirect(method = "lambda$render$0(Lorg/joml/Vector4f;)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget geiler$clearLeapFrameGraphTarget(GameRenderer gameRenderer) {
		return SpiritLeapPovRenderer.INSTANCE.clearTargetFor((LevelRenderer) (Object) this,
			gameRenderer.mainRenderTarget());
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;getTransparencyChain()Lnet/minecraft/client/renderer/PostChain;"))
	private PostChain geiler$useLeapTransparencyChain(LevelRenderer renderer) {
		SpiritLeapPovRenderer pov = SpiritLeapPovRenderer.INSTANCE;
		if (pov.isSecondary(renderer)) return pov.transparencyChainFor(renderer, null);
		return ((LevelRendererInvoker) (Object) renderer).geiler$getTransparencyChain();
	}

	@Redirect(method = "invalidateCompiledGeometry", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/LeavesBlock;setCutoutLeaves(Z)V"))
	private void geiler$preserveSharedLeafSetting(boolean cutoutLeaves) {
		if (!SpiritLeapPovRenderer.INSTANCE.isSecondary((LevelRenderer) (Object) this)) {
			LeavesBlock.setCutoutLeaves(cutoutLeaves);
		}
	}
}
