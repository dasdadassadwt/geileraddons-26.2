package geiler.addons.client.dungeon;

import java.util.List;

/** Focused pure checks for Devonian-compatible corner selection and component transforms. */
public final class DungeonRoomOrientationChecks {
	private DungeonRoomOrientationChecks() { }
	public static void main(String[] args) { run(); }

	public static void run() {
		var oneByOne = DungeonGridRules.cellAtOrigin(-200, -200);
		List<DungeonRoomOrientationRules.Candidate> candidates =
			DungeonRoomOrientationRules.candidates(List.of(oneByOne), "1x1", 80);
		equals(4, candidates.size(), "a single room exposes its four marker corners");
		equals(new DungeonRoomOrientationRules.Candidate(0, -200, 80, -200), candidates.get(0), "NW corner and rotation");
		equals(new DungeonRoomOrientationRules.Candidate(1, -170, 80, -200), candidates.get(1), "NE corner and rotation");
		equals(new DungeonRoomOrientationRules.Candidate(2, -170, 80, -170), candidates.get(2), "SE corner and rotation");
		equals(new DungeonRoomOrientationRules.Candidate(3, -200, 80, -170), candidates.get(3), "SW corner and rotation");

		equals(new DungeonRoomOrientationRules.Position(12, 70, 24),
			DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(0, 10, 80, 20), 2, 70, 4), "NW source coordinate transform");
		equals(new DungeonRoomOrientationRules.Position(6, 70, 22),
			DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(1, 10, 80, 20), 2, 70, 4), "NE source coordinate transform");
		equals(new DungeonRoomOrientationRules.Position(8, 70, 16),
			DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(2, 10, 80, 20), 2, 70, 4), "SE source coordinate transform");
		equals(new DungeonRoomOrientationRules.Position(14, 70, 18),
			DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(3, 10, 80, 20), 2, 70, 4), "SW source coordinate transform");

		equals(candidates.get(1), DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates,
			candidate -> candidate.rotation() == 1), "one roof marker resolves one rotation");
		isNull(DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates,
			candidate -> candidate.rotation() == 0 || candidate.rotation() == 3), "ambiguous roof markers fail closed");
		isNull(DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates, candidate -> false), "missing roof marker fails closed");

		List<DungeonGridRules.Cell> horizontal = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-168, -200),
			DungeonGridRules.cellAtOrigin(-136, -200), DungeonGridRules.cellAtOrigin(-104, -200));
		equals(List.of(0, 3, 1, 2), DungeonRoomOrientationRules.candidates(horizontal, "1x4", 84)
			.stream().map(DungeonRoomOrientationRules.Candidate::rotation).toList(),
			"horizontal 1x4 uses only its four Devonian endpoint corners");
		List<DungeonGridRules.Cell> vertical = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-200, -168),
			DungeonGridRules.cellAtOrigin(-200, -136), DungeonGridRules.cellAtOrigin(-200, -104));
		equals(List.of(0, 1, 2, 3), DungeonRoomOrientationRules.candidates(vertical, "1x4", 84)
			.stream().map(DungeonRoomOrientationRules.Candidate::rotation).toList(),
			"vertical 1x4 uses only its four Devonian endpoint corners");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(horizontal.get(0), horizontal.get(2),
			horizontal.get(3), DungeonGridRules.cellAtOrigin(-72, -200)), "1x4", 84),
			"invalid noncontiguous room footprints do not produce an orientation");
		isEmpty(DungeonRoomOrientationRules.candidates(horizontal, "unknown", 84),
			"unknown room shapes do not produce an orientation");

		List<DungeonGridRules.Cell> horizontalTwo = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-168, -200));
		List<DungeonGridRules.Cell> verticalTwo = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-200, -168));
		List<DungeonGridRules.Cell> horizontalThree = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-168, -200),
			DungeonGridRules.cellAtOrigin(-136, -200));
		List<DungeonGridRules.Cell> verticalThree = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-200, -168),
			DungeonGridRules.cellAtOrigin(-200, -136));
		List<DungeonGridRules.Cell> square = List.of(
			DungeonGridRules.cellAtOrigin(-200, -200), DungeonGridRules.cellAtOrigin(-168, -200),
			DungeonGridRules.cellAtOrigin(-200, -168), DungeonGridRules.cellAtOrigin(-168, -168));
		List<DungeonGridRules.Cell> lShape = List.of(square.get(0), square.get(1), square.get(2));
		equals(8, DungeonRoomOrientationRules.candidates(horizontalTwo, "1x2", 80).size(), "horizontal 1x2 accepts both cells");
		equals(8, DungeonRoomOrientationRules.candidates(verticalTwo, "1x2", 80).size(), "vertical 1x2 accepts both cells");
		equals(12, DungeonRoomOrientationRules.candidates(horizontalThree, "1x3", 80).size(), "1x3 accepts all three cells");
		equals(12, DungeonRoomOrientationRules.candidates(verticalThree, "1x3", 80).size(), "vertical 1x3 accepts all three cells");
		equals(16, DungeonRoomOrientationRules.candidates(square, "2x2", 80).size(), "2x2 accepts all four cells");
		equals(12, DungeonRoomOrientationRules.candidates(lShape, "L", 80).size(), "L accepts its three component cells");
		equals(DungeonRoomOrientationRules.candidates(horizontalThree, "1x3", 80),
			DungeonRoomOrientationRules.candidates(List.of(horizontalThree.get(2), horizontalThree.get(0), horizontalThree.get(1)), "1x3", 80),
			"input cell order does not change deterministic corner order");
		equals(DungeonRoomOrientationRules.candidates(vertical, "1x4", 84),
			DungeonRoomOrientationRules.candidates(List.of(vertical.get(3), vertical.get(1), vertical.get(2), vertical.get(0)), "1x4", 84),
			"component-Z sorting is deterministic for vertical rooms");

		isEmpty(DungeonRoomOrientationRules.candidates(null, "1x1", 80), "null cell lists fail closed");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(oneByOne), null, 80), "null shape fails closed");
		equals(4, DungeonRoomOrientationRules.candidates(List.of(oneByOne), "1x1", 0).size(), "marker at Y=0 is accepted");
		equals(4, DungeonRoomOrientationRules.candidates(List.of(oneByOne), "1x1", 255).size(), "marker at Y=255 is accepted");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(oneByOne), "1x1", -1), "marker below build height fails closed");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(oneByOne), "1x1", 256), "marker above build height fails closed");
		isEmpty(DungeonRoomOrientationRules.candidates(java.util.Collections.singletonList(null), "1x1", 80),
			"a lone null cell fails closed after sorting");
		throwsType(NullPointerException.class,
			() -> DungeonRoomOrientationRules.candidates(java.util.Arrays.asList(null, oneByOne), "1x1", 80),
			"sorting a multi-cell list containing null preserves its exception");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(new DungeonGridRules.Cell(1, 0, -200, -200)), "1x1", 80),
			"component coordinates must match the room origin");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(new DungeonGridRules.Cell(0, 0, -201, -200)), "1x1", 80),
			"cell origins must align with the grid");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(new DungeonGridRules.Cell(-1, 0, -200, -200)), "1x1", 80),
			"component coordinates must stay within the six-cell grid");
		isEmpty(DungeonRoomOrientationRules.candidates(List.of(oneByOne, oneByOne), "1x2", 80),
			"duplicate component coordinates fail closed");
		throwsType(UnsupportedOperationException.class,
			() -> candidates.add(new DungeonRoomOrientationRules.Candidate(0, 0, 80, 0)),
			"candidate results remain immutable");

		isNull(DungeonRoomOrientationRules.uniqueMarkedCandidate(null, candidate -> true), "null candidate list fails closed");
		isNull(DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates, null), "null marker predicate fails closed");
		equals(candidates.get(1), DungeonRoomOrientationRules.uniqueMarkedCandidate(
			java.util.Arrays.asList(null, candidates.get(1)), candidate -> true), "null candidate entries are ignored");
		IllegalStateException markerFailure = new IllegalStateException("marker lookup failed");
		throwsSame(markerFailure,
			() -> DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates, candidate -> { throw markerFailure; }),
			"marker predicate exceptions propagate unchanged");

		isNull(DungeonRoomOrientationRules.toWorld(null, 1, 2, 3), "null orientation fails closed");
		isNull(DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(4, 0, 0, 0), 1, 2, 3),
			"unsupported rotation fails closed");
		isNull(DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(0, Integer.MAX_VALUE, 0, 0), 1, 2, 3),
			"world X overflow fails closed");
		isNull(DungeonRoomOrientationRules.toWorld(new DungeonRoomOrientationRules.Candidate(0, 0, 0, Integer.MAX_VALUE), 1, 2, 1),
			"world Z overflow fails closed");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
	private static void isNull(Object value, String message) {
		if (value != null) throw new AssertionError(message + ": got " + value);
	}
	private static void isEmpty(List<?> values, String message) {
		if (values == null || !values.isEmpty()) throw new AssertionError(message + ": got " + values);
	}
	private static void throwsType(Class<? extends Throwable> expectedType, Runnable action, String message) {
		try {
			action.run();
		} catch (Throwable actual) {
			if (expectedType.isInstance(actual)) return;
			throw new AssertionError(message + ": expected " + expectedType.getName() + ", got " + actual, actual);
		}
		throw new AssertionError(message + ": expected " + expectedType.getName());
	}
	private static void throwsSame(Throwable expected, Runnable action, String message) {
		try {
			action.run();
		} catch (Throwable actual) {
			if (actual == expected) return;
			throw new AssertionError(message + ": expected same exception instance, got " + actual, actual);
		}
		throw new AssertionError(message + ": expected exception " + expected);
	}
}
