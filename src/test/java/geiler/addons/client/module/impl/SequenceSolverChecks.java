package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression traces for the upstream event contracts and the actual vanilla listener boundary. */
final class SequenceSolverChecks {
	static void run() {
		checkSolverMetadataAbi();
		checkKotlinValueSemantics();
		checkRemainingFacadeRecordBoundaries();
		checkChronomatronEventConstruction();
		checkExperimentCellValueLogic();
		checkSequenceStepConstruction();
		checkSuperpairsViewSemantics();
		checkExperimentSnapshotBoundary();
		checkUltrasequencerNullCellParity();
		checkUltrasequencerSolutionAdapter();
		checkAutoUltrasequencerDispatchProgress();
		checkExecutionProgressPresentationSnapshot();
		checkRenderDoesNotLearn();
		checkUltraRounds();
		checkUltraRoundTwoSolve();
		checkUltraTimerAfterPaneEdge();
		checkUltraOpeningClick();
		checkVanillaListenerBridge();
	}

	private static void checkChronomatronEventConstruction() {
		ChronomatronEvent board = new ChronomatronEvent(ChronomatronEvent.Kind.BOARD, 4,
			"\u0001red\t", true, " \tTimer: 3s\t ");
		require(board.value().equals("red") && board.status().equals("Timer: 3s"),
			"Chronomatron event strings retain Java trim normalization");
		ChronomatronEvent status = new ChronomatronEvent(ChronomatronEvent.Kind.STATUS, -4,
			"\u2003", false, null);
		require(status.slotId() == -4 && status.value().equals("\u2003") && status.status().isEmpty(),
			"status events accept negative slots, retain Unicode above U+0020, and normalize null status");

		boolean rejectsNullKind = false;
		try {
			new ChronomatronEvent(null, -1, null, false, null);
		} catch (IllegalArgumentException expected) {
			rejectsNullKind = "kind must not be null".equals(expected.getMessage());
		}
		require(rejectsNullKind, "Chronomatron events retain null-kind validation and message");

		boolean rejectsNegativeBoardSlot = false;
		try {
			new ChronomatronEvent(ChronomatronEvent.Kind.BOARD, -1, null, false, "");
		} catch (IllegalArgumentException expected) {
			rejectsNegativeBoardSlot = "board events require a non-negative slot id".equals(expected.getMessage());
		}
		require(rejectsNegativeBoardSlot, "board events retain negative-slot validation and message");
	}

	private static void checkExperimentCellValueLogic() {
		require(!new ExperimentCell(1, null, -1, true, false, false).hasValue(),
			"null cell values are absent");
		require(!new ExperimentCell(2, " \t ", -1, true, false, false).hasValue(),
			"blank cell values are absent");
		require(new ExperimentCell(3, "  ruby  ", -1, true, false, false).hasValue(),
			"non-blank values remain present after record normalization");

		require(new ExperimentCell(4, "not numeric", 7, true, false, false).numericValue() == 7,
			"a positive numeric field takes precedence over the display value");
		require(new ExperimentCell(5, " 42 ", -1, true, false, false).numericValue() == 42,
			"fallback parsing accepts a trimmed positive integer");
		require(new ExperimentCell(6, "0", 0, true, false, false).numericValue() == 0,
			"a parsed zero fallback retains the Java record behavior");
		require(new ExperimentCell(7, "-3", 0, true, false, false).numericValue() == -3,
			"a parsed negative fallback retains the Java record behavior");
		require(new ExperimentCell(8, "not numeric", -1, true, false, false).numericValue() == -1,
			"a non-numeric fallback parses as unavailable");
		require(new ExperimentCell(9, null, -1, true, false, false).numericValue() == -1,
			"a null fallback parses as unavailable");
		require(new ExperimentCell(10, "   ", -1, true, false, false).numericValue() == -1,
			"a blank fallback parses as unavailable");
		require(new ExperimentCell(11, "\u00a0", -1, true, false, false).hasValue(),
			"non-breaking spaces retain Java String.isBlank behavior");
		require(new ExperimentCell(12, "\u200342\u2003", -1, true, false, false).numericValue() == -1,
			"numeric parsing does not trim Unicode whitespace that Java String.trim retains");
		require(new ExperimentCell(13, "\u0001ruby\t", -1, true, false, false).value().equals("ruby"),
			"cell construction retains Java trim normalization");
		require(new ExperimentCell(14, "\u2003", -1, true, false, false).value().equals("\u2003"),
			"cell construction preserves whitespace above U+0020");

		boolean rejectsNegativeSlot = false;
		try {
			new ExperimentCell(-1, null, -1, true, false, false);
		} catch (IllegalArgumentException expected) {
			rejectsNegativeSlot = "slotId must be non-negative".equals(expected.getMessage());
		}
		require(rejectsNegativeSlot, "cells retain negative-slot validation and message");
	}

	private static void checkSuperpairsViewSemantics() {
		SuperpairsBoard.Card ruby = new SuperpairsBoard.Card(1, "ruby", SuperpairsBoard.CardState.KNOWN);
		List<SuperpairsBoard.Card> sourceCards = new ArrayList<>(List.of(ruby,
			new SuperpairsBoard.Card(2, " \t ", SuperpairsBoard.CardState.UNKNOWN),
			new SuperpairsBoard.Card(3, null, SuperpairsBoard.CardState.RESOLVED),
			new SuperpairsBoard.Card(4, "emerald", SuperpairsBoard.CardState.RESOLVED),
			new SuperpairsBoard.Card(5, "ruby", SuperpairsBoard.CardState.MATCH)));
		var sourcePairs = new java.util.LinkedHashMap<String, List<Integer>>();
		sourcePairs.put("ruby", new ArrayList<>(List.of(1, 5)));
		SuperpairsBoard.View view = new SuperpairsBoard.View(sourceCards, 1, 3, sourcePairs);
		sourceCards.clear();
		sourcePairs.clear();
		require(view.cards().size() == 5 && view.pairs().containsKey("ruby"),
			"view construction defensively copies its outer list and map");
		require(view.knownCount() == 3 && view.discoveredCount() == 3
			&& view.resolvedCount() == 2 && view.collectedCount() == 2,
			"view counts preserve nonblank values and resolved states");
		require(view.resolvedPairs() == 1 && view.totalPairs() == 2 && view.waitingForReveal(),
			"view derives pair totals and pending-reveal state from its snapshot");

		boolean immutableCards = false;
		try {
			view.cards().add(ruby);
		} catch (UnsupportedOperationException expected) {
			immutableCards = true;
		}
		boolean immutablePairs = false;
		try {
			view.pairs().put("emerald", List.of(4));
		} catch (UnsupportedOperationException expected) {
			immutablePairs = true;
		}
		require(immutableCards && immutablePairs, "view outer collections remain immutable");

		SuperpairsBoard.View emptyFromNull = new SuperpairsBoard.View(null, -1, -1, null);
		require(emptyFromNull.cards().isEmpty() && emptyFromNull.pairs().isEmpty()
			&& emptyFromNull.knownCount() == 0 && emptyFromNull.resolvedCount() == 0
			&& !emptyFromNull.waitingForReveal()
			&& SuperpairsBoard.View.empty().equals(emptyFromNull),
			"null collections and the empty factory retain empty-view behavior");

		boolean rejectsNullCard = false;
		try {
			new SuperpairsBoard.View(Arrays.asList((SuperpairsBoard.Card) null), -1, -1, null);
		} catch (NullPointerException expected) {
			rejectsNullCard = true;
		}
		boolean rejectsNullPairKey = false;
		try {
			var nullKeyPair = new java.util.HashMap<String, List<Integer>>();
			nullKeyPair.put(null, List.of(1));
			new SuperpairsBoard.View(List.of(), -1, -1, nullKeyPair);
		} catch (NullPointerException expected) {
			rejectsNullPairKey = true;
		}
		require(rejectsNullCard && rejectsNullPairKey,
			"view copies retain List.copyOf and Map.copyOf null rejection");
	}

