package geiler.addons.client.gui.access;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Runtime invoker contract implemented by the mixin targeting the extractor's scissor stack. */
public interface GuiGraphicsExtractorScissorStackAccess {
	void geileraddons$invokePush(ScreenRectangle rectangle);

	void geileraddons$invokePop();

	ScreenRectangle geileraddons$invokePeek();
}
