package geiler.addons.client.render;

import java.util.Set;

/** Offline checks for projected-label slot choice: a label must not hop between slots as neighbours move. */
public final class ProjectedLabelChecks {
	private ProjectedLabelChecks() { }

	public static void run() {
		// Nothing remembered: first free slot, scanning 0, -1, 1, -2, 2 ...
		require(ProjectedLabelRenderer.pickOffset(ProjectedLabelRenderer.NO_OFFSET, o -> true) == 0, "free slot 0 is taken first");
		require(ProjectedLabelRenderer.pickOffset(ProjectedLabelRenderer.NO_OFFSET, o -> o != 0) == -1, "scan skips a blocked slot 0");

		// Remembered slot 1 stays while free, even though slot 0 became free again (this was the up/down hop).
		require(ProjectedLabelRenderer.pickOffset(1, o -> true) == 1, "a remembered free slot is kept");
		// Blocked remembered slot falls back to the scan.
		Set<Integer> blocked = Set.of(1);
		require(ProjectedLabelRenderer.pickOffset(1, o -> !blocked.contains(o)) == 0, "a blocked remembered slot falls back to the first free one");
		// Unknown remembered value is ignored; everything blocked gives no slot.
		require(ProjectedLabelRenderer.pickOffset(99, o -> o == 2) == 2, "an unknown remembered slot is ignored");
		require(ProjectedLabelRenderer.pickOffset(0, o -> false) == null, "no free slot gives none");

		// Label keys come from the world spot alone: the text changes every step (distance) but the label is the same one.
		require(ProjectedLabelRenderer.labelKey(10.2, 64.0, -5.9) == ProjectedLabelRenderer.labelKey(10.4, 64.3, -5.6),
			"a label keeps its key within one block");
		require(ProjectedLabelRenderer.labelKey(10.2, 64.0, -5.9) != ProjectedLabelRenderer.labelKey(40.2, 64.0, -5.9),
			"another spot has another key");
		// A slot change eases in instead of jumping a whole line.
		require(ProjectedLabelRenderer.ease(0f, 10f, 0f) == 0f, "no elapsed time moves nothing");
		float step = ProjectedLabelRenderer.ease(0f, 10f, 16f);
		require(step > 0f && step < 10f, "one frame covers part of the gap");
		require(ProjectedLabelRenderer.ease(step, 10f, 16f) > step, "easing keeps closing the gap");
		require(ProjectedLabelRenderer.ease(0f, 10f, 5000f) == 10f, "a long gap arrives at the target");
		require(ProjectedLabelRenderer.ease(5f, 5.1f, 16f) == 5.1f, "a sub-pixel gap snaps so it never crawls");
		require(ProjectedLabelRenderer.ease(Float.NaN, 3f, 16f) == 3f, "a bad current value restarts at the target");
		require(ProjectedLabelRenderer.ease(10f, 0f, 16f) < 10f, "easing also works downward");
		System.out.println("Projected label checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
