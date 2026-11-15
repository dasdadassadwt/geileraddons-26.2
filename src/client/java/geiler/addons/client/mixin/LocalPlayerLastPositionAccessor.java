package geiler.addons.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The position last sent to the server, which the dragon stack aimer aims from. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerLastPositionAccessor {
	@Accessor("xLast")
	double geileraddons$getXLast();

	@Accessor("yLast")
	double geileraddons$getYLast();

	@Accessor("zLast")
	double geileraddons$getZLast();
}
