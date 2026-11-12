package geiler.addons.client.hud;

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
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record FakeElement(String id, int width, int height) implements HudElement {
		@Override public String displayName() { return id; }
		@Override public int width(Font font) { return width; }
		@Override public int height(Font font) { return height; }
		@Override public boolean visible() { return false; }
		@Override public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) { }
	}
}
