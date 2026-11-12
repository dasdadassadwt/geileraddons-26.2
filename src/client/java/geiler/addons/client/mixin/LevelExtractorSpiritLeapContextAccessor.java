package geiler.addons.client.mixin;

import net.fabricmc.fabric.impl.client.rendering.level.LevelExtractionContextImpl;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the isolated extraction context owned by this feed's private LevelExtractor. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorSpiritLeapContextAccessor {
	@Accessor("extractionContext")
	LevelExtractionContextImpl geiler$getExtractionContext();
}
