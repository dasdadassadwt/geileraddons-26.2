package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonGridRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused offline checks for the licensed display-only Boulder, Ice Fill, TP Maze, and Quiz catalogs. */
public final class DungeonOdinCatalogChecks {
	private DungeonOdinCatalogChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkCatalogsLoadAndStayBounded();
		checkCatalogFacadeValueContracts();
		checkDetectionValueContracts();
		checkIceFillMatchingAndRouteTransform();
		checkIceFillBoundaries();
		checkBoulderMatchingAndTargetTransform();
		checkBoulderProgression();
		checkTpMazeMatchingAndSuggestion();
		checkQuizCoverageAndConflicts();
	}

	private static void checkDetectionValueContracts() {
		checkIceFillDetectionValueContract();
		checkBoulderDetectionValueContract();
	}

	private static void checkIceFillDetectionValueContract() {
		Class<?> type = DungeonIceFillRules.Detection.class;
		require(!type.isRecord(),
			"Ice Fill Detection intentionally has no JVM record metadata so its constructor can snapshot its route");
		try {
			var constructor = type.getConstructor(int.class, int.class, int.class, int.class, List.class,
				DungeonIceFillRules.UnknownReason.class);
			require(java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
				&& type.getMethod("floor").getReturnType() == int.class
				&& type.getMethod("pattern").getReturnType() == int.class
				&& type.getMethod("rotation").getReturnType() == int.class
				&& type.getMethod("floorY").getReturnType() == int.class
				&& type.getMethod("route").getReturnType() == List.class
				&& type.getMethod("unknownReason").getReturnType() == DungeonIceFillRules.UnknownReason.class
				&& type.getMethod("known").getReturnType() == boolean.class,
				"Ice Fill Detection retains its public Java constructor and accessor signatures");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Ice Fill Detection Java value API changed", exception);
		}

		var first = new DungeonIceFillRules.WorldPosition(10, 65, 20);
		var second = new DungeonIceFillRules.WorldPosition(11, 65, 21);
		List<DungeonIceFillRules.WorldPosition> input = new ArrayList<>(List.of(first, second));
		var value = new DungeonIceFillRules.Detection(1, 2, 3, 64, input, null);
		input.clear();
		require(value.floor() == 1 && value.pattern() == 2 && value.rotation() == 3 && value.floorY() == 64
			&& value.route().equals(List.of(first, second)) && value.unknownReason() == null && value.known(),
			"Ice Fill Detection exposes constructor values and snapshots its route");
		boolean immutable = false;
		try {
			value.route().clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "Ice Fill Detection exposes an immutable route");
		var equal = new DungeonIceFillRules.Detection(1, 2, 3, 64, List.of(first, second), null);
		require(value.equals(equal) && equal.equals(value) && value.hashCode() == equal.hashCode()
			&& !value.equals(new DungeonIceFillRules.Detection(1, 2, 3, 64, List.of(first), null)),
			"Ice Fill Detection retains record-style equality and hash codes");
		require(value.toString().equals("Detection[floor=1, pattern=2, rotation=3, floorY=64, "
			+ "route=[WorldPosition[x=10, y=65, z=20], WorldPosition[x=11, y=65, z=21]], unknownReason=null]"),
			"Ice Fill Detection retains Java record-style diagnostic text");
		var unknown = new DungeonIceFillRules.Detection(-1, -1, -1, Integer.MIN_VALUE, null,
			DungeonIceFillRules.UnknownReason.NO_PATTERN_MATCH);
		var incomplete = new DungeonIceFillRules.Detection(1, 2, 3, 64, List.of(first), null);
		require(!unknown.known() && unknown.route().isEmpty()
			&& unknown.unknownReason() == DungeonIceFillRules.UnknownReason.NO_PATTERN_MATCH
			&& !incomplete.known() && !new DungeonIceFillRules.Detection(1, 2, 3, 64, null, null).known(),
			"Ice Fill Detection remains unknown for failure reasons, short routes, and normalized empty routes");
	}

	private static void checkBoulderDetectionValueContract() {
		Class<?> type = DungeonBoulderRules.Detection.class;
		require(!type.isRecord(),
			"Boulder Detection intentionally has no JVM record metadata so its constructor can snapshot its target list");
		try {
			var constructor = type.getConstructor(int.class, String.class, List.class,
				DungeonBoulderRules.UnknownReason.class);
			require(java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
				&& type.getMethod("rotation").getReturnType() == int.class
				&& type.getMethod("signature").getReturnType() == String.class
				&& type.getMethod("targets").getReturnType() == List.class
				&& type.getMethod("unknownReason").getReturnType() == DungeonBoulderRules.UnknownReason.class
				&& type.getMethod("renderTargets").getReturnType() == List.class
				&& type.getMethod("known").getReturnType() == boolean.class,
				"Boulder Detection retains its public Java constructor and accessor signatures");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Boulder Detection Java value API changed", exception);
		}

		var render = new DungeonBoulderRules.WorldPosition(2, 65, 3);
		var click = new DungeonBoulderRules.WorldPosition(5, 65, 6);
		var target = new DungeonBoulderRules.Target(render, click);
		List<DungeonBoulderRules.Target> input = new ArrayList<>(List.of(target));
		var value = new DungeonBoulderRules.Detection(1, "012", input, null);
		input.clear();
		require(value.rotation() == 1 && value.signature().equals("012") && value.targets().equals(List.of(target))
			&& value.unknownReason() == null && value.renderTargets().equals(List.of(render)) && value.known(),
			"Boulder Detection exposes constructor values and snapshots its targets");
		boolean immutable = false;
		try {
			value.targets().clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "Boulder Detection exposes an immutable target list");
		var equal = new DungeonBoulderRules.Detection(1, "012", List.of(target), null);
		require(value.equals(equal) && equal.equals(value) && value.hashCode() == equal.hashCode()
			&& !value.equals(new DungeonBoulderRules.Detection(2, "012", List.of(target), null)),
			"Boulder Detection retains record-style equality and hash codes");
		require(value.toString().equals("Detection[rotation=1, signature=012, targets=[Target[renderPosition="
			+ "WorldPosition[x=2, y=65, z=3], clickPosition=WorldPosition[x=5, y=65, z=6]]], unknownReason=null]"),
			"Boulder Detection retains Java record-style diagnostic text");
		var unknown = new DungeonBoulderRules.Detection(-1, null, null,
			DungeonBoulderRules.UnknownReason.NO_CATALOG_MATCH);
		require(!unknown.known() && unknown.targets().isEmpty()
			&& unknown.unknownReason() == DungeonBoulderRules.UnknownReason.NO_CATALOG_MATCH
			&& !new DungeonBoulderRules.Detection(-1, "012", List.of(target), null).known()
			&& !new DungeonBoulderRules.Detection(1, null, List.of(target), null).known()
			&& !new DungeonBoulderRules.Detection(1, "012", null, null).known(),
			"Boulder Detection remains unknown for failure reasons or missing rotation, signature, and targets");
	}

	private static void checkCatalogsLoadAndStayBounded() {
		var ice = DungeonIceFillCatalog.bundled();
		var boulder = DungeonBoulderCatalog.bundled();
		var maze = DungeonTpMazeCatalog.bundled();
		var quiz = DungeonQuizCatalog.bundled();
		require(ice.available() && ice.identifiers().size() == 3 && ice.easy().size() == 3
			&& ice.hard().size() == 3, "the pinned Ice Fill catalog exposes aligned easy/hard routes for three floors");
		require(boulder.available() && boulder.solutions().size() == 8,
			"the pinned Boulder catalog exposes its eight supported signatures");
		require(maze.available() && maze.pads().size() == 30,
			"the pinned TP Maze catalog exposes all 30 canonical pads");
		require(quiz.available() && quiz.entries().size() == 34,
			"the pinned Quiz catalog exposes all 34 distinct prompt keys");
		require(!DungeonIceFillCatalog.parse("{}").available()
			&& !DungeonBoulderCatalog.parse("{}").available()
			&& !DungeonTpMazeCatalog.parse("[]").available()
			&& !DungeonQuizCatalog.parse("[]").available()
			&& !DungeonIceFillCatalog.parse("x".repeat(32 * 1024 + 1)).available()
			&& !DungeonQuizCatalog.parse("x".repeat(16 * 1024 + 1)).available(),
			"malformed roots and oversized Ice Fill/Quiz inputs are rejected by the bounded catalog parsers");
	}

	private static void checkCatalogFacadeValueContracts() {
		for (Class<?> type : List.of(
			DungeonBoulderCatalog.class,
			DungeonBoulderCatalog.Target.class,
			DungeonBoulderCatalog.CatalogResult.class,
			DungeonBoulderRules.class,
			DungeonBoulderRules.WorldPosition.class,
			DungeonBoulderRules.ObservedBlock.class,
			DungeonBoulderRules.Target.class,
			DungeonBoulderRules.Detection.class,
			DungeonIceFillCatalog.class,
			DungeonIceFillCatalog.Point.class,
			DungeonIceFillCatalog.CatalogResult.class,
			DungeonIceFillRules.class,
			DungeonIceFillRules.WorldPosition.class,
			DungeonIceFillRules.ObservedBlock.class,
			DungeonIceFillRules.Detection.class,
			DungeonQuizCatalog.class,
			DungeonQuizCatalog.Match.class,
			DungeonQuizCatalog.CatalogResult.class)) {
			requireKotlinClass(type);
		}

		var boulderTarget = new DungeonBoulderCatalog.Target(1, 2, 3, 4);
		Class<?> boulderTargetType = DungeonBoulderCatalog.Target.class;
		try {
			require(boulderTargetType.getConstructor(int.class, int.class, int.class, int.class) != null
				&& boulderTargetType.getMethod("renderX").getReturnType() == int.class
				&& boulderTargetType.getMethod("renderZ").getReturnType() == int.class
				&& boulderTargetType.getMethod("clickX").getReturnType() == int.class
				&& boulderTargetType.getMethod("clickZ").getReturnType() == int.class,
				"Boulder Target retains its Java constructor and record-style accessor names as a Kotlin class");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Boulder Target Java value API changed", exception);
		}
		var equalBoulderTarget = new DungeonBoulderCatalog.Target(1, 2, 3, 4);
		var expectedBoulderTarget = new ExpectedBoulderTarget(1, 2, 3, 4);
		require(boulderTarget.equals(equalBoulderTarget) && equalBoulderTarget.equals(boulderTarget)
			&& boulderTarget.hashCode() == expectedBoulderTarget.hashCode()
			&& boulderTarget.toString().equals("Target[renderX=1, renderZ=2, clickX=3, clickZ=4]"),
			"Boulder Target retains record-style value equality, hash, and diagnostic text");
		require(!DungeonBoulderCatalog.CatalogResult.class.isRecord(),
			"Boulder CatalogResult keeps null normalization and map copying in a Kotlin class");
		List<DungeonBoulderCatalog.Target> boulderTargets = new ArrayList<>(List.of(boulderTarget));
		Map<String, List<DungeonBoulderCatalog.Target>> boulderSource = new HashMap<>();
		boulderSource.put("signature", boulderTargets);
		var boulder = new DungeonBoulderCatalog.CatalogResult(true, boulderSource, null);
		boulderSource.clear();
		boulderTargets.add(new DungeonBoulderCatalog.Target(5, 6, 7, 8));
		require(boulder.available() && boulder.reason().isEmpty() && boulder.targets("signature").size() == 2,
			"Boulder CatalogResult snapshots its map, preserves nested list references, and normalizes null reason");
		var boulderProgressTarget = new DungeonBoulderRules.Target(
			new DungeonBoulderRules.WorldPosition(1, 65, 2), new DungeonBoulderRules.WorldPosition(3, 65, 4));
		var boulderSnapshot = new DungeonBoulderProgressTracker.Snapshot(-2, -3, boulderProgressTarget);
		require(!DungeonBoulderProgressTracker.Snapshot.class.isRecord() && boulderSnapshot.completed() == 0
			&& boulderSnapshot.total() == 0 && !boulderSnapshot.known() && !boulderSnapshot.complete()
			&& boulderSnapshot.current().equals(boulderProgressTarget),
			"Boulder progress Snapshot clamps counters while preserving the current rules target");
		var equalBoulderSnapshot = new DungeonBoulderProgressTracker.Snapshot(0, 0, boulderProgressTarget);
		var expectedBoulderSnapshot = new ExpectedBoulderSnapshot(0, 0, boulderProgressTarget);
		require(boulderSnapshot.equals(equalBoulderSnapshot) && boulderSnapshot.hashCode() == expectedBoulderSnapshot.hashCode()
			&& boulderSnapshot.toString().equals("Snapshot[completed=0, total=0, current=Target[renderPosition="
				+ "WorldPosition[x=1, y=65, z=2], clickPosition=WorldPosition[x=3, y=65, z=4]]]"),
			"Boulder Snapshot retains record-style value behavior and diagnostic text");

		var icePoint = new DungeonIceFillCatalog.Point(4, 65, 7);
		Class<?> icePointType = DungeonIceFillCatalog.Point.class;
		try {
			require(icePointType.getConstructor(int.class, int.class, int.class) != null
				&& icePointType.getMethod("x").getReturnType() == int.class
				&& icePointType.getMethod("y").getReturnType() == int.class
				&& icePointType.getMethod("z").getReturnType() == int.class,
				"Ice Fill Point retains its Java constructor and record-style accessor names as a Kotlin class");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Ice Fill Point Java value API changed", exception);
		}
		var equalIcePoint = new DungeonIceFillCatalog.Point(4, 65, 7);
		var expectedIcePoint = new ExpectedIcePoint(4, 65, 7);
		require(icePoint.equals(equalIcePoint) && equalIcePoint.equals(icePoint)
			&& icePoint.hashCode() == expectedIcePoint.hashCode()
			&& icePoint.toString().equals("Point[x=4, y=65, z=7]"),
			"Ice Fill Point retains record-style value equality, hash, and diagnostic text");
		List<List<List<DungeonIceFillCatalog.Point>>> iceIdentifiers = new ArrayList<>();
		List<List<DungeonIceFillCatalog.Point>> iceFloor = new ArrayList<>();
		iceFloor.add(new ArrayList<>(List.of(icePoint)));
		iceIdentifiers.add(iceFloor);
		var ice = new DungeonIceFillCatalog.CatalogResult(true, iceIdentifiers, null, null, null);
		iceIdentifiers.clear();
		require(!DungeonIceFillCatalog.CatalogResult.class.isRecord() && ice.identifiers().size() == 1
			&& ice.easy().isEmpty() && ice.hard().isEmpty() && ice.reason().isEmpty(),
			"Ice Fill CatalogResult copies the top-level lists and normalizes null lists and reason");
		iceFloor.clear();
		require(ice.identifiers().getFirst().isEmpty(),
			"Ice Fill CatalogResult retains the former shallow-copy boundary for nested route lists");
		var iceValue = new DungeonIceFillCatalog.CatalogResult(true, List.of(List.of(List.of(icePoint))),
			List.of(), List.of(), "");
		var expectedIceValue = new ExpectedIceCatalogResult(true, List.of(List.of(List.of(icePoint))),
			List.of(), List.of(), "");
		require(iceValue.hashCode() == expectedIceValue.hashCode()
			&& iceValue.toString().startsWith("CatalogResult[available=true, identifiers=")
			&& iceValue.toString().endsWith(", reason=]"),
			"Ice Fill CatalogResult retains record-style hashing and diagnostic field layout");

		List<String> answers = new ArrayList<>(List.of("answer"));
		var match = new DungeonQuizCatalog.Match("question", answers);
		answers.add("after-copy");
		require(!DungeonQuizCatalog.Match.class.isRecord() && match.answers().equals(List.of("answer")),
			"Quiz Match copies its answer list while retaining source-question and answer accessors");
		var nullMatch = new DungeonQuizCatalog.Match(null, null);
		require(nullMatch.sourceQuestion() == null && nullMatch.answers().isEmpty(),
			"Quiz Match retains null source questions and normalizes null answers to an empty list");
		var equalMatch = new DungeonQuizCatalog.Match("question", List.of("answer"));
		var expectedMatch = new ExpectedQuizMatch("question", List.of("answer"));
		require(match.equals(equalMatch) && match.hashCode() == expectedMatch.hashCode()
			&& match.toString().equals("Match[sourceQuestion=question, answers=[answer]]"),
			"Quiz Match retains record-style equality, hashing, and diagnostic text");

		List<String> quizAnswers = new ArrayList<>(List.of("one"));
		Map<String, List<String>> quizEntries = new HashMap<>();
		quizEntries.put("question", quizAnswers);
		Map<String, String> quizSources = new HashMap<>();
		quizSources.put("question", "Source question");
		var quiz = new DungeonQuizCatalog.CatalogResult(true, quizEntries, quizSources, -2, null);
		quizEntries.clear();
		quizSources.clear();
		quizAnswers.add("two");
		require(!DungeonQuizCatalog.CatalogResult.class.isRecord() && quiz.entries().get("question").size() == 2
			&& quiz.sourceQuestions().get("question").equals("Source question") && quiz.conflictingKeys() == -2
			&& quiz.reason().isEmpty(),
			"Quiz CatalogResult copies maps shallowly, retains the conflict count, and normalizes reason");
		var emptyQuiz = new DungeonQuizCatalog.CatalogResult(false, null, null, 0, null);
		require(emptyQuiz.entries().isEmpty() && emptyQuiz.sourceQuestions().isEmpty() && emptyQuiz.reason().isEmpty(),
			"Quiz CatalogResult normalizes null maps and reason");
	}

	private record ExpectedBoulderSnapshot(int completed, int total, DungeonBoulderRules.Target current) { }

	private record ExpectedBoulderTarget(int renderX, int renderZ, int clickX, int clickZ) { }

	private record ExpectedIcePoint(int x, int y, int z) { }

	private record ExpectedIceCatalogResult(boolean available,
		List<List<List<DungeonIceFillCatalog.Point>>> identifiers,
		List<List<List<DungeonIceFillCatalog.Point>>> easy,
		List<List<List<DungeonIceFillCatalog.Point>>> hard, String reason) { }

	private record ExpectedQuizMatch(String sourceQuestion, List<String> answers) { }

	private static void requireKotlinClass(Class<?> type) {
		require(!type.isRecord() && type.getAnnotation(kotlin.Metadata.class) != null,
			type.getName() + " must retain Kotlin metadata and have no Java record metadata");
	}

	private static void checkIceFillMatchingAndRouteTransform() {
		var catalog = DungeonIceFillCatalog.bundled();
		boolean matched = false;
		int baseX = -168;
		int baseZ = -136;
		for (int floor = 0; floor < catalog.identifiers().size() && !matched; floor++) {
			List<DungeonIceFillRules.WorldPosition> probes = DungeonIceFillRules.probePositions(baseX, baseZ,
				catalog, floor);
			for (int rotation = 0; rotation < 4 && !matched; rotation++) {
				for (int pattern = 0; pattern < catalog.identifiers().get(floor).size() && !matched; pattern++) {
					Map<DungeonIceFillRules.WorldPosition, Boolean> state = new HashMap<>();
					for (var probe : probes) state.put(probe, false);
					var first = catalog.identifiers().get(floor).get(pattern).getFirst();
					state.put(iceWorld(baseX, baseZ, rotation, first), true);
					List<DungeonIceFillRules.ObservedBlock> observations = state.entrySet().stream()
						.map(entry -> new DungeonIceFillRules.ObservedBlock(entry.getKey(), entry.getValue())).toList();
					var detected = DungeonIceFillRules.detect(baseX, baseZ, catalog, floor, observations, true, true);
					if (!detected.known() || detected.floor() != floor || detected.pattern() != pattern
						|| detected.rotation() != rotation) continue;
					int detectedRotation = rotation;
					List<DungeonIceFillCatalog.Point> expected = catalog.route(true, floor, pattern);
					require(detected.route().equals(expected.stream()
						.map(point -> iceWorld(baseX, baseZ, detectedRotation, point)).toList()),
						"Ice Fill returns the selected optimized route in the detected world orientation");
					var easy = DungeonIceFillRules.detect(baseX, baseZ, catalog, floor, observations, true, false);
					require(easy.known() && easy.route().equals(catalog.route(false, floor, pattern).stream()
						.map(point -> iceWorld(baseX, baseZ, detectedRotation, point)).toList()),
						"Ice Fill exposes the easy route for the same confirmed pattern and orientation");
				var switchedToEasy = DungeonIceFillRules.selectRoute(baseX, baseZ, catalog, detected, false);
				var switchedToHard = DungeonIceFillRules.selectRoute(baseX, baseZ, catalog, easy, true);
				require(switchedToEasy.known() && switchedToEasy.route().equals(easy.route())
					&& switchedToHard.known() && switchedToHard.route().equals(detected.route()),
					"Ice Fill can switch route strategies without losing the confirmed pattern or orientation");
				var invalidOrientation = new DungeonIceFillRules.Detection(floor, pattern, 4,
					detected.floorY(), detected.route(), null);
				require(!DungeonIceFillRules.selectRoute(baseX, baseZ, catalog, invalidOrientation, false).known(),
					"Ice Fill rejects a route switch from an impossible orientation");
					var incomplete = DungeonIceFillRules.detect(baseX, baseZ, catalog, floor,
						observations.subList(0, Math.max(0, observations.size() - 1)), false, false);
					require(incomplete.unknownReason() == DungeonIceFillRules.UnknownReason.INCOMPLETE_SCAN,
						"Ice Fill fails closed when its loaded marker scan is incomplete");
					matched = true;
				}
			}
		}
		require(matched, "at least one Ice Fill marker pair resolves a unique catalog pattern and orientation");
	}

	private static void checkIceFillBoundaries() {
		var catalog = DungeonIceFillCatalog.bundled();
		require(DungeonIceFillRules.probePositions(0, 0, catalog, -1).isEmpty()
			&& catalog.route(false, -1, 0).isEmpty()
			&& catalog.route(true, 0, Integer.MAX_VALUE).isEmpty(),
			"Ice Fill rejects out-of-range floors and patterns at the Java facade boundary");
		List<DungeonIceFillRules.WorldPosition> probes = DungeonIceFillRules.probePositions(0, 0, catalog, 0);
		List<DungeonIceFillRules.ObservedBlock> allSolid = probes.stream()
			.map(position -> new DungeonIceFillRules.ObservedBlock(position, false)).toList();
		var noMatch = DungeonIceFillRules.detect(0, 0, catalog, 0, allSolid, true, false);
		require(noMatch.unknownReason() == DungeonIceFillRules.UnknownReason.NO_PATTERN_MATCH,
			"a complete Ice Fill scan with no first-air/second-solid identifier fails closed");
		List<DungeonIceFillRules.ObservedBlock> duplicate = new ArrayList<>(allSolid);
		duplicate.add(allSolid.getFirst());
		var invalid = DungeonIceFillRules.detect(0, 0, catalog, 0, duplicate, true, false);
		require(invalid.unknownReason() == DungeonIceFillRules.UnknownReason.INVALID_OBSERVATION,
			"duplicate Ice Fill observations are rejected as invalid input");
	}

	private static void checkBoulderMatchingAndTargetTransform() {
		var catalog = DungeonBoulderCatalog.bundled();
		int baseX = -168;
		int baseZ = -136;
		List<DungeonBoulderRules.WorldPosition> probes = DungeonBoulderRules.probePositions(baseX, baseZ);
		boolean matched = false;
		for (String signature : catalog.solutions().keySet()) {
			for (int rotation = 0; rotation < 4 && !matched; rotation++) {
				Map<DungeonBoulderRules.WorldPosition, Boolean> state = new HashMap<>();
				for (var probe : probes) state.put(probe, true);
				int index = 0;
				for (int z = 24; z >= 9; z -= 3) for (int x = 24; x >= 6; x -= 3) {
					var position = boulderWorld(baseX, baseZ, rotation, x, 65, z);
					state.put(position, signature.charAt(index++) == '0');
				}
				List<DungeonBoulderRules.ObservedBlock> observations = state.entrySet().stream()
					.map(entry -> new DungeonBoulderRules.ObservedBlock(entry.getKey(), entry.getValue())).toList();
				var detected = DungeonBoulderRules.detect(baseX, baseZ, catalog, observations, true);
				if (!detected.known() || detected.rotation() != rotation || !signature.equals(detected.signature())) continue;
				int detectedRotation = rotation;
				List<DungeonBoulderRules.WorldPosition> expected = catalog.targets(signature).stream()
					.map(target -> boulderWorld(baseX, baseZ, detectedRotation, target.renderX(), 65, target.renderZ())).toList();
				require(detected.renderTargets().equals(expected),
					"Boulder target positions are transformed for display in the detected room orientation");
				List<DungeonBoulderRules.Target> expectedSteps = catalog.targets(signature).stream().map(target ->
					new DungeonBoulderRules.Target(
						boulderWorld(baseX, baseZ, detectedRotation, target.renderX(), 65, target.renderZ()),
						boulderWorld(baseX, baseZ, detectedRotation, target.clickX(), 65, target.clickZ()))).toList();
				require(detected.targets().equals(expectedSteps),
					"Boulder transforms both visible target boxes and local interaction blocks");
				var incomplete = DungeonBoulderRules.detect(baseX, baseZ, catalog,
					observations.subList(0, Math.max(0, observations.size() - 1)), false);
				require(incomplete.unknownReason() == DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN,
					"Boulder suppresses targets when any required sample is not observed");
				matched = true;
			}
			if (matched) break;
		}
		require(matched, "at least one Boulder signature resolves one unique orientation and render target list");
	}

	private static void checkBoulderProgression() {
		require(DungeonBoulderRules.shouldAdvanceInteraction(true, true)
			&& !DungeonBoulderRules.shouldAdvanceInteraction(false, true)
			&& !DungeonBoulderRules.shouldAdvanceInteraction(true, false),
			"Boulder target advancement requires main-hand interaction during dungeon clear");
		require(DungeonBoulderRules.shouldRenderTarget("BOULDER", null)
			&& DungeonBoulderRules.shouldRenderTarget("AUTO", "Boulder")
			&& !DungeonBoulderRules.shouldRenderTarget("AUTO", "Ice Fill")
			&& !DungeonBoulderRules.shouldRenderTarget("ICE_FILL", "Boulder"),
			"Boulder target boxes are eligible only in explicit Boulder mode or AUTO with the Boulder puzzle selected");
		DungeonBoulderRules.WorldPosition render = new DungeonBoulderRules.WorldPosition(1, 65, 1);
		DungeonBoulderRules.WorldPosition firstClick = new DungeonBoulderRules.WorldPosition(4, 65, 4);
		DungeonBoulderRules.WorldPosition repeatedClick = new DungeonBoulderRules.WorldPosition(5, 65, 5);
		List<DungeonBoulderRules.Target> route = List.of(
			new DungeonBoulderRules.Target(render, firstClick),
			new DungeonBoulderRules.Target(render, repeatedClick),
			new DungeonBoulderRules.Target(render, repeatedClick));
		DungeonBoulderProgressTracker tracker = new DungeonBoulderProgressTracker();
		tracker.observe("room-a|signature", route);
		require(tracker.observeClick(repeatedClick) && tracker.snapshot().completed() == 1
			&& tracker.snapshot().current().equals(route.get(0)),
			"Boulder removes the clicked catalog marker even when it is later in display order");
		require(tracker.observeClick(firstClick) && !tracker.observeClick(firstClick)
			&& tracker.snapshot().completed() == 2 && tracker.snapshot().current().equals(route.get(2)),
			"observed click coordinates remove only their matching remaining marker");
		require(tracker.observeClick(repeatedClick) && tracker.snapshot().complete(),
			"repeated coordinates remove the next remaining marker at that block");
		tracker.observe("room-b|signature", route);
		require(tracker.snapshot().completed() == 0 && !tracker.snapshot().complete(),
			"Boulder progress resets when the confirmed room identity changes");
	}

	private static void checkTpMazeMatchingAndSuggestion() {
		var catalog = DungeonTpMazeCatalog.bundled();
		int baseX = -168;
		int baseZ = -136;
		List<DungeonTpMazeRules.WorldPosition> probes = DungeonTpMazeRules.probePositions(baseX, baseZ, catalog);
		boolean matched = false;
		for (int rotation = 0; rotation < 4 && !matched; rotation++) {
			Map<DungeonTpMazeRules.WorldPosition, Boolean> state = new HashMap<>();
			for (var probe : probes) state.put(probe, false);
			for (DungeonTpMazeCatalog.Point point : catalog.pads())
				state.put(mazeWorld(baseX, baseZ, rotation, point), true);
			List<DungeonTpMazeRules.ObservedBlock> observations = state.entrySet().stream()
				.map(entry -> new DungeonTpMazeRules.ObservedBlock(entry.getKey(), entry.getValue())).toList();
			var detected = DungeonTpMazeRules.detect(baseX, baseZ, catalog, observations, true);
			if (!detected.known() || detected.rotation() != rotation) continue;
			var incomplete = DungeonTpMazeRules.detect(baseX, baseZ, catalog,
				observations.subList(0, Math.max(0, observations.size() - 1)), false);
			require(incomplete.unknownReason() == DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN,
				"TP Maze hides catalog pads while any orientation probe is unloaded");
			matched = true;
		}
		require(matched, "a complete TP Maze pad observation identifies one catalog orientation");
		List<DungeonTpMazeRules.Pad> pads = List.of(
			new DungeonTpMazeRules.Pad(1, new DungeonTpMazeRules.WorldPosition(0, 69, 0)),
			new DungeonTpMazeRules.Pad(2, new DungeonTpMazeRules.WorldPosition(0, 69, 5)),
			new DungeonTpMazeRules.Pad(3, new DungeonTpMazeRules.WorldPosition(5, 69, 0)),
			new DungeonTpMazeRules.Pad(4, new DungeonTpMazeRules.WorldPosition(0, 69, 10)));
		require(DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.5, 69.5, 0.5))
			&& !DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.25, 69.5, 0.5))
			&& !DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.5, 70.0, 0.5)),
			"TP Maze processes only Odin's half-block-grid landing positions at Y=69.5");
		var visitedByLanding = DungeonTpMazeRules.padsIntersectingBounds(pads,
			new DungeonTpMazeRules.Bounds(-0.5, 69.5, -0.5, 2.5, 70.5, 2.5));
		require(visitedByLanding.equals(Set.of(1)),
			"TP Maze correction bounds mark every touched portal block as visited");
		require(DungeonTpMazeRules.seedViewCandidates(pads, Set.of()).equals(Set.of(1, 2, 3, 4))
			&& DungeonTpMazeRules.seedViewCandidates(pads, Set.of(2, 3)).equals(Set.of(2, 3)),
			"TP Maze seeds all candidates only when no prior view candidates survive");
		var next = DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(1, 2), -90.0f);
		require(next != null && next.id() == 3,
			"TP Maze suggests only an unvisited pad in the current group using player heading");
		var yawFromCorrection = DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(1), Set.of(), 0.0f,
			new DungeonTpMazeRules.Position(5.5, 70.5, 0.5));
		require(yawFromCorrection != null && yawFromCorrection.id() == 4,
			"TP Maze yaw fallback uses the observed correction position rather than the portal center");
		var viewCandidates = DungeonTpMazeRules.retainViewCandidates(pads, Set.of(2, 3, 4), Set.of(1, 2),
			new DungeonTpMazeRules.Position(1.0, 70.5, 0.5), -90.0f, 0.0f, 32.0);
		require(viewCandidates.equals(Set.of(3))
			&& DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(1, 2), viewCandidates, 0.0f).id() == 3,
			"TP Maze prefers surviving view-ray candidates before applying the yaw fallback");
		require(DungeonTpMazeRules.retainViewCandidates(pads, Set.of(3), Set.of(1, 2), Set.of(3),
			new DungeonTpMazeRules.Position(1.0, 70.5, 0.5), -90.0f, 0.0f, 32.0).isEmpty(),
			"TP Maze excludes portal blocks occupied by the player's padded bounds from candidate colors");
		require(DungeonTpMazeRules.chooseNextPad(pads, 29, Set.of(), 0.0f) == null,
			"the last group has no inferred next target");
	}

	private static void checkQuizCoverageAndConflicts() {
		var catalog = DungeonQuizCatalog.bundled();
		var answers = catalog.match("[STATUE] Oruo: Which of these enemies does not spawn in the Spiders Den?");
		require(answers != null && answers.answers().size() == 6
			&& answers.answers().contains("Dashing Spooder"),
			"Quiz catalog matches a broad prompt and displays all accepted answer strings");
		require(catalog.match("The status of Scarf is being asked: What is the status of Scarf?") != null,
			"Quiz matching tolerates the observed prompt embedded in surrounding text");
		var conflicts = DungeonQuizCatalog.parse("{\"What is Scarf?\":[\"One\"],\"WHAT IS SCARF!\":[\"Two\"]}");
		require(conflicts.available() && conflicts.conflictingKeys() == 1
			&& conflicts.match("What is Scarf?") == null,
			"conflicting normalized Quiz prompts remain unanswered");
		require(DungeonQuizCatalog.parse("{}").entries().isEmpty(), "empty Quiz answer catalogs do not become active");
	}

	private static DungeonIceFillRules.WorldPosition iceWorld(int baseX, int baseZ, int rotation,
		DungeonIceFillCatalog.Point point) {
		DungeonGridRules.Position rotated = DungeonGridRules.toWorld(rotation, point.x(), point.z());
		return new DungeonIceFillRules.WorldPosition(baseX + rotated.x(), point.y(), baseZ + rotated.z());
	}

	private static DungeonBoulderRules.WorldPosition boulderWorld(int baseX, int baseZ, int rotation,
		int x, int y, int z) {
		DungeonGridRules.Position rotated = DungeonGridRules.toWorld(rotation, x, z);
		return new DungeonBoulderRules.WorldPosition(baseX + rotated.x(), y, baseZ + rotated.z());
	}

	private static DungeonTpMazeRules.WorldPosition mazeWorld(int baseX, int baseZ, int rotation,
		DungeonTpMazeCatalog.Point point) {
		DungeonGridRules.Position rotated = DungeonGridRules.toWorld(rotation, point.x(), point.z());
		return new DungeonTpMazeRules.WorldPosition(baseX + rotated.x(), point.y(), baseZ + rotated.z());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
