package geiler.addons.client.mixin;

import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Mutable bridge for the active POV extraction scope. */
@Mixin(GameRenderState.class)
public interface GameRenderStateSpiritLeapAccessor {
	@Accessor("levelRenderState")
	LevelRenderState geiler$getLevelRenderState();

	@Accessor("levelRenderState")
	@Mutable
	void geiler$setLevelRenderState(LevelRenderState levelRenderState);
}
