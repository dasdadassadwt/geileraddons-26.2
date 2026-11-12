package geiler.addons.client.gui.access;

import net.minecraft.client.renderer.state.gui.GuiRenderState;

/** Runtime access to the GUI element collector implemented by the extractor mixin. */
public interface GuiGraphicsExtractorRenderStateAccess {
	GuiRenderState geileraddons$getGuiRenderState();
}
