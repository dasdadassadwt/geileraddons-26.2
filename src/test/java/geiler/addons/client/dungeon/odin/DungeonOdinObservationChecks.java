package geiler.addons.client.dungeon.odin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure acceptance checks for client-observed Odin puzzle extraction; not wired to OfflineChecks. */
public final class DungeonOdinObservationChecks {
	private DungeonOdinObservationChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		javaFacadePreservesObserverAbi();
		observerValueWrappersRetainConstructorsAndNormalization();
		kotlinCoreKeepsClientlessCallsSafe();
		icePathSolverPreservesRouteAndJavaRecords();
		iceFillExtractionRejectsAmbiguity();
		threeWeirdosChatRequiresAConfirmedSpeaker();
		threeWeirdosClueTruthClassificationIsPreserved();
	}

	private static void icePathSolverPreservesRouteAndJavaRecords() {
		check(DungeonIcePathSolver.BOARD_SIZE == 17, "the solver keeps its public board-size constant");
		check(!DungeonIcePathSolver.validBoard(null)
			&& !DungeonIcePathSolver.validBoard(new boolean[16][17])
			&& !DungeonIcePathSolver.validBoard(new boolean[17][16])
			&& !DungeonIcePathSolver.validBoard(new boolean[17][]),
			"null, wrong-sized, and ragged boards remain invalid");

		boolean[][] blocks = new boolean[17][17];
		for (boolean[] row : blocks) java.util.Arrays.fill(row, true);
		for (int row = 0; row <= 8; row++) blocks[row][2] = false;
		for (int column = 2; column <= 8; column++) {
			blocks[8][column] = false;
			blocks[0][column] = false;
		}
		for (int row = 0; row <= 8; row++) blocks[row][8] = false;
		List<DungeonIcePathSolver.Cell> expected = List.of(new DungeonIcePathSolver.Cell(8, 2),
			new DungeonIcePathSolver.Cell(0, 2), new DungeonIcePathSolver.Cell(0, 8));
		List<DungeonIcePathSolver.Cell> route = DungeonIcePathSolver.solve(blocks,
			new DungeonIcePathSolver.Cell(8, 2));
		check(route.equals(expected), "the shortest two-slide route keeps north-before-east tie breaking");
		try {
			route.add(new DungeonIcePathSolver.Cell(1, 1));
			throw new AssertionError("the returned route must be immutable");
		} catch (UnsupportedOperationException expectedException) { }
		check(DungeonIcePathSolver.solve(null, new DungeonIcePathSolver.Cell(0, 8)).isEmpty()
			&& DungeonIcePathSolver.solve(blocks, null).isEmpty()
			&& DungeonIcePathSolver.solve(blocks, new DungeonIcePathSolver.Cell(-1, 8)).isEmpty(),
			"invalid board and start inputs remain safe empty results");
		blocks[8][2] = true;
		check(DungeonIcePathSolver.solve(blocks, new DungeonIcePathSolver.Cell(8, 2)).isEmpty(),
			"a blocked starting cell remains an empty result");

		DungeonIcePathSolver.Cell firstCell = new DungeonIcePathSolver.Cell(3, 4);
		DungeonIcePathSolver.Cell equalCell = new DungeonIcePathSolver.Cell(3, 4);
		DungeonIcePathSolver.Point firstPoint = new DungeonIcePathSolver.Point(1.25, 2.0, -3.5);
		check(DungeonIcePathSolver.Cell.class.isRecord() && firstCell.row() == 3 && firstCell.column() == 4
			&& firstCell.equals(equalCell) && firstCell.hashCode() == equalCell.hashCode(),
			"Cell remains a public Java record with component accessors and value semantics");
		check(DungeonIcePathSolver.Point.class.isRecord() && firstPoint.x() == 1.25 && firstPoint.y() == 2.0
			&& firstPoint.z() == -3.5 && firstPoint.equals(new DungeonIcePathSolver.Point(1.25, 2.0, -3.5)),
			"Point remains a public Java record with component accessors and value semantics");
		try {
			check(Modifier.isPublic(DungeonIcePathSolver.Cell.class.getDeclaredConstructor(int.class, int.class)
				.getModifiers()), "Cell keeps its public two-int canonical constructor");
			check(Modifier.isPublic(DungeonIcePathSolver.Point.class.getDeclaredConstructor(
				double.class, double.class, double.class).getModifiers()),
				"Point keeps its public three-double canonical constructor");
			check(Modifier.isStatic(DungeonIcePathSolver.class.getMethod("solve", boolean[][].class,
				DungeonIcePathSolver.Cell.class).getModifiers())
				&& Modifier.isStatic(DungeonIcePathSolver.class.getMethod("validBoard", boolean[][].class).getModifiers()),
				"the solver entry points remain Java static methods");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("a Java-facing Ice Path solver signature was removed", exception);
		}
	}

	private static void javaFacadePreservesObserverAbi() {
		Method[] declared = DungeonOdinPuzzleObserver.class.getDeclaredMethods();
		Set<String> methodNames = new HashSet<>();
		int publicMethodCount = 0;
		int observeOverloadCount = 0;
		for (Method method : declared) {
			if (!Modifier.isPublic(method.getModifiers())) continue;
			publicMethodCount++;
			methodNames.add(method.getName());
			if (method.getName().equals("observe")) observeOverloadCount++;
		}
		check(publicMethodCount == 39 && observeOverloadCount == 6,
			"all 39 Java-facing methods and six observe overloads remain on the facade");
		check(methodNames.containsAll(Set.of("onChat", "visualSnapshot", "currentPuzzleRoomDisplayName",
			"currentPuzzleRoomIdentity", "currentPuzzleRoomMapIdentity", "tpMazeDiagnosticSummary",
			"ticTacToeVisualTargets", "ticTacToeDiagnosticSummary", "tickQuizTimer", "quizTimerSnapshot",
			"quizDiagnosticSummary", "styleQuizChatOption", "boulderDiagnosticSummary", "waterBoardDiagnosticSummary",
			"sourceRoomRelativePosition", "parseExplicitNpcClue", "reset", "resetSolver",
			"clearCreeperBeamEndpointConfirmation", "isConfirmedCreeperBeamsEndpoint",
			"onCreeperBeamBlockUpdate", "observeTpMazeCorrection", "observeBoulderClick",
			"observeWaterBoardClick", "boulderCurrentTarget", "boulderTargets", "tpMazeRenderSegments",
			"icePathDiagnosticSummary", "icePathRenderPath", "iceRenderPath", "tpMazePadVisuals",
			"planIceFill")), "the facade retains the complete legacy method-name set");
		try {
			check(Modifier.isStatic(DungeonOdinPuzzleObserver.class
				.getMethod("parseExplicitNpcClue", String.class, Set.class).getModifiers()),
				"the explicit NPC clue parser remains static");
			check(Modifier.isStatic(DungeonOdinPuzzleObserver.class
				.getMethod("planIceFill", Set.class, DungeonPuzzleLogic.Cell.class, int.class).getModifiers()),
				"the Ice Fill planner remains static");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("a public observer utility signature was removed", exception);
		}
		check(DungeonOdinPuzzleObserver.IceFillStrategy.fromSetting("HARD")
			== DungeonOdinPuzzleObserver.IceFillStrategy.HARD
			&& DungeonOdinPuzzleObserver.ScanStatus.COMPLETE.id().equals("complete"),
			"the nested enum helper methods remain Java-visible");
		Class<?>[] kotlinValueWrappers = {
			DungeonOdinPuzzleObserver.Guidance.class,
			DungeonOdinPuzzleObserver.WorldBlock.class,
			DungeonOdinPuzzleObserver.TicTacToeVisualTargets.class,
			DungeonOdinPuzzleObserver.WaterBoardLabel.class,
			DungeonOdinPuzzleObserver.WaterBoardVisual.class,
			DungeonOdinPuzzleObserver.CreeperPairVisual.class,
			DungeonOdinPuzzleObserver.WeirdoNpcVisual.class,
			DungeonOdinPuzzleObserver.QuizOptionVisual.class,
			DungeonOdinPuzzleObserver.QuizTimerSnapshot.class,
			DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.class,
			DungeonOdinPuzzleObserver.IceRenderPath.class,
			DungeonOdinPuzzleObserver.IcePathRenderPath.class,
			DungeonOdinPuzzleObserver.IcePlan.class,
			DungeonOdinPuzzleObserver.ClueLine.class,
			DungeonOdinPuzzleObserver.TpMazePadVisual.class
		};
		check(java.util.Arrays.stream(kotlinValueWrappers).noneMatch(Class::isRecord),
			"Kotlin value wrappers preserve constructor behavior and accessor ABI; native record metadata is the documented tradeoff");
		try {
			check(hasPublicConstructor(DungeonOdinPuzzleObserver.Guidance.class,
				String.class, List.class, DungeonOdinPuzzleObserver.ScanStatus.class, String.class)
				&& hasPublicConstructor(DungeonOdinPuzzleObserver.Guidance.class, String.class, List.class)
				&& hasPublicConstructor(DungeonOdinPuzzleObserver.WaterBoardVisual.class, List.class, List.class, boolean.class)
				&& hasPublicConstructor(DungeonOdinPuzzleObserver.WaterBoardVisual.class, List.class, boolean.class)
				&& hasPublicConstructor(DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.class,
					DungeonOdinPuzzleObserver.WaterBoardVisual.class, List.class, List.class, List.class)
				&& DungeonOdinPuzzleObserver.Guidance.class.getMethod("lines").getReturnType() == List.class
				&& DungeonOdinPuzzleObserver.WaterBoardVisual.class.getMethod("routeConfirmed").getReturnType() == boolean.class
				&& DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.class.getMethod("waterBoard").getReturnType()
					== DungeonOdinPuzzleObserver.WaterBoardVisual.class,
				"observer value wrappers retain Java constructors, overloads, and component accessor descriptors");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("an observer value-wrapper constructor or accessor changed", exception);
		}
	}

	private static boolean hasPublicConstructor(Class<?> type, Class<?>... parameterTypes) {
		try {
			return Modifier.isPublic(type.getConstructor(parameterTypes).getModifiers());
		} catch (NoSuchMethodException exception) {
			return false;
		}
	}

	private static void observerValueWrappersRetainConstructorsAndNormalization() {
		var fallback = new DungeonOdinPuzzleObserver.Guidance(null, null, null, null);
		check(fallback.name().equals("Puzzle") && fallback.lines().isEmpty()
			&& fallback.scanStatus() == DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE
			&& fallback.scanReason().equals("No scan reason was provided."),
			"Guidance keeps its compact-constructor defaults and null-list handling");
		var shortForm = new DungeonOdinPuzzleObserver.Guidance("Quiz", List.of("Observed"));
		check(shortForm.scanStatus() == DungeonOdinPuzzleObserver.ScanStatus.COMPLETE
			&& shortForm.scanReason().equals("Confirmed observations produced puzzle guidance."),
			"Guidance retains its two-argument convenience constructor");
		var water = new DungeonOdinPuzzleObserver.WaterBoardVisual(null, true);
		check(water.orderedUpcomingLevers().isEmpty() && water.scheduleLabels().isEmpty() && water.routeConfirmed(),
			"Water Board visual keeps both null-list defaults and the two-argument constructor");
		try {
			water.orderedUpcomingLevers().add(new DungeonOdinPuzzleObserver.WorldBlock(1, 2, 3));
			throw new AssertionError("Water Board visual lists must remain immutable snapshots");
		} catch (UnsupportedOperationException expectedException) { }
		var timer = new DungeonOdinPuzzleObserver.QuizTimerSnapshot(-1, -5, 8);
		check(timer.remainingTicks() == 0 && timer.maximumTicks() == 0 && timer.stage() == 3,
			"Quiz timer values retain compact-constructor clamps");
		var npc = new DungeonOdinPuzzleObserver.WeirdoNpcVisual(null, null, true);
		var option = new DungeonOdinPuzzleObserver.QuizOptionVisual(null, null, null, false);
		check(npc.name().isEmpty() && option.marker().isEmpty() && option.answer().isEmpty(),
			"Weirdos and Quiz records retain their null-string defaults");
		var emptySnapshot = new DungeonOdinPuzzleObserver.PuzzleVisualSnapshot(null, null, null, null);
		check(emptySnapshot.waterBoard().equals(DungeonOdinPuzzleObserver.WaterBoardVisual.empty())
			&& emptySnapshot.creeperPairs().isEmpty() && emptySnapshot.weirdos().isEmpty()
			&& emptySnapshot.quizOptions().isEmpty(),
			"visual snapshots retain empty defaults for missing components");
		var first = new DungeonOdinPuzzleObserver.WorldBlock(4, 5, 6);
		var equal = new DungeonOdinPuzzleObserver.WorldBlock(4, 5, 6);
		check(first.equals(equal) && first.hashCode() == equal.hashCode()
			&& first.toString().equals("WorldBlock[x=4, y=5, z=6]"),
			"Kotlin observer values retain record-style equality, hashing, and display");
	}

	private static void kotlinCoreKeepsClientlessCallsSafe() {
		DungeonOdinPuzzleObserver observer = new DungeonOdinPuzzleObserver();
		check(observer.observe(null, 0L, "AUTO", null) == null,
			"automatic observation exits cleanly when Minecraft is unavailable");
		check(!observer.onChat(null, "Alice: The reward is in my chest.", true, true),
			"chat observation rejects calls without a client instance");
		observer.reset();
	}

	private static void iceFillExtractionRejectsAmbiguity() {
		String missingFootprint = "Observed ice footprint is missing, too large, or does not contain the player.";
		List<DungeonPuzzleLogic.Cell> expectedCorridor = List.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(2, 0), new DungeonPuzzleLogic.Cell(3, 0));
		Set<DungeonPuzzleLogic.Cell> corridor = new LinkedHashSet<>(List.of(expectedCorridor.get(3),
			expectedCorridor.get(2), expectedCorridor.get(1), expectedCorridor.get(0)));
		DungeonOdinPuzzleObserver.IcePlan route = DungeonOdinPuzzleObserver.planIceFill(corridor,
			new DungeonPuzzleLogic.Cell(0, 0), 10_000);
		check(route.route().equals(expectedCorridor) && route.reason().isEmpty(),
			"an unordered observed corridor produces the same start-to-goal route ordering");
		check(DungeonOdinPuzzleObserver.planIceFill(null, null, 10_000).reason().equals(missingFootprint),
			"null footprints and players preserve the missing-footprint result");
		check(DungeonOdinPuzzleObserver.planIceFill(Set.of(new DungeonPuzzleLogic.Cell(0, 0)),
			new DungeonPuzzleLogic.Cell(0, 0), 10_000).reason().equals(missingFootprint),
			"a one-cell footprint preserves the minimum-size rejection");
		check(DungeonOdinPuzzleObserver.planIceFill(Set.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0)), new DungeonPuzzleLogic.Cell(2, 0), 10_000).reason()
			.equals(missingFootprint), "a player outside the observed footprint preserves the missing-footprint rejection");

		Set<DungeonPuzzleLogic.Cell> disconnected = Set.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(3, 0), new DungeonPuzzleLogic.Cell(4, 0));
		check(DungeonOdinPuzzleObserver.planIceFill(disconnected, new DungeonPuzzleLogic.Cell(0, 0), 10_000)
			.reason().equals("Observed ice tiles form multiple components; start and goal are ambiguous."),
			"disconnected observed ice components keep the explicit ambiguity result");
		Set<DungeonPuzzleLogic.Cell> footprintWithNull = new HashSet<>();
		footprintWithNull.add(new DungeonPuzzleLogic.Cell(0, 0));
		footprintWithNull.add(new DungeonPuzzleLogic.Cell(1, 0));
		footprintWithNull.add(null);
		check(DungeonOdinPuzzleObserver.planIceFill(footprintWithNull, new DungeonPuzzleLogic.Cell(0, 0), 10_000)
			.reason().equals("Observed ice tiles form multiple components; start and goal are ambiguous."),
			"a null member remains disconnected from the visited observed cells");

		Set<DungeonPuzzleLogic.Cell> branch = Set.of(new DungeonPuzzleLogic.Cell(1, 0),
			new DungeonPuzzleLogic.Cell(0, 1), new DungeonPuzzleLogic.Cell(1, 1),
			new DungeonPuzzleLogic.Cell(2, 1), new DungeonPuzzleLogic.Cell(1, 2));
		check(DungeonOdinPuzzleObserver.planIceFill(branch, new DungeonPuzzleLogic.Cell(1, 0), 10_000)
			.reason().equals("Observed ice layout is not a single unambiguous endpoint-to-endpoint path."),
			"a branched layout without a unique endpoint pair keeps the explicit ambiguity result");

		Set<DungeonPuzzleLogic.Cell> loop = Set.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(0, 1), new DungeonPuzzleLogic.Cell(1, 1));
		check(DungeonOdinPuzzleObserver.planIceFill(loop, new DungeonPuzzleLogic.Cell(0, 0), 10_000).reason()
			.equals("Observed ice layout is not a single unambiguous endpoint-to-endpoint path."),
			"a closed loop without endpoints keeps the explicit ambiguity result");
		check(DungeonOdinPuzzleObserver.planIceFill(corridor, new DungeonPuzzleLogic.Cell(0, 0), 0).reason()
			.equals("The observed tiles have no complete path within the planner's search budget."),
			"a planner exhausted by its bounded node budget keeps the search-budget result");
		Set<DungeonPuzzleLogic.Cell> maximumFootprint = new HashSet<>();
		for (int x = 0; x < 22; x++) maximumFootprint.add(new DungeonPuzzleLogic.Cell(x, 0));
		DungeonOdinPuzzleObserver.IcePlan maximumRoute = DungeonOdinPuzzleObserver.planIceFill(maximumFootprint,
			new DungeonPuzzleLogic.Cell(0, 0), 10_000);
		check(maximumRoute.route().size() == 22 && maximumRoute.route().getLast().equals(new DungeonPuzzleLogic.Cell(21, 0))
			&& maximumRoute.reason().isEmpty(), "the 22-cell maximum remains inclusive with deterministic output order");

		Set<DungeonPuzzleLogic.Cell> tooLarge = new java.util.HashSet<>();
		for (int x = 0; x < 24; x++) tooLarge.add(new DungeonPuzzleLogic.Cell(x, 0));
		check(DungeonOdinPuzzleObserver.planIceFill(tooLarge, new DungeonPuzzleLogic.Cell(0, 0), 10_000)
			.reason().equals(missingFootprint), "the observed board size remains within the bounded planner limit");
	}

	private static void threeWeirdosChatRequiresAConfirmedSpeaker() {
		var accepted = DungeonOdinPuzzleObserver.parseExplicitNpcClue("[NPC] Alice: The reward is in my chest.",
			Set.of("Alice"));
		check(accepted != null && accepted.speaker().equals("Alice")
			&& accepted.statement().equals("The reward is in my chest."),
			"explicit NPC clue chat is captured when that name is present in the room observation");
		var formatted = DungeonOdinPuzzleObserver.parseExplicitNpcClue(
			"[npc] \u00a7aalICE\u00a7r: The reward is in my chest. ", Set.of("Alice"));
		check(formatted != null && formatted.speaker().equals("Alice")
			&& formatted.statement().equals("The reward is in my chest."),
			"format codes and speaker case normalize while the confirmed room name and plain statement are returned");
		String maximumSpeaker = "A".repeat(64);
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue(maximumSpeaker + ": The reward is in my chest.",
			Set.of(maximumSpeaker)) != null, "the explicit speaker limit includes a 64-character name");
		String overlongSpeaker = "A".repeat(65);
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue(overlongSpeaker + ": The reward is in my chest.",
			Set.of(overlongSpeaker)) == null, "the explicit speaker limit rejects names longer than 64 characters");
		Set<String> duplicateCaseNames = new LinkedHashSet<>(List.of("alice", "ALICE"));
		var firstCanonical = DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: The reward is in my chest.",
			duplicateCaseNames);
		check(firstCanonical != null && firstCanonical.speaker().equals("alice"),
			"the first matching confirmed name remains the canonical speaker");
		check("Alice".equals(DungeonOdinPuzzleObserverLogic.explicitNpcSpeaker("[NPC] Alice: ordinary chat")),
			"the event-side speaker extractor retains explicit speakers before clue classification");
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue(null, Set.of("Alice")) == null
			&& DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: The reward is in my chest.", Set.of()) == null,
			"null messages and empty confirmation sets are ignored");
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: The reward is in my chest.", Set.of("Bob")) == null,
			"unconfirmed speakers cannot contribute puzzle clues");
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue(
			"Alice: The reward is in my chest, and Bob is lying.", Set.of("Alice")) == null,
			"compound statements outside the deliberately supported grammar are not partially interpreted");
		List<String> pinnedCompoundClues = List.of(
			"At least one of them is lying, and the reward is not in Bob's chest.",
			"My chest doesn't have the reward. We are all telling the truth.",
			"My chest has the reward and I'm telling the truth!",
			"Both of them are telling the truth. Also, Bob has the reward in their chest.",
			"They are both telling the truth. The reward isn't in Bob's chest.",
			"Bob is telling the truth and the reward is in his chest.",
			"My chest doesn't have the reward. At least one of the others is telling the truth!",
			"They are both telling the truth, the reward is in Bob's chest.",
			"They are both lying, the reward is in my chest!",
			"The reward is not in my chest. They are both lying.");
		for (String statement : pinnedCompoundClues) {
			check(DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: " + statement, Set.of("Alice")) != null,
				"a complete compound clue documented by the pinned Odin solver is accepted: " + statement);
		}
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: I like this room.", Set.of("Alice")) == null,
			"ordinary NPC chat without a clue term is ignored");
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue("Alice: the reward is in my chest.", Set.of("Alice")) == null,
			"clue statement matching remains case-sensitive");
		check(DungeonOdinPuzzleObserver.parseExplicitNpcClue("The reward is in my chest.", Set.of("Alice")) == null,
			"clues without an explicit speaker are not attributed by inference");
	}

	private static void threeWeirdosClueTruthClassificationIsPreserved() {
		List<String> correctClues = List.of(
			"The reward is not in my chest!",
			"At least one of them is lying, and the reward is not in Bob's chest.",
			"My chest doesn't have the reward. We are all telling the truth.",
			"My chest has the reward and I'm telling the truth!",
			"The reward isn't in any of our chests.",
			"Both of them are telling the truth. Also, Bob has the reward in their chest.");
		for (String statement : correctClues) {
			check(Boolean.TRUE.equals(DungeonOdinPuzzleObserverLogic.parseWeirdosClaim(statement)),
				"the original correct-clue grammar keeps its true classification: " + statement);
		}
		List<String> wrongClues = List.of(
			"One of us is telling the truth!",
			"They are both telling the truth. The reward isn't in Bob's chest.",
			"We are all telling the truth!",
			"Bob is telling the truth and the reward is in his chest.",
			"My chest doesn't have the reward. At least one of the others is telling the truth!",
			"One of the others is lying.",
			"They are both telling the truth, the reward is in Bob's chest.",
			"They are both lying, the reward is in my chest!",
			"The reward is in my chest.",
			"The reward is not in my chest. They are both lying.",
			"Bob is telling the truth.",
			"My chest has the reward.");
		for (String statement : wrongClues) {
			check(Boolean.FALSE.equals(DungeonOdinPuzzleObserverLogic.parseWeirdosClaim(statement)),
				"the original wrong-clue grammar keeps its false classification: " + statement);
		}
		check(DungeonOdinPuzzleObserverLogic.parseWeirdosClaim("The reward is in my chest, and Bob is lying.") == null,
			"a clue outside the full-statement grammar remains unsupported");
		check(DungeonOdinPuzzleObserverLogic.parseWeirdosClaim(null) == null,
			"null clue statements remain unsupported");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
