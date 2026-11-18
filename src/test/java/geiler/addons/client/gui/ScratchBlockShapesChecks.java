package geiler.addons.client.gui;

import java.util.List;

/** Offline checks for the Scratch-style macro block outlines (notches, bumps, C-blocks, hats, hexagons) and their mesh. */
public final class ScratchBlockShapesChecks {
	private ScratchBlockShapesChecks() { }

	public static void run() {
		float nx = ScratchBlockShapes.NOTCH_X;
		float nw = ScratchBlockShapes.NOTCH_WIDTH;
		float arm = ScratchBlockShapes.ARM_WIDTH;
		float notchMid = nx + nw / 2;

		ScratchBlockShapes.Shape upper = ScratchBlockShapes.stack(90, 27, false);
		ScratchBlockShapes.Shape lower = ScratchBlockShapes.stack(70, 27, false);
		for (float[] p : new float[][] {{notchMid, 1.5f}, {nx + 4, 2.0f}, {nx + nw - 4, 2.0f}}) {
			check(!lower.covers(p[0], p[1]), "the lower block's notch is open at " + p[0] + "," + p[1]);
			check(upper.covers(p[0], 27 + p[1]), "the upper block's bump fills the notch at " + p[0] + "," + p[1]);
		}
		check(lower.covers(nx - 2, 1.5f) && lower.covers(nx + nw + 2, 1.5f), "the notch is cut only where the bump goes");
		check(!upper.covers(nx - 2, 28.5f) && !upper.covers(nx + nw + 2, 28.5f), "the bump is no wider than the notch");
		check(!lower.covers(0.2f, 0.2f) && lower.covers(1.5f, 1.5f), "stack blocks have rounded corners");
		check(lower.covers(69.5f, 13) && !lower.covers(70.5f, 13), "stack blocks hug their width");
		check(near(upper.getHeight(), 27 + ScratchBlockShapes.NOTCH_DEPTH), "the bump hangs below the block");

		ScratchBlockShapes.Shape forever = ScratchBlockShapes.stack(90, 27, true);
		check(near(forever.getHeight(), 27) && !forever.covers(notchMid, 28), "a capped block has no bump");

		ScratchBlockShapes.Shape c = ScratchBlockShapes.cBlock(120, new int[] {27, 16}, new int[] {40}, false);
		float innerMid = 27 + 20;
		check(c.covers(arm / 2, innerMid) && !c.covers(arm + 2, innerMid) && !c.covers(100, innerMid),
			"the C-block body is only the left arm inside the gap");
		check(c.covers(arm + notchMid, 28.5f) && !c.covers(arm + nx - 3, 28.5f),
			"the top bar bumps into the gap where the first child's notch is");
		check(!c.covers(arm + notchMid, 68.5f) && c.covers(arm + nx + nw + 3, 68.5f),
			"the bottom bar has a notch for the last child's bump");
		check(near(c.getHeight(), 27 + 40 + 16 + ScratchBlockShapes.NOTCH_DEPTH), "the C-block height is bars, gap and bump");
		check(c.covers(119.5f, 75) && !c.covers(120.5f, 75), "the bottom bar spans the block width");

		ScratchBlockShapes.Shape ifElse = ScratchBlockShapes.cBlock(120, new int[] {27, 20, 16}, new int[] {30, 25}, false);
		float elseBar = 27 + 30;
		check(ifElse.covers(100, elseBar + 10) && !ifElse.covers(100, elseBar + 30)
			&& ifElse.covers(5, elseBar + 30), "the else bar separates two arm-only gaps");

		ScratchBlockShapes.Shape hat = ScratchBlockShapes.hat(140, 40);
		check(hat.covers(ScratchBlockShapes.HAT_DOME_WIDTH / 2f, 3) && !hat.covers(120, 3) && hat.covers(120, 12),
			"the hat has a dome on its top-left only");
		check(near(hat.getHeight(), 40 + ScratchBlockShapes.NOTCH_DEPTH), "a hat block has a bump for its first block");

		ScratchBlockShapes.Shape hex = ScratchBlockShapes.hexagon(60, 17);
		for (int y = 0; y < 17; y++) {
			for (int x = 0; x < 60; x++) {
				boolean here = hex.covers(x + 0.5f, y + 0.5f);
				check(here == hex.covers(59.5f - x, y + 0.5f) && here == hex.covers(x + 0.5f, 16.5f - y),
					"the hexagon is symmetric at " + x + "," + y);
			}
		}
		check(hex.covers(1, 8.5f) && !hex.covers(1, 1) && hex.covers(30, 1), "the hexagon has pointed ends");

		for (ScratchBlockShapes.Shape shape : List.of(upper, lower, forever, c, ifElse, hat, hex)) {
			float traps = trapezoidArea(ScratchBlockShapes.trapezoids(List.of(shape.getPoints())));
			check(Math.abs(traps - shape.getArea()) < shape.getArea() * 0.002f, "the fill trapezoids cover exactly the outline");
			float[] inner = ScratchBlockShapes.offset(shape.getPoints(), -1f);
			float ring = trapezoidArea(ScratchBlockShapes.trapezoids(List.of(shape.getPoints(), inner)));
			float innerArea = trapezoidArea(ScratchBlockShapes.trapezoids(List.of(inner)));
			check(ring > 0 && Math.abs(ring + innerArea - shape.getArea()) < shape.getArea() * 0.002f,
				"the outline ring and the inset fill tile the shape without overlap");
			float[] mesh = shape.mesh(1f, 0.5f);
			check(mesh.length > 0 && mesh.length % 12 == 0, "the mesh is whole quads of x, y, role vertices");
			for (int i = 2; i < mesh.length; i += 3) check(mesh[i] >= 0 && mesh[i] <= 3, "every vertex has a color role");
		}
		check(ScratchBlockShapes.stack(90, 27, false) == upper, "identical blocks reuse one cached outline");
	}

	private static float trapezoidArea(float[] t) {
		float area = 0;
		for (int i = 0; i < t.length; i += 6) area += (t[i + 1] - t[i]) * ((t[i + 3] - t[i + 2]) + (t[i + 5] - t[i + 4])) / 2;
		return area;
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 0.01f;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
