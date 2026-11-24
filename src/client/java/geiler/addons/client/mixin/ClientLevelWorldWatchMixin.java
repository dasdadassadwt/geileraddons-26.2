package geiler.addons.client.mixin;

import geiler.addons.client.macros.pathing.WorldWatch;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link WorldWatch} about every block the client's world changes.
 *
 * <p>{@code sendBlockUpdated} is where the world hands a changed block on to the renderer, and it is reached by
 * everything that changes one: the server's block and section updates, the client's own predictions, and plain
 * {@code setBlock} calls. A chunk that arrives whole does not go through it; that is the chunk events' part.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelWorldWatchMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void geileraddons$worldWatchBlockUpdated(
		final BlockPos pos, final BlockState oldState, final BlockState newState, final int flags, final CallbackInfo info
	) {
		WorldWatch.blockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		geiler.addons.client.module.impl.CarnivalModule.INSTANCE.onBlockChanged(pos, oldState, newState);
		geiler.addons.client.module.impl.DojoHelperModule.INSTANCE.onBlockChanged(pos, newState);
	}
}
