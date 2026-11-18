package geiler.addons.client.hud;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pure bounds checks for movable-HUD resizing. */
public final class HudManagerChecks {
	private HudManagerChecks() { }

	public static void run() {
		FakeElement interior = new FakeElement("offline_check_hud_scale_interior", 40, 20);
		HudManager.register(interior, 0.5f, 0.5f);
		int oldX = HudManager.x(interior, null, 400);
		int oldY = HudManager.y(interior, null, 300);
		HudManager.setScalePreservingTopLeft(interior, 1.5f, null, 400, 300);
		require(HudManager.x(interior, null, 400) == oldX && HudManager.y(interior, null, 300) == oldY,
			"resizing preserves the panel top-left when there is room");

		FakeElement atEdge = new FakeElement("offline_check_hud_scale_edge", 40, 20);
		HudManager.register(atEdge, 0.99f, 0.99f);
		int oldEdgeX = HudManager.x(atEdge, null, 400);
		int oldEdgeY = HudManager.y(atEdge, null, 300);
		HudManager.setScalePreservingTopLeft(atEdge, 1.5f, null, 400, 300);
		require(HudManager.x(atEdge, null, 400) == 400 - HudManager.scaledWidth(atEdge, null)
			&& HudManager.y(atEdge, null, 300) == 300 - HudManager.scaledHeight(atEdge, null),
			"resizing clamps the panel inside the viewport when its prior anchor no longer fits");
		require(HudManager.x(atEdge, null, 400) <= oldEdgeX && HudManager.y(atEdge, null, 300) <= oldEdgeY,
			"edge clamping does not let a resized panel extend beyond the screen");

		ToggleModule off = new ToggleModule(true);
		ToggleModule on = new ToggleModule(true);
		on.setEnabled(true);
		ToggleModule fixed = new ToggleModule(false);
		require(!HudManager.editorVisible(new FakeElement("offline_check_hud_off", 40, 20, off)),
			"a disabled module's element is hidden in the editor");
		require(HudManager.editorVisible(new FakeElement("offline_check_hud_on", 40, 20, on)),
			"an enabled module's element is shown in the editor");
		require(HudManager.editorVisible(new FakeElement("offline_check_hud_fixed", 40, 20, fixed)),
			"a module without a toggle never hides its element");
		require(HudManager.editorVisible(new FakeElement("offline_check_hud_none", 40, 20, null)),
			"an element with no module is always shown");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class ToggleModule extends Module {
		private final boolean toggle;

		private ToggleModule(boolean toggle) {
			super("hud-check", "hud check", Category.MISCELLANEOUS);
			this.toggle = toggle;
		}

		@Override public boolean showsToggleControl() { return toggle; }
	}

	private record FakeElement(String id, int width, int height, Module module) implements HudElement {
		private FakeElement(String id, int width, int height) { this(id, width, height, null); }

		@Override public String displayName() { return id; }
		@Override public int width(Font font) { return width; }
		@Override public int height(Font font) { return height; }
		@Override public Module module() { return module; }
		@Override public boolean visible() { return false; }
		@Override public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) { }
	}
}
