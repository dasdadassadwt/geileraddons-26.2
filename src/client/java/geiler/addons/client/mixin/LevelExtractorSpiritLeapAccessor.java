package geiler.addons.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identifies the renderer and isolated extraction state owned by a Leap feed. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorSpiritLeapAccessor {
	@Accessor("levelRenderer")
	LevelRenderer geiler$getLevelRenderer();
}