	private static void checkSequenceStepConstruction() {
		List<Integer> input = new ArrayList<>(Arrays.asList(5, null, 3, -1, 5));
		SequenceStep normalized = new SequenceStep(4, "\u0001value\u0002", input);
		input.clear();
		require(normalized.value().equals("value") && normalized.slotIds().equals(List.of(3, 5))
			&& normalized.containsSlot(3) && !normalized.containsSlot(-1),
			"sequence steps trim with Java rules, filter, sort, deduplicate, and detach their slot IDs");

		boolean immutable = false;
		try {
			normalized.slotIds().add(6);
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "sequence-step slot IDs remain immutable");

		SequenceStep empty = new SequenceStep(0, null, null);
		require(empty.value().isEmpty() && empty.slotIds().isEmpty(),
			"sequence steps normalize null value and slot list to empty defaults");
		String emSpace = "\u2003";
		SequenceStep unicode = new SequenceStep(1, emSpace + "value" + emSpace, List.of(2));
		require(unicode.value().equals(emSpace + "value" + emSpace),
			"sequence-step normalization preserves whitespace above U+0020");

		boolean rejectsNegativeIndex = false;
		try {
			new SequenceStep(-1, "", List.of());
		} catch (IllegalArgumentException expected) {
			rejectsNegativeIndex = "index must be non-negative".equals(expected.getMessage());
		}
		require(rejectsNegativeIndex, "sequence steps retain negative-index validation and message");
	}

	@SuppressWarnings("deprecation")
	private static void checkExperimentSnapshotBoundary() {
		ExperimentCell unhighlighted = ExperimentCell.token(2, "visible", false);
		ExperimentCell sameSlotLater = ExperimentCell.token(2, "visible later", false);
		ExperimentCell blankHighlight = ExperimentCell.token(7, " \t ", true);
		ExperimentCell firstHighlight = ExperimentCell.token(11, " first ", true);
		ExperimentCell laterHighlight = ExperimentCell.token(12, "later", true);
		List<ExperimentCell> source = new ArrayList<>(List.of(
			laterHighlight, blankHighlight, unhighlighted, firstHighlight, sameSlotLater));
		ExperimentSnapshot snapshot = new ExperimentSnapshot(" \tChronomatron (High)\t ",
			" \tRemember the pattern!\t ", source, "  I  ", 42L, true);

		require(snapshot.title().equals("Chronomatron (High)"), "title uses Java trim normalization");
		require(snapshot.status().equals("Remember the pattern!"), "status uses Java trim normalization");
		require(snapshot.cells().equals(List.of(unhighlighted, sameSlotLater, blankHighlight,
			firstHighlight, laterHighlight)),
			"cells are sorted by slot id with equal ids retaining encounter order");
		require(snapshot.type() == ExperimentType.CHRONOMATRON && snapshot.tier() == ExperimentTier.HIGH,
			"type and tier derive from the normalized title");
		require(snapshot.phase() == ExperimentPhase.MEMORIZE,
			"phase derives from the normalized status and title");
		require(snapshot.highlightedCell().orElseThrow() == firstHighlight,
			"first highlighted cell with a nonblank value is selected in sorted order");
		require(snapshot.ultrasequencerPaneColor().equals("i") && snapshot.revision() == 42L
			&& snapshot.statusEvent(), "pane color, revision, and status-event fields are retained");

		source.clear();
		require(snapshot.cells().size() == 5, "snapshot cells are defensively copied");
		boolean immutable = false;
		try {
			snapshot.cells().add(unhighlighted);
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "snapshot cells are immutable");

		ExperimentSnapshot minimal = new ExperimentSnapshot(null, null, null);
		require(minimal.title().isEmpty() && minimal.status().isEmpty() && minimal.cells().isEmpty()
			&& minimal.ultrasequencerPaneColor() == null && minimal.revision() == 0L && !minimal.statusEvent(),
			"the short constructor preserves empty defaults");
		require(minimal.type() == null && minimal.tier() == ExperimentTier.UNKNOWN
			&& minimal.phase() == ExperimentPhase.IDLE && minimal.highlightedCell().isEmpty(),
			"empty snapshots derive unknown solver metadata");
		boolean rejectsNullCell = false;
		try {
			new ExperimentSnapshot("", "", Arrays.asList((ExperimentCell) null));
		} catch (NullPointerException expected) {
			rejectsNullCell = true;
		}
		require(rejectsNullCell, "immutable snapshot copies continue rejecting null cells");

		ExperimentSnapshot fiveArgument = new ExperimentSnapshot("Superpairs", "", List.of(), " BLUE ", 9L);
		require(fiveArgument.ultrasequencerPaneColor().equals("blue") && fiveArgument.revision() == 9L
			&& !fiveArgument.statusEvent(), "the five-argument constructor defaults statusEvent to false");
		ExperimentSnapshot legacy = new ExperimentSnapshot("Superpairs", "", List.of(), 90, 80, 70, 6L);
		require(legacy.ultrasequencerPaneColor() == null && legacy.revision() == 6L && !legacy.statusEvent(),
			"the deprecated compatibility constructor ignores progress fields");

		Locale originalLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			ExperimentSnapshot rooted = new ExperimentSnapshot("Superpairs", "", List.of(), " I ", 0L, false);
			require(rooted.ultrasequencerPaneColor().equals("i"), "pane color lowercasing uses Locale.ROOT");
		} finally {
			Locale.setDefault(originalLocale);
		}

