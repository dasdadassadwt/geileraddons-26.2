package geiler.addons.client.gui.access;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Provides the exact active scissor rectangle captured from an extractor's scissor stack. */
public interface GuiGraphicsExtractorScissorAccess {
	ScreenRectangle geileraddons$getScissorArea();
}
