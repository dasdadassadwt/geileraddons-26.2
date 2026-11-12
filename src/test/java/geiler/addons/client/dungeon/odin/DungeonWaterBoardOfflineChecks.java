package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonGridRules;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Modifier;
import java.util.function.IntPredicate;

/** Standalone pure checks for Water Board route selection and fail-closed probe resolution. */
public final class DungeonWaterBoardOfflineChecks {
	private DungeonWaterBoardOfflineChecks() { }

	public static void run() {
		checkCatalogChoice();
		checkRotationResolution();
		checkUnknownInputs();
		checkDataOnlyPlannerSurface();
		checkPlannerFacadeCompatibility();
	}

	private static void checkCatalogChoice() {
		DungeonWaterBoardCatalog.CatalogResult catalog = DungeonWaterBoardCatalog.bundled();
		require(catalog.available() && catalog.routes().size() == 80,
			"both strategies expose the full 4-pattern by 10-slot signature catalog");
		List<DungeonWaterBoardCatalog.Click> standard = DungeonWaterBoardCatalog.route(false, 0, "012");
		List<DungeonWaterBoardCatalog.Click> optimized = DungeonWaterBoardCatalog.route(true, 0, "012");
		require(!standard.isEmpty() && !optimized.isEmpty() && !standard.equals(optimized),
			"Standard and Optimized choose their distinct data-driven schedules");
		require(standard.stream().anyMatch(click -> click.leverId().equals("water") && click.seconds() == 0.0)
			&& optimized.stream().anyMatch(click -> click.leverId().equals("water") && click.seconds() == 0.0),
			"each selected route includes the observed water-start action");
	}

	private static void checkRotationResolution() {
		DungeonGridRules.Position[] expected = {
			new DungeonGridRules.Position(14, 27), new DungeonGridRules.Position(27, 16),
			new DungeonGridRules.Position(16, 3), new DungeonGridRules.Position(3, 14)
		};
		for (int rotation = 0; rotation < 4; rotation++)
			require(DungeonGridRules.toWorld(rotation, 14, 27).equals(expected[rotation]),
				"canonical marker transforms through rotation " + rotation);
		List<DungeonWaterBoardRules.Candidate> candidates = new ArrayList<>();
		for (int rotation = 0; rotation < 4; rotation++) candidates.add(new DungeonWaterBoardRules.Candidate(
			rotation, rotation == 2 ? List.of(3) : List.of(), rotation == 2 ? "024" : "", true));
		DungeonWaterBoardRules.Detection detected = DungeonWaterBoardRules.detect(candidates);
		require(detected.known() && detected.rotation() == 2 && detected.patternId() == 3
			&& detected.extendedSlots().equals("024"), "one unique fully loaded rotated signature is accepted");
		DungeonWaterBoardRules.Detection activeOnly = DungeonWaterBoardRules.detect(List.of(
			new DungeonWaterBoardRules.Candidate(2, List.of(3), "024", true)));
		require(activeOnly.known() && activeOnly.rotation() == 2 && activeOnly.patternId() == 3,
			"the known active orientation resolves without reading unused alternatives or lever blocks");
	}

	private static void checkUnknownInputs() {
		List<DungeonWaterBoardRules.Candidate> incomplete = candidates(1);
		require(DungeonWaterBoardRules.detect(incomplete).unknownReason()
			== DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES,
			"one unloaded rotation keeps the route unknown");
		List<DungeonWaterBoardRules.Candidate> ambiguous = candidates(-1);
		ambiguous.set(0, new DungeonWaterBoardRules.Candidate(0, List.of(0), "012", true));
		ambiguous.set(3, new DungeonWaterBoardRules.Candidate(3, List.of(2), "013", true));
		require(DungeonWaterBoardRules.detect(ambiguous).unknownReason()
			== DungeonWaterBoardRules.UnknownReason.AMBIGUOUS_ROTATION,
			"multiple matching orientations do not select a guessed route");
		require(!DungeonWaterBoardRules.validSlots("015") && !DungeonWaterBoardRules.validSlots("011"),
			"out-of-range and duplicate slot ids are rejected");
	}

	private static void checkDataOnlyPlannerSurface() {
		List<DungeonWaterBoardCatalog.Click> route = DungeonWaterBoardCatalog.route(false, 0, "012");
		boolean immutable = false;
		try {
			route.add(new DungeonWaterBoardCatalog.Click("water", 0.0));
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "planner returns immutable display data and exposes no interaction executor");
	}

	private static void checkPlannerFacadeCompatibility() {
		try {
			var standard = DungeonWaterBoardSolver.class.getMethod("standard", int.class, int.class, List.class, int.class);
			var optimized = DungeonWaterBoardSolver.class.getMethod("optimized", int.class, List.class,
				int.class, IntPredicate.class);
			require(Modifier.isPublic(standard.getModifiers()) && Modifier.isStatic(standard.getModifiers())
				&& standard.getReturnType() == List.class,
				"standard keeps its public static Java method descriptor");
			require(Modifier.isPublic(optimized.getModifiers()) && Modifier.isStatic(optimized.getModifiers())
				&& optimized.getReturnType() == List.class,
				"optimized keeps its public static Java method descriptor");
			require(standard.getGenericReturnType().getTypeName().equals("java.util.List<java.lang.Integer>")
				&& standard.getGenericParameterTypes()[2].getTypeName().equals("java.util.List<java.lang.Integer>")
				&& optimized.getGenericReturnType().getTypeName().equals("java.util.List<java.lang.Integer>")
				&& optimized.getGenericParameterTypes()[1].getTypeName().equals("java.util.List<java.lang.Integer>"),
				"planner keeps invariant Java List<Integer> generic signatures");
			require(Modifier.isFinal(DungeonWaterBoardSolver.class.getModifiers())
				&& Modifier.isPrivate(DungeonWaterBoardSolver.class.getDeclaredConstructor().getModifiers()),
				"planner remains a final utility class with a private constructor");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Water Board planner Java ABI changed", exception);
		}

		require(DungeonWaterBoardSolver.standard(0, 1, null, 3).isEmpty(),
			"standard planner preserves null-list fail-closed behavior");
		require(DungeonWaterBoardSolver.optimized(0, null, 3, state -> true).isEmpty(),
			"optimized planner preserves null-list fail-closed behavior");
		require(DungeonWaterBoardSolver.optimized(0, List.of(1), 3, null).isEmpty(),
			"optimized planner preserves null-predicate fail-closed behavior");
	}

	private static List<DungeonWaterBoardRules.Candidate> candidates(int unloadedRotation) {
		List<DungeonWaterBoardRules.Candidate> result = new ArrayList<>();
		for (int rotation = 0; rotation < 4; rotation++) result.add(new DungeonWaterBoardRules.Candidate(
			rotation, List.of(), "", rotation != unloadedRotation));
		return result;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