		String emSpace = "\u2003";
		ExperimentSnapshot unicodeWhitespace = new ExperimentSnapshot(emSpace + "Chronomatron (High)" + emSpace,
			emSpace + "Remember the pattern!" + emSpace, List.of(), emSpace + "I" + emSpace, 1L, false);
		require(unicodeWhitespace.title().equals(emSpace + "Chronomatron (High)" + emSpace)
			&& unicodeWhitespace.status().equals(emSpace + "Remember the pattern!" + emSpace)
			&& unicodeWhitespace.ultrasequencerPaneColor().equals(emSpace + "i" + emSpace),
			"Java trim semantics preserve whitespace above U+0020");
		require(unicodeWhitespace.type() == null && unicodeWhitespace.tier() == ExperimentTier.UNKNOWN
			&& unicodeWhitespace.phase() == ExperimentPhase.IDLE,
			"untrimmed Unicode whitespace does not produce a recognized experiment title");
	}

	private static void checkUltrasequencerNullCellParity() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.observe("Remember the pattern!", Arrays.asList(null, ExperimentCell.number(30, 1)), null);
		require(model.sequence().size() == 1 && model.sequence().getFirst().slotIds().equals(List.of(30)),
			"null cells remain ignored while valid Ultrasequencer values are captured");
		model.reset();
		model.markDirty(Arrays.asList(null, "gray"));
		require(model.state() == UltrasequencerModel.State.END,
			"null pane entries remain ignored while scanning menu mutations");
	}

	private static void checkSolverMetadataAbi() {
		requireEnumConstants(ChronomatronModel.State.class, "REMEMBER", "WAIT", "SHOW", "END");
		requireEnumConstants(UltrasequencerModel.State.class, "REMEMBER", "WAIT", "SHOW", "END");
		requireKotlinType(ExperimentSolverEngine.class);
		requireKotlinType(SuperpairsBoard.class);
		requireKotlinValueClass(ChronomatronEvent.class, "kind", "slotId", "value", "highlighted", "status");
		requireKotlinValueClass(ClickDecision.class, "slotId", "recognized", "expected", "predicted",
			"visualStateChanged", "reason");
		requireKotlinValueClass(ExperimentBoardGeometry.class, "columns", "rows", "slotIds");
		requireKotlinValueClass(ExperimentCell.class, "slotId", "value", "number", "revealed", "highlighted", "removed");
		requireKotlinValueClass(ExperimentMilestone.class, "type", "tier", "serumsConsumed",
			"displayedSequenceLength", "completedRoundThreshold", "maximumExtraClicks");
		requireKotlinValueClass(ExperimentSnapshot.class, "title", "status", "cells", "ultrasequencerPaneColor",
			"revision", "statusEvent");
		requireKotlinValueClass(SequenceProbe.class, "lastStatus", "modelState", "openingClickOwed", "nextSlotId",
			"solveWindowOpen");
		requireKotlinValueClass(SequenceStep.class, "index", "value", "slotIds");
		requireKotlinValueClass(SolverView.class, "type", "tier", "phase", "currentSequenceLength",
			"completedRounds", "sequence", "authoritativeIndex", "predictedIndex", "visualIndex", "current",
			"next", "nextNext", "superpairs", "milestone", "milestoneReached");
		requireKotlinValueClass(ExperimentSolverEngine.Configuration.class, "serumsConsumed");
		requireKotlinValueClass(SuperpairsBoard.Card.class, "slotId", "value", "state");
		requireKotlinValueClass(SuperpairsBoard.View.class, "cards", "selectedSlot", "awaitingRevealSlot", "pairs");
		requireEnumConstants(ChronomatronEvent.Kind.class, "BOARD", "STATUS");
		requireEnumConstants(SuperpairsBoard.CardState.class, "UNKNOWN", "KNOWN", "SELECTED", "MATCH", "RESOLVED");
	}

	private static void checkKotlinValueSemantics() {
		requireSameValue(new ChronomatronEvent(ChronomatronEvent.Kind.BOARD, 1, "red", true, "Timer"),
			new ChronomatronEvent(ChronomatronEvent.Kind.BOARD, 1, "red", true, "Timer"),
			"ChronomatronEvent[kind=BOARD, slotId=1, value=red, highlighted=true, status=Timer]",
			"ChronomatronEvent");
		requireSameValue(new ClickDecision(1, true, true, false, false, "ok"),
			new ClickDecision(1, true, true, false, false, "ok"),
			"ClickDecision[slotId=1, recognized=true, expected=true, predicted=false, visualStateChanged=false, reason=ok]",
			"ClickDecision");
		requireSameValue(new ExperimentBoardGeometry(1, 2, List.of(3, 4)),
			new ExperimentBoardGeometry(1, 2, List.of(3, 4)),
			"ExperimentBoardGeometry[columns=1, rows=2, slotIds=[3, 4]]", "ExperimentBoardGeometry");
		requireSameValue(new ExperimentCell(1, "red", -1, true, false, false),
			new ExperimentCell(1, "red", -1, true, false, false),
			"ExperimentCell[slotId=1, value=red, number=-1, revealed=true, highlighted=false, removed=false]",
			"ExperimentCell");
		requireSameValue(new ExperimentMilestone(ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, 0, 2, 2, 0),
			new ExperimentMilestone(ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, 0, 2, 2, 0),
			"ExperimentMilestone[type=CHRONOMATRON, tier=HIGH, serumsConsumed=0, displayedSequenceLength=2, "
				+ "completedRoundThreshold=2, maximumExtraClicks=0]", "ExperimentMilestone");
		requireSameValue(new ExperimentSnapshot("Superpairs", "", List.of(), null, 1L, false),
			new ExperimentSnapshot("Superpairs", "", List.of(), null, 1L, false),
			"ExperimentSnapshot[title=Superpairs, status=, cells=[], ultrasequencerPaneColor=null, revision=1, "
				+ "statusEvent=false]", "ExperimentSnapshot");
		requireSameValue(new SequenceProbe("x", "show", true, 17, true),
			new SequenceProbe("x", "show", true, 17, true),
			"SequenceProbe[lastStatus=x, modelState=show, openingClickOwed=true, nextSlotId=17, solveWindowOpen=true]",
			"SequenceProbe");
		requireSameValue(new SequenceStep(0, "1", List.of(17)), new SequenceStep(0, "1", List.of(17)),
			"SequenceStep[index=0, value=1, slotIds=[17]]", "SequenceStep");
		SolverView idle = SolverView.idle();
		requireSameValue(idle, new SolverView(null, ExperimentTier.UNKNOWN, ExperimentPhase.IDLE,
			-1, -1, List.of(), 0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(),
			SuperpairsBoard.View.empty(), Optional.empty(), false),
			"SolverView[type=null, tier=UNKNOWN, phase=IDLE, currentSequenceLength=-1, completedRounds=-1, "
				+ "sequence=[], authoritativeIndex=0, predictedIndex=0, visualIndex=0, current=Optional.empty, "
				+ "next=Optional.empty, nextNext=Optional.empty, "
				+ "superpairs=View[cards=[], selectedSlot=-1, awaitingRevealSlot=-1, pairs={}], "
				+ "milestone=Optional.empty, milestoneReached=false]", "SolverView");
		requireSameValue(new ExperimentSolverEngine.Configuration(0), new ExperimentSolverEngine.Configuration(0),
			"Configuration[serumsConsumed=0]", "ExperimentSolverEngine.Configuration");
		SuperpairsBoard.Card card = new SuperpairsBoard.Card(1, "ruby", SuperpairsBoard.CardState.KNOWN);
		requireSameValue(card, new SuperpairsBoard.Card(1, "ruby", SuperpairsBoard.CardState.KNOWN),
			"Card[slotId=1, value=ruby, state=KNOWN]", "SuperpairsBoard.Card");
		SuperpairsBoard.View view = new SuperpairsBoard.View(List.of(card), 1, -1, java.util.Map.of("ruby", List.of(1)));
		requireSameValue(view,
			new SuperpairsBoard.View(List.of(new SuperpairsBoard.Card(1, "ruby", SuperpairsBoard.CardState.KNOWN)),
				1, -1, java.util.Map.of("ruby", List.of(1))),
			"View[cards=[Card[slotId=1, value=ruby, state=KNOWN]], selectedSlot=1, awaitingRevealSlot=-1, "
				+ "pairs={ruby=[1]}]", "SuperpairsBoard.View");
	}

	private static void requireSameValue(Object first, Object equivalent, String expectedString, String label) {
		require(first != equivalent && first.equals(equivalent) && equivalent.equals(first)
			&& first.hashCode() == equivalent.hashCode() && first.toString().equals(expectedString),
			label + " retains record-compatible equality, hashing, and string layout as a Kotlin class");
	}

	private static void checkRemainingFacadeRecordBoundaries() {
		List<Integer> sourceSlots = new ArrayList<>(List.of(17, 18, 26, 27));
		ExperimentBoardGeometry geometry = new ExperimentBoardGeometry(2, 2, sourceSlots);
		sourceSlots.clear();
		require(geometry.slotIds().equals(List.of(17, 18, 26, 27)) && geometry.containsSlot(26)
			&& geometry.column(26) == 0 && geometry.row(26) == 1 && geometry.slotId(1, 1) == 27,
			"board geometry keeps its defensive copy and compact row/column mapping");
		boolean immutableGeometry = false;
		try {
			geometry.slotIds().add(30);
		} catch (UnsupportedOperationException expected) {
			immutableGeometry = true;
		}
		require(immutableGeometry, "board geometry slot IDs remain immutable");
		boolean rejectsBadDimensions = false;
		try {
			new ExperimentBoardGeometry(0, 1, List.of(17));
		} catch (IllegalArgumentException expected) {
			rejectsBadDimensions = "Invalid board dimensions".equals(expected.getMessage());
		}
		boolean rejectsInvalidSlots = false;
		try {
			new ExperimentBoardGeometry(2, 1, Arrays.asList(17, null));
		} catch (IllegalArgumentException expected) {
			rejectsInvalidSlots = "Invalid experiment board slots".equals(expected.getMessage());
		}
		require(rejectsBadDimensions && rejectsInvalidSlots,
			"board geometry keeps dimension and null-slot validation messages");

		ExperimentMilestone normalizedMilestone = new ExperimentMilestone(ExperimentType.CHRONOMATRON,
			ExperimentTier.HIGH, 9, 2, 2, 0);
		require(normalizedMilestone.serumsConsumed() == 3,
			"milestone construction retains the three-serum clamp");
		require(ExperimentMilestone.forExperiment(ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, -4)
			.orElseThrow().serumsConsumed() == 0,
			"milestone factories retain the lower serum bound");

		SolverView nullableView = new SolverView(null, ExperimentTier.UNKNOWN, ExperimentPhase.IDLE,
			-1, -1, null, 0, 0, 0, null, null, null, null, null, false);
		require(nullableView.sequence().isEmpty() && nullableView.current().isEmpty()
			&& nullableView.next().isEmpty() && nullableView.nextNext().isEmpty()
			&& nullableView.superpairs().equals(SuperpairsBoard.View.empty()) && nullableView.milestone().isEmpty(),
			"solver views preserve null-to-empty collection and optional defaults");
		List<SequenceStep> mutableSequence = new ArrayList<>(List.of(new SequenceStep(0, "one", List.of(17))));
		SolverView copiedView = new SolverView(ExperimentType.CHRONOMATRON, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, 1, 0, mutableSequence, 0, 0, 0, Optional.empty(), Optional.empty(),
			Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), false);
		mutableSequence.clear();
		boolean immutableViewSequence = false;
		try {
			copiedView.sequence().clear();
		} catch (UnsupportedOperationException expected) {
			immutableViewSequence = true;
		}
		require(copiedView.sequence().size() == 1 && immutableViewSequence,
			"solver views keep a detached immutable sequence after Kotlin normalization");

		ClickDecision nullReason = new ClickDecision(17, true, false, false, false, null);
		require(nullReason.reason().isEmpty(), "click decisions normalize a null reason to empty text");
		ClickDecision whitespaceReason = new ClickDecision(17, true, false, false, false, " \t");
		require(whitespaceReason.reason().equals(" \t"),
			"click decision normalization preserves non-null reason whitespace");

		SequenceProbe emptyProbe = new SequenceProbe(null, null, false, -1, false);
		SequenceProbe activeProbe = new SequenceProbe("Timer: 3s", "show", true, 17, true);
		SequenceProbe whitespaceProbe = new SequenceProbe("\u2003", " \t", false, -1, false);
		require(emptyProbe.lastStatus().isEmpty() && emptyProbe.modelState().isEmpty()
			&& emptyProbe.describe().equals("none")
			&& activeProbe.describe().equals(
				"status=\"Timer: 3s\" model=show openingClick=true nextSlot=17 solveWindow=true"),
			"sequence probes preserve null normalization and diagnostic text");
		require(whitespaceProbe.lastStatus().equals("\u2003") && whitespaceProbe.modelState().equals(" \t"),
			"sequence probe normalization preserves Unicode and ASCII whitespace");

		require(ExperimentSolverEngine.Configuration.defaults().serumsConsumed() == 0
			&& new ExperimentSolverEngine.Configuration(-1).serumsConsumed() == 0
			&& new ExperimentSolverEngine.Configuration(5).serumsConsumed() == 3,
			"engine configuration keeps its default and serum bounds");

		UltrasequencerSequenceExecutor.Decision normalizedDecision =
			new UltrasequencerSequenceExecutor.Decision(null, -1, -1, null);
		require(normalizedDecision.action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& normalizedDecision.explanation().isEmpty(),
			"executor decisions keep null action and explanation defaults");
		UltrasequencerSequenceExecutor.Decision whitespaceDecision =
			new UltrasequencerSequenceExecutor.Decision(null, -1, -1, " \t");
		require(whitespaceDecision.action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& whitespaceDecision.explanation().equals(" \t"),
			"executor decision normalization preserves non-null explanation whitespace");
		UltrasequencerSequenceExecutor.Solution emptySolution =
			new UltrasequencerSequenceExecutor.Solution(null, -1, -1, -1, null);
		require(emptySolution.tier() == ExperimentTier.UNKNOWN && emptySolution.sequence().isEmpty(),
			"executor solutions keep unknown-tier and null-sequence defaults");
		UltrasequencerSequenceExecutor.Solution boundedSolution =
			new UltrasequencerSequenceExecutor.Solution(ExperimentTier.HIGH, 0, 2, -1,
				List.of(new SequenceStep(0, "one", List.of(17)), new SequenceStep(1, "two", List.of(18))));
		UltrasequencerSequenceExecutor.ExecutionProgress boundedProgress =
			new UltrasequencerSequenceExecutor.ExecutionProgress(boundedSolution, 99, false, false, null);
		require(boundedProgress.cursor() == 2 && boundedProgress.pauseReason().equals("none"),
			"execution progress keeps cursor bounds and its null pause-reason default");
		UltrasequencerSequenceExecutor.ExecutionProgress lowerBoundProgress =
			new UltrasequencerSequenceExecutor.ExecutionProgress(boundedSolution, -4, false, false, " \t");
		require(lowerBoundProgress.cursor() == 0 && lowerBoundProgress.pauseReason().equals(" \t"),
			"execution progress clamps the lower cursor bound and preserves pause-reason whitespace");
		List<SequenceStep> executorSource = new ArrayList<>(boundedSolution.sequence());
		UltrasequencerSequenceExecutor.Solution copiedSolution =
			new UltrasequencerSequenceExecutor.Solution(ExperimentTier.HIGH, 0, 2, -1, executorSource);
		executorSource.clear();
		boolean immutableSolution = false;
		try {
			copiedSolution.sequence().clear();
		} catch (UnsupportedOperationException expected) {
			immutableSolution = true;
		}
		require(copiedSolution.sequence().size() == 2 && immutableSolution,
			"executor solutions keep a detached immutable sequence after Kotlin normalization");
		boolean rejectsNullProgressSolution = false;
		try {
			new UltrasequencerSequenceExecutor.ExecutionProgress(null, 0, false, false, "none");
		} catch (IllegalArgumentException expected) {
			rejectsNullProgressSolution = "solution must not be null".equals(expected.getMessage());
		}
		require(rejectsNullProgressSolution,
			"execution progress keeps its null-solution validation message");
	}

	private static void checkUltrasequencerSolutionAdapter() {
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView firstResult = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		var solution = UltrasequencerSequenceExecutor.Solution.from(firstResult);
		require(solution.isPresent(), "a complete healthy solver view is accepted as the solution source");
		require(solution.orElseThrow().sequence().equals(steps),
			"the adapter preserves the solver's exact ordered sequence and slot IDs");

		SolverView incomplete = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, 4, 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), false);
		require(UltrasequencerSequenceExecutor.Solution.from(incomplete).isEmpty(),
			"a sequence shorter than the solver's reported length is unavailable to Auto");
		require(UltrasequencerSequenceExecutor.Solution.from(
			solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.WAITING, steps, 0, steps.getFirst())).isEmpty(),
			"a remembered but non-solve solver state is not dispatched");
	}

	/** Auto replays one captured solution and waits for a fresh menu update between clicks. */
	private static void checkAutoUltrasequencerDispatchProgress() {
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView unchangedSolver = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		UltrasequencerSequenceExecutor executor = new UltrasequencerSequenceExecutor();
		Object screen = new Object();
		Object menu = new Object();
		long millis = 1_000_000L;
		long start = 10_000 * millis;
		long firstDelay = 500 * millis;
		long nextDelay = 300 * millis;
		long menuRevision = 10;
		List<Integer> dispatchedSlots = new ArrayList<>();

		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver, start, firstDelay,
			menuRevision).action()
			== UltrasequencerSequenceExecutor.Action.WAIT,
			"the first click waits for Auto Experiments' configured first delay");
		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay - 1, firstDelay, menuRevision).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"the first slot cannot fire early");
		var first = executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay, firstDelay, menuRevision);
		require(first.action() == UltrasequencerSequenceExecutor.Action.CLICK && first.slotId() == 30,
			"the first solver slot is dispatched in order");
		dispatchedSlots.add(first.slotId());
		executor.dispatchFinishedWithMenuRevision(true, start + firstDelay, nextDelay, menuRevision);
		require(unchangedSolver.visualIndex() == 0,
			"Auto dispatch does not mutate or wait for the solver cursor");
		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay, nextDelay, menuRevision).action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& executor.pendingDelay().equals("awaiting_menu_update"),
			"the configured delay cannot authorize step two before the menu reports an update");
		menuRevision++;
		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay, nextDelay, menuRevision).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"a fresh menu update starts the configured inter-click delay");
		var second = executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 2, nextDelay, menuRevision);
		require(second.action() == UltrasequencerSequenceExecutor.Action.CLICK && second.slotId() == 31,
			"the second solver slot runs only after the menu update and delay");
		dispatchedSlots.add(second.slotId());
		executor.dispatchFinishedWithMenuRevision(true, start + firstDelay + nextDelay * 2,
			nextDelay, menuRevision);
		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 3, nextDelay, menuRevision).action()
			== UltrasequencerSequenceExecutor.Action.WAIT,
			"the third slot remains blocked while the second click has no menu update");
		menuRevision++;
		executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 3, nextDelay, menuRevision);
		var third = executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 4, nextDelay, menuRevision);
		require(third.action() == UltrasequencerSequenceExecutor.Action.CLICK && third.slotId() == 32,
			"the final solver slot runs in the captured order");
		dispatchedSlots.add(third.slotId());
		executor.dispatchFinishedWithMenuRevision(true, start + firstDelay + nextDelay * 4,
			nextDelay, menuRevision);
		menuRevision++;
		require(dispatchedSlots.equals(List.of(30, 31, 32)),
			"every captured slot reaches vanilla exactly once and in sequence order");
		require(executor.tickWithMenuRevision(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 20, nextDelay, menuRevision).action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& dispatchedSlots.size() == 3,
			"an unchanged solver snapshot cannot resend an already consumed slot");
		UltrasequencerSequenceExecutor timedOutUpdate = new UltrasequencerSequenceExecutor();
		var timedOutClick = timedOutUpdate.tickWithMenuRevision(screen, menu, 10, true, unchangedSolver,
			start, 0L, 3);
		timedOutUpdate.dispatchFinishedWithMenuRevision(true, start, nextDelay, 3);
		require(timedOutUpdate.tickWithMenuRevision(screen, menu, 10, true, unchangedSolver,
			start + 14_999 * millis, nextDelay, 3).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"a laggy server gets a full fifteen seconds to acknowledge a click");
		require(timedOutUpdate.tickWithMenuRevision(screen, menu, 10, true, unchangedSolver,
			start + 15_000 * millis, nextDelay, 3).action() == UltrasequencerSequenceExecutor.Action.PAUSED
			&& timedOutClick.action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"a missing menu update times out into a pause and never retries the dispatched slot");
		UltrasequencerSequenceExecutor rejectedDispatch = new UltrasequencerSequenceExecutor();
		var rejected = rejectedDispatch.tick(screen, menu, 9, true, unchangedSolver, start, 0L);
		rejectedDispatch.dispatchFinished(false, start, nextDelay);
		require(rejected.action() == UltrasequencerSequenceExecutor.Action.CLICK
			&& rejectedDispatch.tick(screen, menu, 9, true, unchangedSolver,
				start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a rejected or uncertain vanilla dispatch pauses and never resends its claimed slot");

		UltrasequencerSequenceExecutor changedSolution = new UltrasequencerSequenceExecutor();
		var claimed = changedSolution.tick(screen, menu, 2, true, unchangedSolver, start, 0L);
		require(claimed.action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"a healthy complete solver sequence can start without an extra board parse");
		changedSolution.dispatchFinished(true, start, nextDelay);
		List<SequenceStep> changedSteps = List.of(steps.getFirst(),
			new SequenceStep(1, "2", List.of(99)), steps.get(2));
		SolverView changedView = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			changedSteps, 0, changedSteps.getFirst());
		require(changedSolution.tick(screen, menu, 2, true, changedView,
			start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a changed ordered solution pauses before any remaining slot is clicked");
		UltrasequencerSequenceExecutor unavailableSolution = new UltrasequencerSequenceExecutor();
		var unavailableFirst = unavailableSolution.tick(screen, menu, 8, true, unchangedSolver, start, 0L);
		unavailableSolution.dispatchFinished(true, start, nextDelay);
		SolverView waitingSolver = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.WAITING,
			steps, 0, steps.getFirst());
		require(unavailableSolution.tick(screen, menu, 8, true, waitingSolver,
			start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED
			&& unavailableFirst.slotId() == 30,
			"an unavailable solver state before completion pauses without sending a stale next slot");

		UltrasequencerSequenceExecutor contextChange = new UltrasequencerSequenceExecutor();
		Object newMenu = new Object();
		contextChange.tick(screen, menu, 3, true, unchangedSolver, start, 100 * millis);
		require(contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
			start + 50 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"a new menu and session discard the old queued click and arm a new delay");
		require(contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
			start + 149 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
				start + 150 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"the replacement menu receives only its own delayed first slot");

		UltrasequencerSequenceExecutor unsafeSession = new UltrasequencerSequenceExecutor();
		unsafeSession.tick(screen, menu, 5, true, unchangedSolver, start, 100 * millis);
		require(unsafeSession.tick(screen, menu, 5, false, unchangedSolver,
			start + 50 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a lost active session pauses before dispatch");

		UltrasequencerSequenceExecutor noClickMilestone = new UltrasequencerSequenceExecutor();
		SolverView reachedMilestone = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, steps.size(), 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), true);
		require(noClickMilestone.tick(screen, menu, 6, true, reachedMilestone, start, 0L).action()
			== UltrasequencerSequenceExecutor.Action.STOPPED,
			"a milestone reached before any Auto click stops without closing the menu");
		UltrasequencerSequenceExecutor closeMilestone = new UltrasequencerSequenceExecutor();
		var beforeMilestone = closeMilestone.tick(screen, menu, 7, true, unchangedSolver, start, 0L);
		closeMilestone.dispatchFinished(true, start, nextDelay);
		require(beforeMilestone.action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"the milestone fixture records an Auto-dispatched click");
		require(closeMilestone.tick(screen, menu, 7, true, reachedMilestone,
			start + 1, 0L).action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU,
			"a later milestone closes the menu after Auto has dispatched a click");
		require(closeMilestone.tick(screen, menu, 7, true, reachedMilestone,
			start + 2, 0L).action() == UltrasequencerSequenceExecutor.Action.STOPPED,
			"milestone close is requested once");

		ExperimentMilestone sequenceLengthMilestone = new ExperimentMilestone(
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, 0, steps.size(), steps.size() - 1, 0);
		SolverView targetLengthView = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, steps.size(), 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(),
			Optional.of(sequenceLengthMilestone), false);
		UltrasequencerSequenceExecutor targetLength = new UltrasequencerSequenceExecutor();
		for (int index = 0; index < steps.size(); index++) {
			var next = targetLength.tick(screen, menu, 10, true, targetLengthView, start + index, 0L);
			require(next.action() == UltrasequencerSequenceExecutor.Action.CLICK
				&& next.slotId() == steps.get(index).slotIds().getFirst(),
				"the milestone round still dispatches solver slot " + (index + 1) + " in order");
			UltrasequencerSequenceExecutor.Decision finished = targetLength.dispatchFinished(
				true, start + index, 0L);
			if (index + 1 == steps.size()) {
				require(finished.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU,
					"the full solution at the solver-reported milestone requests a menu close without cursor ack");
			}
		}
	}

	/** The Solver may display Auto's cursor only for the exact active context and captured solution. */
	private static void checkExecutionProgressPresentationSnapshot() {
		require("Experimentation Solver".equals(ExperimentSolverModule.INSTANCE.name())
			&& "Solver".equals(ExperimentSolverModule.INSTANCE.configName()),
			"the visible module rename retains the legacy persisted module identity");
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView view = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		UltrasequencerSequenceExecutor executor = new UltrasequencerSequenceExecutor();
		Object screen = new Object();
		Object menu = new Object();
		long generation = 42;
		long start = 20_000_000L;
		executor.tick(screen, menu, generation, true, view, start, 100L);
		var initial = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(initial.cursor() == 0 && initial.sequenceLength() == 3 && !initial.dispatchPending(),
			"presentation begins at Auto's independent cursor without advancing it");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(initial), 3)).equals(List.of(30, 31, 32)),
			"the initial Auto snapshot keeps the current and future tile highlights in order");
		require(executor.cursor() == 0,
			"reading presentation progress has no effect on Auto execution state");
		require(executor.presentationProgress(new Object(), menu, generation, view).isEmpty()
			&& executor.presentationProgress(screen, new Object(), generation, view).isEmpty()
			&& executor.presentationProgress(screen, menu, generation + 1, view).isEmpty(),
			"a different screen, menu, or session generation cannot reuse the display snapshot");

		var click = executor.tick(screen, menu, generation, true, view, start + 100L, 100L);
		var dispatching = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(click.action() == UltrasequencerSequenceExecutor.Action.CLICK
			&& dispatching.cursor() == 1 && dispatching.dispatchPending(),
			"the display snapshot reports the claimed Auto position while vanilla dispatch is pending");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(dispatching), 3)).equals(List.of(30, 31, 32)),
			"a pending dispatch keeps its clicked tile current until vanilla returns");
		executor.dispatchFinished(true, start + 100L, 200L);
		var dispatched = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(dispatched.cursor() == 1 && !dispatched.dispatchPending(),
			"a successful dispatch updates only the displayed Auto cursor, not Solver progress");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(dispatched), 3)).equals(List.of(31, 32)),
			"after vanilla dispatch, tile priorities advance to the next Auto slot while Solver stays unchanged");

		List<SequenceStep> changedSteps = List.of(steps.getFirst(),
			new SequenceStep(1, "2", List.of(99)), steps.get(2));
		SolverView changedView = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			changedSteps, 0, changedSteps.getFirst());
		require(executor.presentationProgress(screen, menu, generation, changedView).isEmpty(),
			"a changed ordered-solution fingerprint cannot display stale Auto progress");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(changedView,
			Optional.of(dispatched), 3)).equals(List.of(30, 99, 32)),
			"a stale Auto snapshot falls back to the Solver's current ordered tile preview");
	}

	private static List<Integer> displayedSlots(List<SequenceStep> steps) {
		return steps.stream().map(step -> step.slotIds().getFirst()).toList();
	}

	private static SolverView solverView(ExperimentType type, ExperimentPhase phase,
		List<SequenceStep> sequence, int index, SequenceStep current) {
		return new SolverView(type, ExperimentTier.HIGH, phase, sequence.size(), 0, sequence,
			index, index, index, Optional.of(current), Optional.empty(), Optional.empty(),
			SuperpairsBoard.View.empty(), Optional.empty(), false);
	}

	/**
	 * A pane repaint can arrive before the round's timer, which puts the model at END while the
	 * round's numbers are still remembered. END used to ignore the timer outright, so the one signal
	 * that opens the solve window was discarded and the round sat at ROUND_COMPLETE for its whole
	 * solve. The timer now reopens a round that was never replayed, and the cursor still starts over.
	 */
	private static void checkUltraTimerAfterPaneEdge() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.markDirty(List.of("gray"));
		model.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(model.state() == UltrasequencerModel.State.WAIT, "the memory notice starts the round");

		// The pane repaint lands before the timer, so the round looks finished although it never ran.
		model.markDirty(List.of("blue"));
		require(model.state() == UltrasequencerModel.State.END,
			"a pane colour before the timer reads as a round edge");
		require(model.sequence().size() == 2, "the remembered numbers survive that edge");

		model.observe("Timer: 3s", List.of(), null);
		require(model.state() == UltrasequencerModel.State.SHOW,
			"the timer reopens a round whose edge arrived before it");
		require(model.currentIndex() == 0, "the reopened round starts at its first number");
		require(model.click(30) && model.click(31), "the reopened round replays every click");

		// A round that really was replayed must not be reopened by a later stale timer.
		model.markDirty(List.of("purple"));
		require(model.state() == UltrasequencerModel.State.END,
			"the completed round ends at its pane edge again");
		model.observe("Timer: 2s", List.of(), null);
		require(model.state() == UltrasequencerModel.State.END,
			"a timer cannot restart a round that was already replayed");

		// And a timer with nothing remembered must not invent a solve.
		UltrasequencerModel empty = new UltrasequencerModel();
		empty.markDirty(List.of("gray"));
		empty.markDirty(List.of("blue"));
		empty.observe("Timer: 3s", List.of(), null);
		require(empty.state() != UltrasequencerModel.State.SHOW,
			"a timer without remembered numbers cannot open a solve");
	}

	/** The solver exposes the remembered first slot early, while the click gate still waits for solve. */
	private static void checkUltraOpeningClick() {
		String title = "Ultrasequencer (High)";
		List<ExperimentCell> cells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3));
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", cells));

		require(engine.view().phase() == ExperimentPhase.WAITING,
			"the round waits for its solve window after the memory notice");
		require(engine.view().current().orElseThrow().containsSlot(30),
			"the first solver slot is already available during the wait");
		ExperimentClickGate gate = new ExperimentClickGate();
		boolean[] dispatched = {false};
		require(!gate.dispatchSequenceClick(engine, 0, 30, true, id -> dispatched[0] = true),
			"the vanilla click gate waits for the solver's solve phase");
		require(!dispatched[0], "the pre-solve slot is not dispatched");

		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", cells));
		require(engine.view().phase() == ExperimentPhase.SOLVE, "the timer opens the solve phase");
		require(gate.dispatchSequenceClick(engine, 0, 30, true, id -> dispatched[0] = true),
			"the expected first slot is dispatched after the solver opens the solve phase");
		require(dispatched[0] && engine.view().visualIndex() == 1,
			"the confirmed first click advances the solver cursor exactly once");
		require(!gate.dispatchSequenceClick(engine, 0, 30, true, id -> { }),
			"the confirmed first slot cannot be dispatched a second time");

		// Chronomatron has no opening click: its solve is announced, never inferred.
		ExperimentSolverEngine chrono = new ExperimentSolverEngine();
		chrono.observe(new ExperimentSnapshot("Chronomatron (High)", "Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", true))));
		require(!new ExperimentClickGate().dispatchSequenceClick(chrono, 0, 17, true, id -> { }),
			"Chronomatron still requires its own solve result before any click");
	}

	/**
	 * Round two of a real board, starting from the pane colour that is actually there.
	 *
	 * <p>The older traces all seeded {@code markDirty(List.of("gray"))} before anything else, which
	 * hides the one path a live board takes: the first pane colour a round sees is never black, so a
	 * new round's very first observation is already a colour change. This check drives round two
	 * through that exact order - pane colour, memory notice, timer, clicks - because that is the
	 * sequence BUG-003 was about and the one the old traces could not reach.
	 */
	private static void checkUltraRoundTwoSolve() {
		UltrasequencerModel fresh = new UltrasequencerModel();
		fresh.markDirty(List.of("orange"));
		// A pane colour that follows no round is read as the edge of one, so a brand new round starts
		// out looking finished. That is harmless only because the memory notice below resets it, which
		// is exactly what this trace is here to prove.
		require(fresh.state() == UltrasequencerModel.State.END,
			"a pane colour with no round in progress reads as a round edge");

		fresh.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(fresh.state() == UltrasequencerModel.State.WAIT, "the memory notice starts round one");
		fresh.observe("Timer: 3s", List.of(), null);
		require(fresh.state() == UltrasequencerModel.State.SHOW, "the timer opens the solve");
		require(fresh.click(30) && fresh.click(31), "round one replays both clicks");
		fresh.markDirty(List.of("blue"));
		require(fresh.state() == UltrasequencerModel.State.END, "the completed round ends at its pane edge");
		require(fresh.completedRounds() == 1, "round one is counted once");

		// Round two starts the way the server starts it: the board repaints its panes first, and only
		// then does the next memory notice arrive. The repaint must not cost the round its capture.
		fresh.markDirty(List.of("purple"));
		fresh.observe("Remember the pattern!", List.of(ExperimentCell.number(40, 1),
			ExperimentCell.number(41, 2), ExperimentCell.number(42, 3)), null);
		require(fresh.state() == UltrasequencerModel.State.WAIT,
			"round two captures its numbers even though a pane colour preceded the notice");
		require(fresh.sequence().size() == 3, "round two captures its own numbers");
		fresh.observe("Timer: 3s", List.of(), null);
		require(fresh.state() == UltrasequencerModel.State.SHOW,
			"round two reaches its solve; a second round must be replayable, not just capturable");
		require(fresh.click(40) && fresh.click(41) && fresh.click(42), "round two replays all three clicks");
		fresh.markDirty(List.of("green"));
		require(fresh.completedRounds() == 2, "round two is counted once");
	}

	private static void checkRenderDoesNotLearn() {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Chronomatron (High)";
		List<ExperimentCell> red = List.of(ExperimentCell.token(17, "red", true));
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().sequence().isEmpty(), "render frames cannot invent reveal events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.board(17, "red", true));
		require(engine.view().phase() == ExperimentPhase.WAITING, "board events cannot synthesize timer events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.status("Timer: 3s"));
		require(engine.view().phase() == ExperimentPhase.SOLVE, "real timer opens solve");
		engine.confirmClick(17);
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().phase() == ExperimentPhase.ROUND_COMPLETE, "stale render cannot reopen click one");
		require(engine.view().visualIndex() == 1, "render cannot rewind completed cursor");
	}

	private static void checkUltraRounds() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.markDirty(List.of("gray"));
		for (int round = 1; round <= 8; round++) {
			List<ExperimentCell> cells = new ArrayList<>();
			for (int i = 1; i <= round; i++) cells.add(ExperimentCell.number(9 + i, i));
			model.observe("Remember the pattern!", cells, null);
			model.observe("Remember the pattern!", List.of(ExperimentCell.number(44, 99)), null);
			require(model.sequence().size() == round, "WAIT freezes tick-captured number map");
			model.observe("Timer: 3s", List.of(), null);
			for (int i = 1; i <= round; i++) {
				require(model.sequence().get(i - 1).slotIds().equals(List.of(9 + i)), "number order retained");
				require(model.currentIndex() == i - 1, "cursor follows the remembered click order");
				require(model.click(9 + i), "expected Ultra click accepted");
				model.observe("Timer: 2s", List.of(), null);
			}
			require(model.currentIndex() == round - 1,
				"last selection waits for the server colour edge, as upstream");
			if (round < 8) {
				model.markDirty(List.of(round % 2 == 0 ? "blue" : "orange"));
				require(model.state() == UltrasequencerModel.State.END,
					"a pane boundary after the final click ends the round");
				model.observe("Remember the pattern!", List.of(), null);
				require(model.state() == UltrasequencerModel.State.REMEMBER,
					"tick starts the next round without a status callback");
				require(model.completedRounds() == round, "each real boundary counts exactly one round");
			}
		}
		require(model.completedRounds() == 7, "all eight Ultra rounds retained");

		UltrasequencerModel interrupted = new UltrasequencerModel();
		interrupted.markDirty(List.of("gray"));
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3)), null);
		interrupted.observe("Timer: 3s", List.of(), null);
		require(interrupted.click(30), "first click of the round is accepted");
		interrupted.markDirty(List.of("blue"));
		interrupted.markDirty(List.of("gray"));
		require(interrupted.state() == UltrasequencerModel.State.SHOW,
			"pressed and released button panes cannot end an unfinished round");
		require(interrupted.currentIndex() == 1, "the same panes cannot rewind or freeze the cursor");
		require(interrupted.click(31) && interrupted.click(32),
			"the rest of the round stays clickable after the pane churn");
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3),
			ExperimentCell.number(33, 4)), null);
		require(interrupted.sequence().size() == 4,
			"the next memory notice resets and captures the longer round");
		require(interrupted.completedRounds() == 1,
			"an interrupted solve counts its round exactly once");

		UltrasequencerModel counted = new UltrasequencerModel();
		counted.markDirty(List.of("white"));
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1)), null);
		counted.observe("Timer: 3s", List.of(), null);
		require(counted.click(30), "single-click round accepted before its boundary");
		counted.markDirty(List.of("red"));
		require(counted.state() == UltrasequencerModel.State.END,
			"a completed round still ends through its pane boundary");
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(counted.completedRounds() == 1,
			"a round that already ended through a pane is not counted twice");

		model.reset();
		model.observe("Remember the pattern!", List.of(
			new ExperimentCell(20, "1", 4, true, false, false),
			new ExperimentCell(21, "9", 5, true, false, false)), null);
		model.observe("Timer: 3s", List.of(), null);
		require(model.click(20) && model.currentIndex() == 1, "Ultra uses saved stack count for successor");
	}

	private static void checkVanillaListenerBridge() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// 26.1 binds item defaults while loading data packs. This headless fixture needs only
		// the common components; names and glints used below are explicit per-stack overrides.
		net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		TestMenu menu = new TestMenu();
		ChronomatronModel model = new ChronomatronModel();
		Item redTerracotta = registeredItem("red_terracotta");
		Item redStainedGlass = registeredItem("red_stained_glass");
		int[] events = {0};
		menu.addSlotListener(new ContainerListener() {
			@Override public void slotChanged(AbstractContainerMenu source, int slot, ItemStack stack) {
				events[0]++;
				if (slot == 49) model.observe(ChronomatronEvent.status(stack.getHoverName().getString()));
				else if (slot >= 17 && slot <= 25) model.observe(ChronomatronEvent.board(slot,
					stack.getItem() == redTerracotta || stack.getItem() == redStainedGlass
						? "red" : "blue", stack.hasFoil()));
			}
			@Override public void dataChanged(AbstractContainerMenu source, int property, int value) { }
		});
		for (int round = 1; round <= 6; round++) {
			set(menu, 49, status("Remember the pattern!"));
			if (round > 1) set(menu, 17 + (round - 2) % 2, tile((round - 2) % 2 == 0, false));
			for (int i = 0; i < round; i++) {
				set(menu, 17 + i % 2, tile(i % 2 == 0, true));
				if (i + 1 < round) set(menu, 17 + i % 2, tile(i % 2 == 0, false));
			}
			for (int i = 0; i < Math.min(round, 2); i++) set(menu, 17 + i, glass(i == 0, false));
			set(menu, 49, status("Timer: 3s"));
			require(model.items().size() == round, "real listener captures exactly one new item each round");
			for (int i = 0; i < round; i++) {
				require(model.click(i % 2 == 0 ? "red" : "blue"), "real listener trace retains click order");
				// Player click glints are delivered too, but may not grow memory or rewind progress.
				set(menu, 17 + i % 2, glass(i % 2 == 0, true));
				set(menu, 17 + i % 2, glass(i % 2 == 0, false));
			}
			require(model.state() == ChronomatronModel.State.END, "final click remains ended");
		}
		int before = events[0];
		menu.broadcastChanges();
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(events[0] == before, "two mods broadcasting cannot duplicate unchanged slot events");
		List<ItemStack> full = new ArrayList<>();
		for (int i = 0; i < 54; i++) full.add(ItemStack.EMPTY);
		full.set(49, status("Remember the pattern!"));
		menu.initializeContents(100, full, ItemStack.EMPTY);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(model.state() == ChronomatronModel.State.REMEMBER, "full-content update reaches listener");
	}
	private static void set(TestMenu menu, int slot, ItemStack stack) {
		menu.setItem(slot, menu.getStateId() + 1, stack);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
	}
	private static ItemStack tile(boolean red, boolean glint) {
		ItemLike item = registeredItem(red ? "red_terracotta" : "blue_terracotta");
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static ItemStack status(String text) {
		ItemStack stack = new ItemStack(Items.CLOCK);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(text));
		return stack;
	}
	private static ItemStack glass(boolean red, boolean glint) {
		ItemLike item = registeredItem(red ? "red_stained_glass" : "blue_stained_glass");
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static Item registeredItem(String path) {
		Identifier id = Identifier.withDefaultNamespace(path);
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (!id.equals(BuiltInRegistries.ITEM.getKey(item))) {
			throw new AssertionError("Missing expected vanilla item " + id);
		}
		return item;
	}
	private static final class TestMenu extends AbstractContainerMenu {
		TestMenu() {
			super(null, 1);
			SimpleContainer inventory = new SimpleContainer(54);
			for (int i = 0; i < 54; i++) addSlot(new Slot(inventory, i, 0, 0));
		}
		@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
		@Override public boolean stillValid(Player player) { return true; }
	}
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
	private static void requireKotlinValueClass(Class<?> type, String... expectedAccessors) {
		boolean kotlinMetadata = Arrays.stream(type.getDeclaredAnnotations()).anyMatch(annotation ->
			annotation.annotationType().getName().equals("kotlin.Metadata"));
		boolean explicitAccessors = Arrays.stream(expectedAccessors).allMatch(name -> {
			try {
				var method = type.getMethod(name);
				return method.getParameterCount() == 0
					&& !java.lang.reflect.Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() != void.class;
			} catch (NoSuchMethodException missing) {
				return false;
			}
		});
		require(java.lang.reflect.Modifier.isFinal(type.getModifiers()) && !type.isRecord()
			&& kotlinMetadata && explicitAccessors,
			"Kotlin value class has explicit Java accessors and no Java record metadata: " + type.getName());
	}
	private static void requireKotlinType(Class<?> type) {
		boolean kotlinMetadata = Arrays.stream(type.getDeclaredAnnotations()).anyMatch(annotation ->
			annotation.annotationType().getName().equals("kotlin.Metadata"));
		require(java.lang.reflect.Modifier.isFinal(type.getModifiers()) && !type.isRecord() && kotlinMetadata,
			"Kotlin class metadata is retained without Java record metadata: " + type.getName());
	}
	private static void requireEnumConstants(Class<? extends Enum<?>> type, String... expected) {
		require(type.isEnum() && Arrays.stream(type.getEnumConstants())
			.map(value -> ((Enum<?>) value).name()).toList().equals(Arrays.asList(expected)),
			"Java enum metadata remains available for " + type.getName());
	}
}
