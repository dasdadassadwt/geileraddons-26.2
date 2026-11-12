package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapResourceReloadListener;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records Spirit Leap shader visibility before Minecraft builds its shader-source table. */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerSpiritLeapResourceProbeMixin {
	@Inject(method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Lnet/minecraft/client/renderer/ShaderManager$Configs;",
		at = @At("HEAD"))
	private void geileraddons$recordSpiritLeapShaderResources(
		ResourceManager resourceManager,
		ProfilerFiller profiler,
		CallbackInfoReturnable<?> cir
	) {
		SpiritLeapResourceReloadListener.recordShaderPreparation(resourceManager);
	}
}
