package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonRoomPlayerProjectionChecks;
import geiler.addons.client.module.impl.DungeonRunSplitsServerTickChecks;
import geiler.addons.client.module.impl.SpiritLeapMenuModule;
import geiler.addons.client.module.impl.SpiritLeapMenuChecks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure acceptance checks for Odin-style dungeon helper rules; invoked by the offline check runner. */
public final class DungeonOdinAcceptanceChecks {
	private DungeonOdinAcceptanceChecks() { }

	public static void run() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		DungeonOdinCatalogChecks.run();
		DungeonWeirdosMarkerCacheChecks.run();
		DungeonQuizChecks.run();
		DungeonOdinBlazeChecks.run();
		DungeonCreeperBeamsOfflineChecks.run();
		DungeonWaterBoardOfflineChecks.run();
		DungeonLividSnapshotGateChecks.run();
		DungeonTicTacToeRulesChecks.run();
		DungeonTicTacToeTargetHoldChecks.run();
		OdinDungeonMapChecks.run();
		DungeonRoomPlayerProjectionChecks.run();
		ThreeWeirdosLogicChecks.run();
		SpiritLeapArrivalChecks.run();
		SpiritLeapIncomingChecks.run();
		checkPuzzlePlanners();
		DungeonSplitPersonalBestChecks.run();
		DungeonRunSplitsServerTickChecks.run();
		DungeonPuzzleTimingTrackerChecks.run();
		checkScoreAndSplits();
		DungeonOdinHelperChecks.run();
		checkSpiritLeapSelection();
		SpiritLeapMenuChecks.run();
		checkTimersAndSoundRules();
		checkLividAndTerminals();
	}

	public static void main(String[] args) { run(); }

	private static void checkPuzzlePlanners() {
		require(DungeonPuzzleLogic.parseBlazeHealth("§8[§7Lv100§8]  §cBlaze §a100/250§c❤") == 250,
			"Blaze parsing reads the maximum-health field from the rendered nameplate");
		List<DungeonPuzzleLogic.BlazeTarget> blazes = List.of(new DungeonPuzzleLogic.BlazeTarget("a", 20),
			new DungeonPuzzleLogic.BlazeTarget("b", 90));
		require(DungeonPuzzleLogic.orderBlazes(blazes, true).getFirst().stableId().equals("b"),
			"Lower Blaze ordering starts at the highest-health target");
		require(DungeonPuzzleLogic.orderBlazes(blazes, false).getFirst().stableId().equals("a"),
			"Higher Blaze ordering starts at the lowest-health target");
		require(DungeonPuzzleLogic.solveLeverSubset(3, 4, state -> state == 7) == 3,
			"lever search returns the clicked subset rather than the resulting board state");
		require(DungeonWaterBoardSolver.standard(0, 5, List.of(1, 4, 2), 3).equals(List.of(0, 1)),
			"standard Water Board search returns the shortest exact-state clicks");
		require(DungeonWaterBoardSolver.optimized(0, List.of(1, 2, 3), 3, state -> state == 3)
			.equals(List.of(2)), "optimized Water Board search chooses a single click that reaches the drain");
		require(DungeonPuzzleLogic.shortestPath(3, 2, Set.of(new DungeonPuzzleLogic.Cell(1, 0)),
			new DungeonPuzzleLogic.Cell(0, 0), new DungeonPuzzleLogic.Cell(2, 0)).size() == 5,
			"grid pathing routes around observed obstacles");
		List<DungeonPuzzleLogic.Cell> iceCells = List.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(0, 1), new DungeonPuzzleLogic.Cell(1, 1));
		List<DungeonPuzzleLogic.Cell> iceRoute = DungeonPuzzleLogic.solveIceFill(iceCells,
			new DungeonPuzzleLogic.Cell(0, 0), new DungeonPuzzleLogic.Cell(1, 0), 1000);
		require(iceRoute.size() == 4 && iceRoute.getLast().equals(new DungeonPuzzleLogic.Cell(1, 0)),
			"Ice Fill path covers every observed cell and ends at the exit");
		Set<DungeonPuzzleLogic.Cell> boulderGrid = Set.of(new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(2, 0), new DungeonPuzzleLogic.Cell(3, 0));
		require(DungeonPuzzleLogic.solveBoulder(boulderGrid, new DungeonPuzzleLogic.Cell(0, 0),
			new DungeonPuzzleLogic.Cell(1, 0), new DungeonPuzzleLogic.Cell(3, 0)).size() == 2,
			"Boulder path yields the two required pushes in a corridor");
		DungeonPuzzleLogic.Portal from = new DungeonPuzzleLogic.Portal(1, 7, 0, 0);
		DungeonPuzzleLogic.Portal toward = new DungeonPuzzleLogic.Portal(2, 7, 0, 3);
		require(DungeonPuzzleLogic.choosePortal(List.of(from, toward), 1, Set.of(), Set.of(2), 0).id() == 2,
			"TP Maze chooses an observed viable portal in the current group");
		require(DungeonPuzzleLogic.pairEndpoints(List.of(1, 2, 3, 4), (first, second) ->
			Math.abs(first - second)).size() == 2, "beam matching pairs all observed endpoints");
		require(ThreeWeirdosLogic.solve(List.of("A", "B", "C"), List.of(
			"The reward is in my chest.", "The reward is in A's chest.", "B is lying.")).isEmpty(),
			"Three Weirdos returns no chest when the parsed clues do not uniquely agree");
	}

	private static void checkScoreAndSplits() {
		DungeonOdinScoreCalculator.TabStats scoreTab = DungeonOdinScoreCalculator.readTabLines(List.of(
			" Secrets Found: 30", " Secrets Found: 75%", " Completed Rooms: 30", "Cleared: 85% (12)",
			"Puzzles: (3)", "Puzzle A: [✔]", "Puzzle B: [✔]", "Puzzle C: [✖]", "Team Deaths: 2", "Crypts: 5",
			"Mimic: Yes", "Prince: Yes", "Bat Killers: 3", "Paul: Yes", "Score: 300"));
		DungeonOdinScoreCalculator.ScoreEstimate score = DungeonOdinScoreCalculator.calculate(
			DungeonFloor.F5, scoreTab, true, true);
		require(scoreTab.secretsFound() == 30 && scoreTab.secretsPercent() == 75.0
			&& scoreTab.completedRooms() == 30 && score.exactScore().orElseThrow() == 287,
			"a sidebar-style Score total cannot override the locally calculated tab-stat score");
		var sidebarOnly = DungeonOdinScoreCalculator.readTabLines(List.of("Score: 300"));
		var unavailable = DungeonOdinScoreCalculator.calculate(DungeonFloor.F5, sidebarOnly, null, false);
		DungeonScoreAlerts sidebarOnlyAlerts = new DungeonScoreAlerts();
		require(!unavailable.available()
			&& unavailable.unknownInputs().contains(DungeonOdinScoreCalculator.Input.SECRETS_FOUND)
			&& unavailable.unknownInputs().contains(DungeonOdinScoreCalculator.Input.DEATHS)
			&& sidebarOnlyAlerts.update(unavailable, 270, 300, true).isEmpty(),
			"a final sidebar total alone leaves core inputs unavailable and cannot trigger score alerts");
		DungeonOdinScoreCalculator.TabStats paulUnknown = new DungeonOdinScoreCalculator.TabStats(
			scoreTab.secretsFound(), scoreTab.secretsPercent(), scoreTab.completedRooms(), scoreTab.clearedPercent(),
			scoreTab.puzzleCount(), scoreTab.completedPuzzles(), scoreTab.observedPuzzleStatusRows(),
			scoreTab.deaths(), scoreTab.crypts(), scoreTab.mimic(),
			scoreTab.prince(), scoreTab.batKillers(), null);
		DungeonOdinScoreCalculator.ScoreEstimate partial = DungeonOdinScoreCalculator.calculate(
			DungeonFloor.F5, paulUnknown, true, true);
		require(partial.minimum() == 277 && partial.maximum() == 287
			&& partial.unknownInputs().contains(DungeonOdinScoreCalculator.Input.PAUL)
			&& partial.exactScore().isEmpty(), "unknown Paul state remains a typed score range, not a fabricated total");
		var incompletePuzzleRows = DungeonOdinScoreCalculator.readTabLines(List.of(
			"Secrets Found: 30", "Secrets Found: 75%", "Completed Rooms: 30", "Cleared: 85% (12)",
			"Puzzles: (3)", "Puzzle A: [✔]", "Puzzle B: [✔]", "Team Deaths: 2", "Crypts: 5",
			"Mimic: Yes", "Prince: Yes", "Bat Killers: 3", "Paul: Yes"));
		var partialPuzzles = DungeonOdinScoreCalculator.calculate(DungeonFloor.F5, incompletePuzzleRows, true, true);
		require(partialPuzzles.minimum() < partialPuzzles.maximum()
			&& partialPuzzles.unknownInputs().contains(DungeonOdinScoreCalculator.Input.PUZZLE_STATUS_ROWS),
			"missing puzzle-status rows widen the score range rather than counting unseen puzzles as incomplete");
		DungeonOdinScoreCalculator.RunSignals teamSignals = new DungeonOdinScoreCalculator.RunSignals();
		require(teamSignals.observeTeamUpdate("The Catacombs (F6) Cleared: 85% (12)"),
			"team-prefix updates capture Odin's floor and cleared percent");
		var teamSnapshot = teamSignals.teamSnapshot();
		var noTabClear = DungeonOdinScoreCalculator.readTabLines(List.of("Puzzles: (0)"));
		require(teamSnapshot.floor() == DungeonFloor.F6 && teamSnapshot.clearedPercent() == 85.0
			&& DungeonOdinScoreCalculator.withTeamSignals(noTabClear, teamSnapshot).clearedPercent() == 85.0,
			"team-prefix floor/clear state supplements missing tab rows");
		var noClearScore = DungeonOdinScoreCalculator.calculate(DungeonFloor.F5,
			new DungeonOdinScoreCalculator.TabStats(scoreTab.secretsFound(), scoreTab.secretsPercent(),
				scoreTab.completedRooms(), null, scoreTab.puzzleCount(), scoreTab.completedPuzzles(),
				scoreTab.observedPuzzleStatusRows(), scoreTab.deaths(), scoreTab.crypts(), scoreTab.mimic(),
				scoreTab.prince(), scoreTab.batKillers(), scoreTab.paul()), true, true);
		require(noClearScore.exactScore().orElseThrow() == 284,
			"missing clear percentage uses Odin's documented 36-room fallback");
		DungeonOdinScoreCalculator.RunSignals signals = new DungeonOdinScoreCalculator.RunSignals();
		signals.beginRun();
		signals.observePartyMessage("Party > [MVP+] ArcherOne: Mimic killed!", DungeonFloor.F6);
		signals.observePartyMessage("Party > [MVP+] TankOne: Prince slain", DungeonFloor.F6);
		signals.observePartyMessage("Party > [MVP+] MageOne: Bat killed", DungeonFloor.F6);
		signals.observePartyMessage("Party > [MVP+] MageOne: Bat killed!", DungeonFloor.F6);
		DungeonOdinScoreCalculator.BonusSnapshot observed = signals.snapshot();
		require(Boolean.TRUE.equals(observed.mimic()) && Boolean.TRUE.equals(observed.prince())
			&& observed.batKillers() == 1 && observed.paul() == null,
			"run-local party signals track Odin bonus events and count each bat killer once");
		DungeonScoreAlerts alerts = new DungeonScoreAlerts();
		var score269 = exactScore(269);
		var score270 = exactScore(270);
		var score301 = exactScore(301);
		require(alerts.update(score269, 270, 300, true).isEmpty()
			&& alerts.update(score270, 270, 300, true).equals(List.of(270)),
			"score alert fires once when crossing 270");
		require(alerts.update(score270, 270, 300, true).isEmpty()
			&& alerts.update(exactScore(300), 270, 300, true).equals(List.of(300)),
			"score alert fires once when crossing 300");
		alerts.reset();
		require(alerts.update(score269, 270, 300, true).isEmpty()
			&& alerts.update(score301, 270, 300, true).equals(List.of(270, 300)),
			"one local update that passes both score thresholds emits each alert once");
		alerts.reset();
		require(alerts.update(score301, 270, 300, true).equals(List.of(270, 300))
			&& alerts.update(exactScore(305), 270, 300, true).isEmpty(),
			"the first enabled score observation fires already-reached thresholds once");
		alerts.reset();
		require(alerts.update(score301, 270, 300, false).isEmpty()
			&& alerts.update(score301, 270, 300, true).equals(List.of(270, 300))
			&& alerts.update(score301, 270, 300, true).isEmpty(),
			"enabling alerts after crossing fires immediately once without repeating");
		DungeonScoreAlerts rangeAlerts = new DungeonScoreAlerts();
		require(rangeAlerts.update(partial, 270, 280, true).equals(List.of(270))
			&& rangeAlerts.update(partial, 270, 280, true).isEmpty()
			&& rangeAlerts.update(exactScore(280), 270, 280, true).equals(List.of(280)),
			"a partial score range alerts only when its lower bound guarantees a threshold, then fires on later certainty");
		DungeonSplitTracker splits = new DungeonSplitTracker();
		require(splits.observe(DungeonFloor.F5, "ENTRY", 1_000_000_000L, 20), "run starts at Entry");
		require(splits.observePhase(DungeonFloor.F5, "BLOOD_OPEN", 4_000_000_000L, 80),
			"observed floor stage advances the split");
		require(splits.snapshot(5_000_000_000L, 100).getFirst().elapsedTicks() == 60,
			"finished entry split preserves client-observed ticks");
		require(splits.observeChat(DungeonFloor.F5,
			"[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.",
			6_000_000_000L, 120), "boss chat advances to the stable Livid phase");
		require(splits.observePhase(DungeonFloor.F5, "COMPLETE", 9_000_000_000L, 180),
			"completion closes the active phase");
		DungeonSplitTracker.Split total = splits.snapshot(9_000_000_000L, 180).getLast();
		require(total.id().equals("COMPLETE") && total.elapsedNanos() == 8_000_000_000L,
			"final split is measured from run entry, not the previous phase");
	}

	private static DungeonOdinScoreCalculator.ScoreEstimate exactScore(int score) {
		return new DungeonOdinScoreCalculator.ScoreEstimate(score, score, Set.of());
	}

	private static void checkSpiritLeapSelection() {
		boolean releaseTimingSettingVisible = SpiritLeapMenuModule.INSTANCE.groups().stream()
			.filter(group -> "Menu Input".equals(group.name()))
			.flatMap(group -> group.settings().stream())
			.anyMatch(setting -> setting.name().equals("Leap on Mouse Release"));
		require(releaseTimingSettingVisible,
			"the Leap on Mouse Release toggle is included in the Spirit Leap Menu input group");
		var sort = SpiritLeapMenuModule.INSTANCE.choiceSettings().stream()
			.filter(setting -> setting.name().equals("Sort Teammates")).findFirst().orElseThrow();
		require(sort.choices().contains("ODIN") && sort.displayValue().equals("Class Layout"),
			"the old serialized sort value remains valid while showing a neutral class-layout label");
		sort.setValue("ODIN");
		require(sort.value().equals("ODIN") && sort.displayValue().equals("Class Layout"),
			"restoring a saved ODIN value preserves behavior without exposing the source name");

		SpiritLeapRoster roster = new SpiritLeapRoster();
		roster.update(List.of(new SpiritLeapRoster.Member("ArcherOne", "Archer", 80, true),
			new SpiritLeapRoster.Member("BerserkOne", "Berserk", 70, true),
			new SpiritLeapRoster.Member("TankOne", "Tank", 90, true),
			new SpiritLeapRoster.Member("HealerOne", "Healer", 95, true)), "Self");
		List<SpiritLeapRoster.Card> cards = roster.cards(SpiritLeapRoster.Sort.ODIN, List.of());
		require(cards.size() == 4 && cards.get(0).member().name().equals("ArcherOne")
			&& cards.get(1).member().name().equals("BerserkOne")
			&& cards.get(2).member().name().equals("HealerOne")
			&& cards.get(3).member().name().equals("TankOne"),
			"Odin sort honors reserved class quadrants and fills the Tank collision into the remaining hole");
		require(SpiritLeapRoster.classColorCode("Mage") == 'b', "class cards expose Odin's configured color code");
		Map<SpiritLeapSelection.Quadrant, String> cornerKeys = Map.of(
			SpiritLeapSelection.Quadrant.TOP_LEFT, "1");
		var target = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "1",
			SpiritLeapSelection.KeyMode.CORNERS, cornerKeys, Map.of(), cards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)));
		require(target.isPresent() && target.orElseThrow().containerSlot() == 11,
			"a quadrant key resolves only the matching player-head slot in the open menu");
		List<SpiritLeapRoster.Card> cardsWithNullMember = List.of(
			new SpiritLeapRoster.Card(0, null),
			new SpiritLeapRoster.Card(0, new SpiritLeapRoster.Member("ArcherOne", "Archer", 80, true)));
		List<SpiritLeapSelection.MenuHead> archerHead = List.of(
			new SpiritLeapSelection.MenuHead(11, "ArcherOne", true));
		var cornerTargetAfterNullMember = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "1",
			SpiritLeapSelection.KeyMode.CORNERS, cornerKeys, Map.of(), cardsWithNullMember, archerHead);
		var mouseTargetAfterNullMember = SpiritLeapSelection.targetForQuadrant(true, "Spirit Leap", 0,
			cardsWithNullMember, archerHead);
		require(cornerTargetAfterNullMember.isPresent() && mouseTargetAfterNullMember.isPresent(),
			"quadrant selection skips malformed cards and resolves the next matching player");
		require(!SpiritLeapSelection.isLeapMenu("Spirit Leap\u2003")
			&& SpiritLeapSelection.validatedPlayerName(
				new SpiritLeapSelection.MenuHead(11, "\u2003ArcherOne", true)) == null,
			"menu and player-name parsing retain Java's legacy trim behavior");
		var unicodePaddedKey = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "1\u2003",
			SpiritLeapSelection.KeyMode.CORNERS, cornerKeys, Map.of(), cards, archerHead);
		require(unicodePaddedKey.isEmpty(), "key normalization retains Java's legacy trim behavior");
		require(SpiritLeapSelection.quadrantAt(149, 99, 300, 200) == 0
			&& SpiritLeapSelection.quadrantAt(150, 99, 300, 200) == 1
			&& SpiritLeapSelection.quadrantAt(149, 100, 300, 200) == 2
			&& SpiritLeapSelection.quadrantAt(150, 100, 300, 200) == 3
			&& SpiritLeapSelection.quadrantAt(149, 99, 301, 201) == 0
			&& SpiritLeapSelection.quadrantAt(150, 99, 301, 201) == 1
			&& SpiritLeapSelection.quadrantAt(149, 100, 301, 201) == 2
			&& SpiritLeapSelection.quadrantAt(150, 100, 301, 201) == 3
			&& SpiritLeapSelection.quadrantAt(-1, 100, 300, 200) == -1,
			"mouse positions resolve to Odin's integer-centered quadrants for even and odd sizes");
		List<SpiritLeapSelection.MenuHead> menuHeads = List.of(
			new SpiritLeapSelection.MenuHead(13, "HealerOne", true));
		var mouseTarget = SpiritLeapSelection.targetForQuadrant(true, "Teleport to Player", 2, cards, menuHeads);
		require(mouseTarget.isPresent() && mouseTarget.orElseThrow().playerName().equals("HealerOne")
			&& mouseTarget.orElseThrow().containerSlot() == 13,
			"a consumed quadrant click resolves the displayed card to its current menu-head slot");
		var classTarget = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "a",
			SpiritLeapSelection.KeyMode.CLASS, Map.of(), Map.of("Archer", "a"), cards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)));
		require(classTarget.isPresent() && classTarget.orElseThrow().containerSlot() == 11,
			"a configured class key resolves its online class target in the open menu");
		List<SpiritLeapRoster.Card> sparseCards = SpiritLeapRoster.odinCards(List.of(
			new SpiritLeapRoster.Member("ArcherOne", "Archer", 80, true)));
		var populatedMousePress = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		var emptyMouseQuadrant = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 1, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		var emptyMouseQuadrantRelease = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 1, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.RELEASE, SpiritLeapSelection.MousePhase.RELEASE);
		var emptyRightButtonQuadrant = SpiritLeapSelection.mouseAction(1, true, "Spirit Leap", 1, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		var emptyMiddleButtonQuadrant = SpiritLeapSelection.mouseAction(2, true, "Spirit Leap", 1, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		require(populatedMousePress.consumed() && populatedMousePress.target().isPresent()
			&& !emptyMouseQuadrant.consumed() && emptyMouseQuadrant.target().isEmpty()
			&& !emptyMouseQuadrantRelease.consumed() && emptyMouseQuadrantRelease.target().isEmpty()
			&& !emptyRightButtonQuadrant.consumed() && !emptyMiddleButtonQuadrant.consumed(),
			"occupied cards consume clicks while empty quadrants pass left, right, and middle clicks through");
		var populatedRightButtonPress = SpiritLeapSelection.mouseAction(1, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		var populatedRightButtonRelease = SpiritLeapSelection.mouseAction(1, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.RELEASE);
		var populatedMiddleButtonPress = SpiritLeapSelection.mouseAction(2, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		require(populatedRightButtonPress.consumed() && populatedRightButtonPress.target().isPresent()
			&& populatedRightButtonRelease.consumed() && populatedRightButtonRelease.target().isEmpty()
			&& populatedMiddleButtonPress.consumed() && populatedMiddleButtonPress.target().isPresent(),
			"left, right, and middle buttons select occupied cards; only the configured phase resolves a target");
		List<SpiritLeapRoster.Card> noTeammateCards = new SpiritLeapRoster().cards(SpiritLeapRoster.Sort.ODIN,
			List.of());
		boolean allEmptyPassThrough = true;
		for (int quadrant = 0; quadrant < 4; quadrant++) {
			allEmptyPassThrough &= !SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", quadrant,
				noTeammateCards, List.of(), SpiritLeapSelection.MouseTiming.PRESS,
				SpiritLeapSelection.MousePhase.PRESS).consumed();
		}
		require(allEmptyPassThrough, "an all-empty roster lets all four quadrant clicks pass through");
		var unresolvedOccupiedCard = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(), SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		require(unresolvedOccupiedCard.consumed() && unresolvedOccupiedCard.target().isEmpty(),
			"an occupied card with no safely mapped menu head is swallowed without a leap");
		var nonPlayerHeadCard = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", false)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		var outOfRangeHeadCard = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(10, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		require(nonPlayerHeadCard.consumed() && nonPlayerHeadCard.target().isEmpty()
			&& outOfRangeHeadCard.consumed() && outOfRangeHeadCard.target().isEmpty(),
			"occupied cards mapped only to a non-player-head or out-of-range slot are swallowed without leaping");
		var releaseModePress = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.RELEASE, SpiritLeapSelection.MousePhase.PRESS);
		var releaseModeRelease = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.RELEASE, SpiritLeapSelection.MousePhase.RELEASE);
		var pressModeRelease = SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", 0, sparseCards,
			List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true)),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.RELEASE);
		require(releaseModePress.consumed() && releaseModePress.target().isEmpty()
			&& releaseModeRelease.consumed() && releaseModeRelease.target().isPresent()
			&& pressModeRelease.consumed() && pressModeRelease.target().isEmpty(),
			"mouse timing triggers on press by default or on release when configured, with the other phase swallowed");
		require(!SpiritLeapSelection.mouseAction(0, false, "Spirit Leap", 0, sparseCards, menuHeads,
				SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS).consumed()
			&& !SpiritLeapSelection.mouseAction(0, true, "Chest", 0, sparseCards, menuHeads,
				SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS).consumed()
			&& !SpiritLeapSelection.mouseAction(0, true, "Spirit Leap", -1, sparseCards, menuHeads,
				SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS).consumed(),
			"closed menus, unsupported titles, and out-of-bounds quadrants pass through");
		require(SpiritLeapSelection.targetForKey(true, "Chest", "1", SpiritLeapSelection.KeyMode.CORNERS,
			cornerKeys, Map.of(), cards, List.of(new SpiritLeapSelection.MenuHead(11, "ArcherOne", true))).isEmpty(),
			"a key cannot select a leap target outside the supported menu titles");
		require(SpiritLeapSelection.teleportedPlayer("You have teleported to ArcherOne!").equals("ArcherOne")
			&& SpiritLeapSelection.announcement("ArcherOne").equals("pc Leaped to ArcherOne!"),
			"leap completion can generate the configured party announcement");
	}

	private static void checkTimersAndSoundRules() {
		DungeonMaskProcTimers timers = new DungeonMaskProcTimers();
		require(timers.observeChat("Second Wind Activated! Your Spirit Mask saved your life!", true, null)
			== DungeonMaskProcTimers.Proc.SPIRIT, "Spirit proc is recognized from its stable chat signal");
		require(timers.reading(DungeonMaskProcTimers.Proc.SPIRIT).activeTicks() == 60,
			"Spirit active timer starts from the observed proc");
		timers.tick(true);
		require(timers.reading(DungeonMaskProcTimers.Proc.SPIRIT).activeTicks() == 59,
			"Spirit timer advances once per server tick");
		require(DungeonMaskProcTimers.bonzoCooldownSeconds(List.of("Cooldown: 12s")) == 12,
			"Bonzo cooldown can use its observed item lore override");
		DungeonSoundRules.Decision fuse = DungeonSoundRules.decide("minecraft:entity.creeper.primed", true, true, true);
		DungeonSoundRules.Decision hurt = DungeonSoundRules.decide("minecraft:entity.creeper.hurt", true, true, false);
		DungeonSoundRules.Decision explosion = DungeonSoundRules.decide("minecraft:entity.generic.explode", true, false, true);
		require(fuse.playDing() && !fuse.cancelOriginal() && hurt.cancelOriginal() && !hurt.playDing()
			&& explosion.cancelOriginal(), "Creeper ding, Creeper hurt mute, and explosion mute remain separate");
		require(DungeonSoundRules.WEIRDOS_CORRECT_CLUE_SOUND.equals("minecraft:entity.firework_rocket.large_blast")
			&& DungeonSoundRules.WEIRDOS_CORRECT_CLUE_VOLUME == 2.0f
			&& DungeonSoundRules.WEIRDOS_CORRECT_CLUE_PITCH == 1.0f,
			"a correct Three Weirdos clue uses the large firework blast at volume 2 and pitch 1");

		DungeonCreeperBeamsRules.Detection endpoints = new DungeonCreeperBeamsRules.Detection(0, List.of(
			new DungeonCreeperBeamsRules.Pair(new DungeonCreeperBeamsRules.Endpoint(1, 70, 1),
				new DungeonCreeperBeamsRules.Endpoint(2, 70, 2)),
			new DungeonCreeperBeamsRules.Pair(new DungeonCreeperBeamsRules.Endpoint(3, 70, 3),
				new DungeonCreeperBeamsRules.Endpoint(4, 70, 4)),
			new DungeonCreeperBeamsRules.Pair(new DungeonCreeperBeamsRules.Endpoint(5, 70, 5),
				new DungeonCreeperBeamsRules.Endpoint(6, 70, 6)),
			new DungeonCreeperBeamsRules.Pair(new DungeonCreeperBeamsRules.Endpoint(7, 70, 7),
				new DungeonCreeperBeamsRules.Endpoint(8, 70, 8)),
			new DungeonCreeperBeamsRules.Pair(new DungeonCreeperBeamsRules.Endpoint(9, 70, 9),
				new DungeonCreeperBeamsRules.Endpoint(10, 70, 10))), null);
		boolean matchedEndpoint = DungeonCreeperBeamsRules.containsEndpoint(endpoints,
			new DungeonCreeperBeamsRules.WorldPosition(1, 70, 1));
		boolean unrelatedSeaLantern = DungeonCreeperBeamsRules.containsEndpoint(endpoints,
			new DungeonCreeperBeamsRules.WorldPosition(11, 70, 11));
		require(DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", true,
			true, matchedEndpoint), "the local glass hit at a confirmed endpoint is replaced");
		require(!DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", true,
			true, unrelatedSeaLantern)
			&& !DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", false,
				true, matchedEndpoint)
			&& !DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", true,
				false, matchedEndpoint)
			&& !DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.stone.hit", true,
				true, matchedEndpoint),
			"unrelated sea lantern hits, inactive puzzle hits, and other block sounds remain vanilla");
	}

	private static void checkLividAndTerminals() {
		String startLine = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";
		DungeonLividTracker timer = new DungeonLividTracker();
		var worldLabel = DungeonLividOverlayLabel.create(true, true, 340);
		require(worldLabel != null && "17.0S".equals(worldLabel.text())
			&& worldLabel.colorArgb() == 0xFF55FF55,
			"the floating Livid label formats the full timer and starts green");
		require(DungeonLividOverlayLabel.create(false, true, 200) == null
			&& DungeonLividOverlayLabel.create(true, false, 200) == null
			&& DungeonLividOverlayLabel.create(true, true, 0) == null
			&& DungeonLividOverlayLabel.create(true, true, -1) == null,
			"the floating Livid label is hidden when disabled, outside the F5 boss, or expired");
		require("6.5S".equals(DungeonLividOverlayLabel.create(true, true, 130).text())
			&& DungeonLividOverlayLabel.create(true, true, 130).colorArgb() == 0xFFFF5555,
			"the floating Livid label follows the tracked red countdown range");
			require(DungeonLividOverlayLabel.HUD_SCALE == 1.0f,
				"the Livid timer is projected HUD text at the real font scale, not vector strokes");
		require(!timer.observeChat(startLine, false) && !timer.observeChat(startLine + "!", true)
			&& timer.invulnerabilityTicks() == 0,
			"the Livid timer ignores non-F5 and non-exact boss chat lines");
		require(timer.observeChat(" " + startLine + " ", true) && timer.invulnerabilityTicks() == 340,
			"the exact F5 boss line starts the timer after the existing outer whitespace trim");
		timer.tick(false);
		require(timer.invulnerabilityTicks() == 340, "the Livid timer does not tick outside Floor 5");
		timer.tick(true);
		require(timer.invulnerabilityTicks() == 339, "the Livid timer advances on a Floor 5 tick");
		timer.tick(false);
		require(timer.invulnerabilityTicks() == 339, "an off-floor tick does not decrement the Livid timer");
		require(DungeonLividTracker.isLividEntityName("&dCrossed Livid")
			&& DungeonLividTracker.isLividEntityName(" §7Hockey Livid ")
			&& !DungeonLividTracker.isLividEntityName("Crossed Livid Extra")
			&& !DungeonLividTracker.isLividEntityName(null),
			"formatted Livid entity names strip supported codes but still match exactly");

		DungeonLividTracker livid = new DungeonLividTracker();
		require(livid.targetEntityName() == null && !livid.observePlayerName("Hockey Livid", true),
			"the tracker does not guess Hockey/red before observing the Livid wool marker");
		require(livid.observeChat(startLine, true),
			"Livid invulnerability timer starts on the exact observed boss line");
		require(livid.invulnerabilityTicks() == 340, "Livid timer starts at 340 ticks");
		require(livid.observeMarkerWool("magenta", true)
			&& !livid.observePlayerName("Crossed Livid Extra", true)
			&& livid.observePlayerName("§dCrossed Livid", true),
			"observed wool dye selects the matching Livid player name");
		require("Crossed Livid".equals(livid.targetEntityName()) && "magenta".equals(livid.targetColorName())
			&& livid.targetColorArgb() == 0xFFFF55FF,
			"the visible target identity and title color come from the same observed wool selection");
		require(livid.highlightTarget(true, false).equals("Crossed Livid")
			&& livid.highlightTarget(true, true) == null && livid.highlightTarget(false, false) == null,
			"Livid highlight is hidden while blinded or outside the Floor 5 boss phase");
		java.util.UUID targetUuid = java.util.UUID.randomUUID();
		require(!livid.observePlayerEntity(targetUuid, -1, "§dCrossed Livid", true)
			&& !livid.observePlayerEntity(targetUuid, 91, "§dCrossed Livid Extra", true)
			&& livid.observePlayerEntity(targetUuid, 91, "§dCrossed Livid", true),
			"the exact observed Livid entity records its UUID and client entity ID");
		DungeonLividTracker.TargetSnapshot target = livid.selectedTarget(true, false);
		require(target != null && target.uuid().equals(targetUuid) && target.entityId() == 91
			&& "Crossed Livid".equals(target.entityName()) && "magenta".equals(target.colorName())
			&& target.colorArgb() == 0xFFFF55FF,
			"the selected Livid snapshot joins one exact entity identity and the observed wool color");
		java.lang.reflect.RecordComponent[] lividComponents = DungeonLividTracker.TargetSnapshot.class.getRecordComponents();
		require(DungeonLividTracker.TargetSnapshot.class.isRecord() && lividComponents != null
			&& java.util.Arrays.stream(lividComponents).map(java.lang.reflect.RecordComponent::getName).toList()
				.equals(List.of("uuid", "entityId", "entityName", "colorName", "colorArgb"))
			&& java.util.Arrays.stream(lividComponents).map(java.lang.reflect.RecordComponent::getType).toList()
				.equals(List.of(java.util.UUID.class, int.class, String.class, String.class, int.class)),
			"the selected Livid snapshot keeps its native Java record metadata and component ABI");
		DungeonLividTracker.TargetSnapshot nullableTarget =
			new DungeonLividTracker.TargetSnapshot(null, -1, null, null, 0);
		require(nullableTarget.uuid() == null && nullableTarget.entityId() == -1
			&& nullableTarget.entityName() == null && nullableTarget.colorName() == null,
			"the selected Livid snapshot keeps its public record constructor and nullable reference components");
		require(livid.selectedTarget(true, true) == null && livid.selectedTarget(false, false) == null,
			"the target snapshot is unavailable while blinded or outside the active Floor 5 boss phase");
		require(livid.observeMarkerWool("yellow", true)
			&& "Arcade Livid".equals(livid.targetEntityName())
			&& livid.selectedTarget(true, false) == null
			&& livid.highlightTarget(true, false) == null,
			"changing observed wool color clears the previous Livid name and entity identity");
		require(!livid.observeMarkerWool("black", true) && "Arcade Livid".equals(livid.targetEntityName()),
			"an unrecognized wool observation leaves state for the caller's authoritative clear path");
		livid.clearMarker();
		require(livid.targetEntityName() == null && livid.targetColorName() == null
			&& livid.selectedTarget(true, false) == null && livid.invulnerabilityTicks() == 340,
			"clearing an invalid marker removes target state without changing the separate timer");
		livid.reset();
		require(livid.targetEntityName() == null && livid.invulnerabilityTicks() == 0
			&& !livid.timerVisible(true), "a full Livid reset clears both target and timer state");
		// The real Livid dying or the run ending must drop the target, timer and HUD label for good.
		DungeonLividTracker gone = new DungeonLividTracker();
		java.util.UUID goneUuid = java.util.UUID.randomUUID();
		gone.observeChat(startLine, true);
		require(gone.observeMarkerWool("gray", true) && gone.observePlayerEntity(goneUuid, 7, "§7Doctor Livid", true)
			&& gone.matchedEntityId() == 7 && gone.selectedTarget(true, false) != null,
			"a matched Livid exposes its entity id and target snapshot");
		gone.releaseTarget();
		require(gone.selectedTarget(true, false) == null && gone.targetEntityName() == null
			&& gone.invulnerabilityTicks() == 0 && !gone.timerVisible(true) && gone.matchedEntityId() == -1
			&& gone.released(), "releasing the Livid drops the target snapshot, the timer and the matched entity id");
		require(!gone.observeMarkerWool("gray", true) && !gone.observePlayerEntity(goneUuid, 7, "§7Doctor Livid", true)
			&& gone.selectedTarget(true, false) == null,
			"a released Livid is not re-acquired from the leftover wool marker or its lingering entity");
		require(gone.observeChat(startLine, true) && !gone.released() && gone.observeMarkerWool("gray", true),
			"the next boss start line re-arms a released Livid tracker");
		gone.releaseTarget();
		gone.reset();
		require(!gone.released(), "a level-load reset clears the release latch");
		require(DungeonLividReleaseRules.shouldRelease(DungeonLividReleaseRules.RELEASE_TICKS)
			&& !DungeonLividReleaseRules.shouldRelease(DungeonLividReleaseRules.RELEASE_TICKS - 1)
			&& DungeonLividReleaseRules.nextMissingTicks(9, true) == 0
			&& DungeonLividReleaseRules.nextMissingTicks(3, false) == 4
			&& DungeonLividReleaseRules.nextMissingTicks(DungeonLividReleaseRules.RELEASE_TICKS, false)
				== DungeonLividReleaseRules.RELEASE_TICKS,
			"the Livid target is released only after a sustained run of missing or dead ticks");
		require(DungeonLividReleaseRules.isRunEnd("> EXTRA STATS <")
			&& DungeonLividReleaseRules.isRunEnd("  > EXTRA STATS <  ")
			&& !DungeonLividReleaseRules.isRunEnd("EXTRA STATS") && !DungeonLividReleaseRules.isRunEnd(null),
			"the run-end line is recognised exactly");
		String[][] colors = {
			{"white", "Vendetta Livid", "ffffffff"}, {"magenta", "Crossed Livid", "ffff55ff"},
			{"yellow", "Arcade Livid", "ffffff55"}, {"lime", "Smile Livid", "ff55ff55"},
			{"gray", "Doctor Livid", "ffaaaaaa"}, {"purple", "Purple Livid", "ffaa00aa"},
			{"blue", "Scream Livid", "ff5555ff"}, {"green", "Frog Livid", "ff00aa00"},
			{"red", "Hockey Livid", "ffff5555"}};
		for (String[] color : colors) {
			DungeonLividTracker selected = new DungeonLividTracker();
			require(selected.observeMarkerWool(color[0] + "_wool", true)
				&& color[1].equals(selected.targetEntityName())
				&& Long.toHexString(Integer.toUnsignedLong(selected.targetColorArgb())).equals(color[2]),
				"each observed wool color supplies one matching Livid name and exact legacy color");
		}
		DungeonTerminalBreakdown terminals = new DungeonTerminalBreakdown();
		var completion = terminals.observeTitle("ArcherOne activated a terminal! (2/7)", "Self", false);
		require(completion != null && terminals.counts().get("terminal") == 1
			&& terminals.replacement("&a{type} {current}/{total}", completion).equals("§aterminal 2/7"),
			"terminal titles update the breakdown and support color-aware templates");
		require(terminals.observeTitle("Other completed a device! (1/1)", "Self", true) == null,
			"self-only terminal title mode ignores teammate titles");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

}
