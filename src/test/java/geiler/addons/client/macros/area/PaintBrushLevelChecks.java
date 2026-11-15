package geiler.addons.client.macros.area;

import java.util.ArrayList;
import java.util.List;

/**
 * The brush paints one level of ground: the stand under the aim and what is joined to it on that level. Ground a platform higher, a floor
 * far below and ground over a gap that is too tall to step are left alone.
 */
public final class PaintBrushLevelChecks {
	private PaintBrushLevelChecks() { }

	public static void run() {
		checkPlatformAboveIsNotPainted();
		checkSlabsAndStairsAreTheSameLevel();
		checkAGapBreaksTheFlood();
		checkNothingAtTheAim();
		checkReferenceIsTheNearestLevel();
	}

	private static BrushLevel.Stand stand(int x, int y, double floor) {
		return new BrushLevel.Stand(x, y, 0, floor);
	}

	private static void checkPlatformAboveIsNotPainted() {
		List<BrushLevel.Stand> candidates = new ArrayList<>();
		for (int x = 0; x < 5; x++) candidates.add(stand(x, 64, 64.0));
		// A platform two blocks up over the same columns, and the top of a wall two blocks high next to the ground.
		for (int x = 0; x < 5; x++) candidates.add(stand(x, 66, 66.0));
		candidates.add(stand(5, 66, 66.0));
		BrushLevel.Stand reference = BrushLevel.reference(candidates, 2, 64, 0);
		check(reference != null && reference.getY() == 64, "the reference is the ground at the aim");
		List<BrushLevel.Stand> painted = BrushLevel.select(candidates, reference);
		check(painted.size() == 5, "only the ground is painted (" + painted.size() + ")");
		for (BrushLevel.Stand stand : painted) check(stand.getY() == 64, "no cell of the platform above");
	}

	private static void checkSlabsAndStairsAreTheSameLevel() {
		List<BrushLevel.Stand> candidates = new ArrayList<>();
		candidates.add(stand(0, 64, 64.0));
		candidates.add(stand(1, 64, 64.5));
		candidates.add(stand(2, 65, 65.0));
		BrushLevel.Stand reference = BrushLevel.reference(candidates, 0, 64, 0);
		check(BrushLevel.select(candidates, reference).size() == 3, "a slab and a step up are the same level");
		candidates.add(stand(3, 66, 66.0));
		check(BrushLevel.select(candidates, reference).size() == 3, "but the second step up is not");
	}

	private static void checkAGapBreaksTheFlood() {
		List<BrushLevel.Stand> candidates = new ArrayList<>();
		candidates.add(stand(0, 64, 64.0));
		candidates.add(stand(1, 64, 64.0));
		// No column at x = 2, then more ground of the same height: not joined, so not painted.
		candidates.add(stand(3, 64, 64.0));
		BrushLevel.Stand reference = BrushLevel.reference(candidates, 0, 64, 0);
		check(BrushLevel.select(candidates, reference).size() == 2, "ground across a gap is not joined");
	}

	private static void checkNothingAtTheAim() {
		List<BrushLevel.Stand> candidates = new ArrayList<>();
		candidates.add(stand(0, 70, 70.0));
		check(BrushLevel.reference(candidates, 0, 64, 0) == null, "ground more than two cells from the aim is not the reference");
		check(BrushLevel.select(candidates, null).isEmpty(), "with no reference nothing is painted");
		check(BrushLevel.reference(new ArrayList<>(), 0, 64, 0) == null, "and with no ground at all there is none");
	}

	private static void checkReferenceIsTheNearestLevel() {
		List<BrushLevel.Stand> candidates = new ArrayList<>();
		candidates.add(stand(0, 63, 63.0));
		candidates.add(stand(1, 65, 65.0));
		candidates.add(stand(2, 64, 64.0));
		BrushLevel.Stand reference = BrushLevel.reference(candidates, 0, 64, 0);
		check(reference != null && reference.getY() == 64, "the level at the aim's height wins over a nearer column on another level");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
