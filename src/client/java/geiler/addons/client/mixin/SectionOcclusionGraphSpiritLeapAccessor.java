package geiler.addons.client.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What the main renderer knows is loaded, to start a private POV renderer from. */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphSpiritLeapAccessor {
	@Accessor("loadedChunks")
	LongOpenHashSet geiler$loadedChunks();

	@Accessor("emptySections")
	LongOpenHashSet geiler$emptySections();
}
