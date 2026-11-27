package geiler.addons.client.module.impl;

/** Offline checks for the Animations pull-out styles: each curve starts displaced and ends at the normal pose. */
public final class ItemPullOutChecks {
	private ItemPullOutChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		for (String style : new String[] { "Rise", "Spin", "Pop", "Swing In" }) {
			float[] start = ItemAnimationsModule.pullOutPose(style, 0f);
			float[] half = ItemAnimationsModule.pullOutPose(style, 0.5f);
			float[] end = ItemAnimationsModule.pullOutPose(style, 1f);
			require(!identity(start), style + " starts away from the normal pose");
			require(!identity(half), style + " is still moving halfway");
			require(identity(end), style + " ends exactly at the normal pose");
			require(identity(ItemAnimationsModule.pullOutPose(style, 3f)), style + " stays put after it finished");
		}
		require(identity(ItemAnimationsModule.pullOutPose("Vanilla", 0f)), "Vanilla adds nothing");
		float[] rise = ItemAnimationsModule.pullOutPose("Rise", 0f);
		require(rise[1] < 0f, "Rise comes up from below");
		float[] pop = ItemAnimationsModule.pullOutPose("Pop", 0f);
		require(pop[5] > 0f && pop[5] < 1f, "Pop starts small");
		require(ItemAnimationsModule.pullOutProgress(0L, 150_000_000L, 1f) == 0.5f
			&& ItemAnimationsModule.pullOutProgress(0L, 150_000_000L, 2f) == 1f
			&& ItemAnimationsModule.pullOutProgress(0L, -5L, 1f) == 0f, "progress follows time and speed, clamped to 0..1");
	}

	/** dx, dy, dz, yaw, roll, scale */
	private static boolean identity(float[] p) {
		return near(p[0], 0) && near(p[1], 0) && near(p[2], 0) && near(p[3], 0) && near(p[4], 0) && near(p[5], 1);
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 1e-5f;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
