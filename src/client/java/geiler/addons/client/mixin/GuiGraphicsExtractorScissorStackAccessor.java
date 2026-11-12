package geiler.addons.client.mixin;

import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorStackAccess;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes the public operations on the package-private nested scissor stack by its mapped name. */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$ScissorStack")
public interface GuiGraphicsExtractorScissorStackAccessor extends GuiGraphicsExtractorScissorStackAccess {
	@Override
	@Invoker("push")
	void geileraddons$invokePush(ScreenRectangle rectangle);

	@Override
	@Invoker("pop")
	void geileraddons$invokePop();

	@Override
	@Invoker("peek")
	ScreenRectangle geileraddons$invokePeek();
}
