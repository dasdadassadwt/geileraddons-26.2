package geiler.addons.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the game's frame allocator without taking ownership of its lifecycle. */
@Mixin(GameRenderer.class)
public interface GameRendererResourcePoolSpiritLeapAccessor {
	@Accessor("resourcePool")
	CrossFrameResourcePool geiler$getResourcePool();
}
