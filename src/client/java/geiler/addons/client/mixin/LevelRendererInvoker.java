package geiler.addons.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Resolves the normal chain only for the main renderer; the POV renderer supplies its own. */
@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {
	@Invoker("getTransparencyChain")
	PostChain geiler$getTransparencyChain();
}
