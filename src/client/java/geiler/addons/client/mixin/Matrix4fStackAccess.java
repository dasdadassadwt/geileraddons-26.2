package geiler.addons.client.mixin;

import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Captures/restores the depth of Minecraft's render-thread model-view stack around a POV pass. */
@Mixin(Matrix4fStack.class)
public interface Matrix4fStackAccess {
	@Accessor("curr")
	int geiler$getStackDepth();

	@Accessor("curr")
	void geiler$setStackDepth(int depth);
}
