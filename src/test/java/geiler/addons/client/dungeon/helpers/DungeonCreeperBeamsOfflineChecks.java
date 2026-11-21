package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.dungeon.DungeonGridRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure checks for the bundled Odin catalog, its rotated loaded-block matching, and fail-closed states. */
public final class DungeonCreeperBeamsOfflineChecks {
	private static final int ROOM_BASE_X = -64;
	private static final int ROOM_BASE_Z = -128;

	private DungeonCreeperBeamsOfflineChecks() { }

	public static void run() {
		checkDungeonSoundRulesAbi();
		checkRecordAbi();
		checkRecordConstructorSemantics();
		DungeonCreeperBeamsCatalog.CatalogResult catalog = DungeonCreeperBeamsCatalog.bundled();
		checkBundledCatalog(catalog);
		checkExactRoomGate();
		checkMatchingAndRotations(catalog);
		checkUnknownInputs(catalog);
		checkEndpointConfirmation(catalog);
		checkResolvedRotationSurvivesProgress(catalog);
		checkDisplayOnlyGuidance(catalog);
		checkFrozenSolutionKeepsPairIndices(catalog);
	}

	/** A completed pair must be flagged done without renumbering (re-coloring) the other pairs. */
	private static void checkFrozenSolutionKeepsPairIndices(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		DungeonCreeperBeamsRules.Detection initial = detectAtRotation(catalog, 0);
		List<DungeonCreeperBeamsRules.Pair> frozen = initial.pairs();
		require(frozen.size() >= DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS, "fixture has a full frozen solution");
		require(DungeonCreeperBeamsRules.stablePairProgress(frozen, initial).stream().noneMatch(done -> done),
			"no pair is done before any progress");
		List<DungeonCreeperBeamsRules.Pair> remaining = new ArrayList<>(frozen);
		remaining.remove(0);
		DungeonCreeperBeamsRules.Detection afterFirst = new DungeonCreeperBeamsRules.Detection(0, remaining, null);
		List<Boolean> flags = DungeonCreeperBeamsRules.stablePairProgress(frozen, afterFirst);
		require(flags.size() == frozen.size() && flags.get(0)
			&& flags.subList(1, flags.size()).stream().noneMatch(done -> done),
			"only the completed pair is done; every other pair keeps its index");
		remaining.remove(0);
		flags = DungeonCreeperBeamsRules.stablePairProgress(frozen,
			new DungeonCreeperBeamsRules.Detection(0, remaining, null));
		require(flags.get(0) && flags.get(1) && flags.subList(2, flags.size()).stream().noneMatch(done -> done),
			"a second completed pair leaves later indices unchanged");
		require(DungeonCreeperBeamsRules.stablePairProgress(frozen, null).stream().allMatch(done -> done),
			"a missing detection marks everything done instead of shifting indices");
	}

	private static void checkDungeonSoundRulesAbi() {
		try {
			Class<?> rules = DungeonSoundRules.class;
			require(java.lang.reflect.Modifier.isPublic(rules.getMethod("classify", String.class).getModifiers())
				&& java.lang.reflect.Modifier.isStatic(rules.getMethod("classify", String.class).getModifiers())
				&& rules.getMethod("classify", String.class).getReturnType() == DungeonSoundRules.Kind.class,
				"sound classification retains its public static Java signature");
			require(rules.getMethod("decide", String.class, boolean.class, boolean.class, boolean.class)
				.getReturnType() == DungeonSoundRules.Decision.class
				&& java.lang.reflect.Modifier.isStatic(rules.getMethod("decide", String.class, boolean.class,
					boolean.class, boolean.class).getModifiers()),
				"sound decision retains its public static Java signature");
			require(rules.getMethod("isCreeperBeamHit", String.class).getReturnType() == boolean.class
				&& rules.getMethod("shouldReplaceCreeperBeamHit", String.class, boolean.class,
					boolean.class, boolean.class).getReturnType() == boolean.class,
				"Creeper Beams sound gates retain their public Java signatures");
			require(rules.getField("WEIRDOS_CORRECT_CLUE_SOUND").get(null)
				.equals("minecraft:entity.firework_rocket.large_blast")
				&& rules.getField("WEIRDOS_CORRECT_CLUE_VOLUME").getFloat(null) == 2.0f
				&& rules.getField("WEIRDOS_CORRECT_CLUE_PITCH").getFloat(null) == 1.0f,
				"Weirdos sound constants keep their Java names and values");
			require(DungeonSoundRules.Decision.class.isRecord()
				&& java.util.Arrays.stream(DungeonSoundRules.Decision.class.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList()
					.equals(List.of("kind", "playDing", "cancelOriginal")),
				"sound Decision retains its Java record shape");
			require(DungeonSoundRules.classify(null) == DungeonSoundRules.Kind.OTHER
				&& !DungeonSoundRules.decide(null, true, true, true).playDing()
				&& !DungeonSoundRules.decide(null, true, true, true).cancelOriginal(),
				"null sound IDs remain classified as harmless unknown sounds");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Dungeon sound rule Java ABI changed", exception);
		}
	}

	private static void checkRecordAbi() {
		requireRecord(DungeonCreeperBeamsRules.WorldPosition.class, "x", "y", "z");
		requireRecord(DungeonCreeperBeamsRules.ObservedBlock.class, "position", "blockKind");
		requireRecord(DungeonCreeperBeamsRules.Endpoint.class, "x", "y", "z");
		requireRecord(DungeonCreeperBeamsRules.Pair.class, "first", "second");
		requireValueClass(DungeonCreeperBeamsRules.Detection.class,
			new String[] { "rotation", "pairs", "unknownReason" }, int.class, List.class,
			DungeonCreeperBeamsRules.UnknownReason.class);
		requireRecord(DungeonCreeperBeamsCatalog.Point.class, "x", "y", "z");
		requireRecord(DungeonCreeperBeamsCatalog.Pair.class, "first", "second");
		requireValueClass(DungeonCreeperBeamsCatalog.CatalogResult.class,
			new String[] { "available", "pairs", "reason" }, boolean.class, List.class, String.class);
		boolean privateCandidateRecord = false;
		for (Class<?> nested : DungeonCreeperBeamsRules.class.getDeclaredClasses()) {
			if (nested.getSimpleName().equals("OrientationCandidate") && nested.isRecord()) {
				privateCandidateRecord = true;
				break;
			}
		}
		require(privateCandidateRecord, "the private orientation candidate retains its record shape");
		require(new DungeonCreeperBeamsRules.Endpoint(1, 2, 3).toString()
			.equals("Endpoint[x=1, y=2, z=3]"), "record diagnostic strings retain Java record formatting");
	}

	private static void requireRecord(Class<?> type, String... expectedComponents) {
		require(type.isRecord(), type.getSimpleName() + " remains a JVM record");
		java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
		require(components.length == expectedComponents.length,
			type.getSimpleName() + " retains its record component count");
		for (int index = 0; index < components.length; index++) {
			require(components[index].getName().equals(expectedComponents[index]),
				type.getSimpleName() + " retains component " + expectedComponents[index]);
		}
	}

	private static void requireValueClass(Class<?> type, String[] accessors, Class<?>... componentTypes) {
		require(!type.isRecord(), type.getSimpleName() + " retains normalization without JVM record metadata");
		try {
			require(type.getConstructor(componentTypes) != null,
				type.getSimpleName() + " retains its public Java constructor");
			for (int index = 0; index < accessors.length; index++) {
				require(type.getMethod(accessors[index]).getReturnType() == componentTypes[index],
					type.getSimpleName() + " retains accessor " + accessors[index]);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(type.getSimpleName() + " Java value API changed", exception);
		}
	}

	private static void checkRecordConstructorSemantics() {
		DungeonCreeperBeamsRules.Endpoint endpoint = new DungeonCreeperBeamsRules.Endpoint(1, 2, 3);
		DungeonCreeperBeamsRules.Pair pair = new DungeonCreeperBeamsRules.Pair(endpoint,
			new DungeonCreeperBeamsRules.Endpoint(4, 5, 6));
		List<DungeonCreeperBeamsRules.Pair> detectionPairs = new ArrayList<>(List.of(pair));
		DungeonCreeperBeamsRules.Detection detection =
			new DungeonCreeperBeamsRules.Detection(1, detectionPairs, null);
		detectionPairs.clear();
		require(detection.pairs().equals(List.of(pair)),
			"the Detection compact constructor snapshots its pair list");
		boolean detectionListImmutable = false;
		try {
			detection.pairs().clear();
		} catch (UnsupportedOperationException expected) {
			detectionListImmutable = true;
		}
		require(detectionListImmutable, "Detection exposes an immutable pair list");
		require(new DungeonCreeperBeamsRules.Detection(1, null, null).pairs().isEmpty(),
			"Detection normalizes a null pair list to an empty list");
		var equalDetection = new DungeonCreeperBeamsRules.Detection(1, List.of(pair), null);
		var expectedDetection = new ExpectedDetection(1, List.of(pair), null);
		require(detection.equals(equalDetection) && equalDetection.equals(detection)
			&& detection.hashCode() == expectedDetection.hashCode()
			&& detection.toString().equals("Detection[rotation=1, pairs=[" + pair + "], unknownReason=null]"),
			"Detection retains record-style equality, hashing, and diagnostic text after list normalization");

		DungeonCreeperBeamsCatalog.Pair catalogPair = new DungeonCreeperBeamsCatalog.Pair(
			new DungeonCreeperBeamsCatalog.Point(1, 70, 2),
			new DungeonCreeperBeamsCatalog.Point(3, 71, 4));
		List<DungeonCreeperBeamsCatalog.Pair> catalogPairs = new ArrayList<>(List.of(catalogPair));
		DungeonCreeperBeamsCatalog.CatalogResult catalog =
			new DungeonCreeperBeamsCatalog.CatalogResult(true, catalogPairs, null);
		catalogPairs.clear();
		require(catalog.pairs().equals(List.of(catalogPair)) && catalog.reason().isEmpty(),
			"CatalogResult copies pairs and normalizes a null reason");
		boolean catalogListImmutable = false;
		try {
			catalog.pairs().clear();
		} catch (UnsupportedOperationException expected) {
			catalogListImmutable = true;
		}
		require(catalogListImmutable, "CatalogResult exposes an immutable pair list");
		var equalCatalog = new DungeonCreeperBeamsCatalog.CatalogResult(true, List.of(catalogPair), "");
		var expectedCatalog = new ExpectedCatalogResult(true, List.of(catalogPair), "");
		require(catalog.equals(equalCatalog) && equalCatalog.equals(catalog)
			&& catalog.hashCode() == expectedCatalog.hashCode()
			&& catalog.toString().equals("CatalogResult[available=true, pairs=[" + catalogPair + "], reason=]"),
			"CatalogResult retains record-style equality, hashing, and diagnostic text after list normalization");
		DungeonCreeperBeamsCatalog.CatalogResult nullFields =
			new DungeonCreeperBeamsCatalog.CatalogResult(false, null, null);
		require(nullFields.pairs().isEmpty() && nullFields.reason().isEmpty(),
			"CatalogResult normalizes null list and reason components");
	}

	private record ExpectedDetection(int rotation, List<DungeonCreeperBeamsRules.Pair> pairs,
		DungeonCreeperBeamsRules.UnknownReason unknownReason) { }

	private record ExpectedCatalogResult(boolean available, List<DungeonCreeperBeamsCatalog.Pair> pairs,
		String reason) { }

	private static void checkExactRoomGate() {
		require(DungeonCreeperBeamsRules.isCreeperBeamsRoom("Creeper Beams"),
			"the exact catalog room name enables Creeper Beams scanning");
		require(!DungeonCreeperBeamsRules.isCreeperBeamsRoom("Blaze")
			&& !DungeonCreeperBeamsRules.isCreeperBeamsRoom("Three Weirdos")
			&& !DungeonCreeperBeamsRules.isCreeperBeamsRoom("creeper beams")
			&& !DungeonCreeperBeamsRules.isCreeperBeamsRoom(null),
			"other, differently-cased, and unknown room names cannot enable Creeper Beams guidance or hit authorization");
		boolean knownEndpoint = true;
		require(DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", true, true,
			DungeonCreeperBeamsRules.isCreeperBeamsRoom("Creeper Beams") && knownEndpoint)
			&& !DungeonSoundRules.shouldReplaceCreeperBeamHit("minecraft:block.glass.hit", true, true,
				DungeonCreeperBeamsRules.isCreeperBeamsRoom("Blaze") && knownEndpoint),
			"the exact room gate keeps optional hit replacement limited to the existing active-feature and known-endpoint path");
	}

	private static void checkBundledCatalog(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		require(catalog.available(), "the bundled BSD-3 Odin catalog loads");
		require(catalog.pairs().size() == 12,
			"the repeated source row is removed while Odin's reverse-directed row remains");
		require(catalog.pairs().getFirst().equals(new DungeonCreeperBeamsCatalog.Pair(
			new DungeonCreeperBeamsCatalog.Point(15, 74, 15),
			new DungeonCreeperBeamsCatalog.Point(15, 84, 13))), "the exact first Odin pair is retained");
		require(catalog.pairs().getLast().equals(new DungeonCreeperBeamsCatalog.Pair(
			new DungeonCreeperBeamsCatalog.Point(3, 76, 18),
			new DungeonCreeperBeamsCatalog.Point(26, 78, 12))), "the exact last Odin pair is retained");
		require(!DungeonCreeperBeamsCatalog.parse("[]").available(), "an empty catalog fails closed");
		require(!DungeonCreeperBeamsCatalog.parse("[[0,0,0,1,1,1]]").available(),
			"a catalog below the minimum pair count fails closed");
		require(!DungeonCreeperBeamsCatalog.parse("[[0,68,0,31,68,1],[0,69,0,1,69,1],"
			+ "[0,70,0,1,70,1],[0,71,0,1,71,1],[0,72,0,1,72,1]]").available(),
			"out-of-room coordinates fail closed");
	}

	private static void checkMatchingAndRotations(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		DungeonCreeperBeamsRules.Detection baseline = detectAtRotation(catalog, 0);
		require(baseline.known() && baseline.pairs().size() == catalog.pairs().size(),
			"the exact catalog pairs are selected from loaded sea-lantern endpoints");
		for (int rotation = 1; rotation < 4; rotation++) {
			DungeonCreeperBeamsRules.Detection rotated = detectAtRotation(catalog, rotation);
			require(rotated.known() && rotated.rotation() == rotation
				&& localPairKeys(rotated.pairs()).equals(catalogPairKeys(catalog, rotation)),
				"catalog matching remains stable for room rotation " + rotation + " (got "
					+ rotated.rotation() + "/" + rotated.unknownReason() + ", pairs=" + rotated.pairs().size() + ")");
		}
	}

	private static void checkUnknownInputs(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		List<DungeonCreeperBeamsRules.ObservedBlock> observations = observations(catalog, 0, true);
		require(DungeonCreeperBeamsRules.detect(false, ROOM_BASE_X, ROOM_BASE_Z, catalog, observations)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.UNLOADED_ROOM_VOLUME,
			"an unloaded room volume is not read or matched");
		DungeonCreeperBeamsCatalog.CatalogResult unavailable = DungeonCreeperBeamsCatalog.parse("[]");
		require(DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, unavailable, observations)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.CATALOG_UNAVAILABLE,
			"a missing or malformed bundled catalog cannot produce guidance");

		List<DungeonCreeperBeamsRules.ObservedBlock> incomplete = new ArrayList<>(observations);
		incomplete.removeFirst();
		require(DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, catalog, incomplete)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.INCOMPLETE_ENDPOINT_OBSERVATIONS,
			"a partial endpoint sample cannot become a catalog match");

		List<DungeonCreeperBeamsRules.ObservedBlock> duplicate = new ArrayList<>(observations);
		duplicate.add(observations.getFirst());
		require(DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, catalog, duplicate)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.DUPLICATE_OR_INVALID_OBSERVATION,
			"duplicate observation coordinates fail closed");

		List<DungeonCreeperBeamsRules.ObservedBlock> none = observations(catalog, 0, false);
		require(DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, catalog, none)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.NO_CATALOG_MATCH,
			"nonmatching blocks do not generate estimated pairings");

		List<DungeonCreeperBeamsRules.ObservedBlock> allLanterns = observations(catalog, 0, false).stream()
			.map(block -> new DungeonCreeperBeamsRules.ObservedBlock(block.position(),
				DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN)).toList();
		require(DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, catalog, allLanterns)
			.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.AMBIGUOUS_ORIENTATION,
			"multiple catalog rotations fail closed");
	}

	private static void checkDisplayOnlyGuidance(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		DungeonCreeperBeamsRules.Detection detection = detectAtRotation(catalog, 0);
		List<String> lines = DungeonCreeperBeamsRules.guidanceLines(detection, ROOM_BASE_X, ROOM_BASE_Z);
		require(lines.size() > 1 && lines.getFirst().startsWith("Catalog match · rotation ")
			&& lines.stream().skip(1).allMatch(line -> (line.matches(
				"Pair \\d+: \\(-?\\d+,-?\\d+,-?\\d+\\) ↔ \\(-?\\d+,-?\\d+,-?\\d+\\)"))
				|| line.endsWith("more catalog pairs not shown")),
			"the HUD identifies catalog pair coordinates");
		require(lines.stream().noneMatch(line -> line.toLowerCase().contains("click")
			|| line.toLowerCase().contains("interact") || line.toLowerCase().contains("press")),
			"guidance never asks for or automates player input");
	}

	private static void checkResolvedRotationSurvivesProgress(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		Object level = new Object();
		String room = "F7|map|creeper-beams-progress";
		DungeonCreeperBeamsEndpointTracker tracker = new DungeonCreeperBeamsEndpointTracker();
		List<DungeonCreeperBeamsRules.ObservedBlock> observations = observations(catalog, 0, true);
		DungeonCreeperBeamsRules.Detection initial = DungeonCreeperBeamsRules.detect(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, observations);
		require(initial.known() && initial.rotation() == 0,
			"a full initial observation establishes the room rotation uniquely before progress");
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, initial, observations).isEmpty(),
			"unprogressed lantern pairs do not ding");
		Set<DungeonCreeperBeamsRules.Pair> reported = new java.util.HashSet<>();
		boolean crossedFiveToFour = false;

		for (int completed = 1; completed <= catalog.pairs().size(); completed++) {
			observations = observationsWithProgressedPairs(catalog, 0, completed);
			DungeonCreeperBeamsRules.Detection fresh = DungeonCreeperBeamsRules.detect(true,
				ROOM_BASE_X, ROOM_BASE_Z, catalog, observations);
			int remainingLanternPairs = countSeaLanternPairs(catalog, 0, observations);
			if (remainingLanternPairs == DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS) {
				require(fresh.known(), "the normal matcher stays known with five unprogressed pairs");
			} else if (remainingLanternPairs == DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS - 1) {
				crossedFiveToFour = true;
				require(fresh.unknownReason() == DungeonCreeperBeamsRules.UnknownReason.NO_CATALOG_MATCH,
					"the normal matcher becomes unknown at the five-to-four sea-lantern threshold");
			}
			DungeonCreeperBeamsRules.Detection stable = DungeonCreeperBeamsRules.detectResolvedRotation(true,
				ROOM_BASE_X, ROOM_BASE_Z, catalog, observations, initial.rotation());
			require(stable.known() && stable.rotation() == initial.rotation(),
				"the cached unique rotation preserves a complete mapping after pair progress " + completed);
			List<DungeonCreeperBeamsRules.Pair> transitions = tracker.update(level, room, ROOM_BASE_X,
				ROOM_BASE_Z, catalog, stable, observations);
			Set<DungeonCreeperBeamsRules.Pair> expectedNew = newlyProgressedPairs(catalog, 0, completed, reported);
			require(new java.util.HashSet<>(transitions).equals(expectedNew),
				"the cached mapping reports every newly prismarine pair once after " + completed
					+ " completed pairs (expected " + expectedNew.size() + ", got " + transitions.size() + ")");
			reported.addAll(transitions);
		}
		require(crossedFiveToFour, "the progress sequence exercises the actual five-to-four threshold");
		require(reported.size() == catalog.pairs().size(),
			"all pairs continue reporting through the final beam after crossing the five-to-four threshold");

		tracker.clearConfirmation();
		DungeonCreeperBeamsRules.Detection stableAfterToggle = DungeonCreeperBeamsRules.detectResolvedRotation(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, observations, initial.rotation());
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, stableAfterToggle,
			observations).isEmpty(), "disabling and re-enabling guidance does not replay an already-reported pair ding");
		require(!DungeonCreeperBeamsRules.detectResolvedRotation(true, ROOM_BASE_X, ROOM_BASE_Z,
			catalog, observations, -1).known(), "an invalid or absent cached rotation cannot guess a room mapping");
	}

	private static void checkEndpointConfirmation(DungeonCreeperBeamsCatalog.CatalogResult catalog) {
		Object level = new Object();
		String room = "F7|map|room-a";
		DungeonCreeperBeamsEndpointTracker tracker = new DungeonCreeperBeamsEndpointTracker();
		List<DungeonCreeperBeamsRules.ObservedBlock> active = observations(catalog, 0, true);
		DungeonCreeperBeamsRules.Detection detection = DungeonCreeperBeamsRules.detect(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, active);
		DungeonCreeperBeamsRules.Endpoint progressedEndpoint = DungeonCreeperBeamsRules.endpoints(detection)
			.stream()
			.filter(endpoint -> detection.pairs().stream()
				.filter(pair -> pair.first().equals(endpoint) || pair.second().equals(endpoint))
				.count() == 1)
			.sorted(java.util.Comparator.comparingInt(DungeonCreeperBeamsRules.Endpoint::x)
				.thenComparingInt(DungeonCreeperBeamsRules.Endpoint::y)
				.thenComparingInt(DungeonCreeperBeamsRules.Endpoint::z))
			.findFirst()
			.orElseThrow(() -> new AssertionError("fixture requires an endpoint used by exactly one catalog pair"));
		DungeonCreeperBeamsRules.Pair progressedPair = detection.pairs().stream()
			.filter(pair -> pair.first().equals(progressedEndpoint) || pair.second().equals(progressedEndpoint))
			.findFirst().orElseThrow();
		DungeonCreeperBeamsRules.Endpoint partner = progressedPair.first().equals(progressedEndpoint)
			? progressedPair.second() : progressedPair.first();
		DungeonCreeperBeamsRules.Endpoint survivingEndpoint = DungeonCreeperBeamsRules.endpoints(detection).stream()
			.filter(endpoint -> !endpoint.equals(progressedEndpoint) && !endpoint.equals(partner))
			.findFirst().orElseThrow();
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, detection, active).isEmpty(),
			"initial sea-lantern candidates do not trigger the pair ding");
		require(tracker.contains(level, room, progressedEndpoint),
			"a complete unique scan confirms current room endpoints");

		List<DungeonCreeperBeamsRules.ObservedBlock> progressed = active.stream().map(block -> {
			DungeonCreeperBeamsRules.WorldPosition position = block.position();
			if (position.x() == progressedEndpoint.x() && position.y() == progressedEndpoint.y()
				&& position.z() == progressedEndpoint.z())
				return new DungeonCreeperBeamsRules.ObservedBlock(position,
					DungeonCreeperBeamsRules.BlockKind.PRISMARINE);
			return block;
		}).toList();
		DungeonCreeperBeamsRules.Detection afterProgress = DungeonCreeperBeamsRules.detect(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, progressed);
		require(afterProgress.known(), "one prismarine endpoint leaves the catalog rotation identifiable");
		List<DungeonCreeperBeamsRules.Pair> newProgress = tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z,
			catalog, afterProgress, progressed);
		require(newProgress.size() == 1 && newProgress.getFirst().equals(progressedPair),
			"a complete unique scan reports exactly the pair whose catalog endpoint became prismarine");
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, afterProgress, progressed).isEmpty(),
			"the same observed prismarine pair cannot ding twice");
		require(tracker.contains(level, room, progressedEndpoint) && tracker.contains(level, room, partner),
			"a progressed pair keeps its prismarine endpoint and sea-lantern partner confirmed");

		List<DungeonCreeperBeamsRules.ObservedBlock> changedPair = progressed.stream().map(block -> {
			DungeonCreeperBeamsRules.WorldPosition position = block.position();
			if (position.x() == partner.x() && position.y() == partner.y() && position.z() == partner.z())
				return new DungeonCreeperBeamsRules.ObservedBlock(position, DungeonCreeperBeamsRules.BlockKind.OTHER);
			return block;
		}).toList();
		DungeonCreeperBeamsRules.Detection afterUnexpectedChange = DungeonCreeperBeamsRules.detect(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, changedPair);
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, afterUnexpectedChange, changedPair).isEmpty(),
			"an unexpected partner block does not emit a pair ding");
		require(!tracker.contains(level, room, progressedEndpoint) && !tracker.contains(level, room, partner),
			"an unexpected block in a formerly confirmed pair drops both old coordinates");

		DungeonCreeperBeamsRules.Detection stale = DungeonCreeperBeamsRules.detect(true,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, observations(catalog, 0, false));
		require(!stale.known(), "a scan with no matching endpoints is unknown");
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, stale,
			observations(catalog, 0, false)).isEmpty(), "an unknown scan does not emit pair transitions");
		require(!tracker.contains(level, room, survivingEndpoint),
			"an unknown fresh scan clears previously confirmed coordinates");

		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, detection, active).isEmpty(),
			"returning to the same room after an unknown scan does not replay a confirmed pair ding");
		require(!tracker.contains(level, "F7|map|room-b", survivingEndpoint)
			&& !tracker.contains(level, room, survivingEndpoint),
			"a room identity change invalidates old confirmations, including on return");
		require(tracker.update(level, room, ROOM_BASE_X, ROOM_BASE_Z, catalog, detection, active).isEmpty(),
			"the still-confirmed pair stays deduplicated before changing worlds");
		require(!tracker.contains(new Object(), room, survivingEndpoint)
			&& !tracker.contains(level, room, survivingEndpoint),
			"a world identity change invalidates old confirmations, including on return");
		List<DungeonCreeperBeamsRules.Pair> newWorldProgress = tracker.update(new Object(), room,
			ROOM_BASE_X, ROOM_BASE_Z, catalog, afterProgress, progressed);
		require(newWorldProgress.size() == 1 && newWorldProgress.getFirst().equals(progressedPair),
			"a new world scope can report its own newly observed pair once");
	}

	private static DungeonCreeperBeamsRules.Detection detectAtRotation(
		DungeonCreeperBeamsCatalog.CatalogResult catalog, int actualRotation) {
		return DungeonCreeperBeamsRules.detect(true, ROOM_BASE_X, ROOM_BASE_Z, catalog,
			observations(catalog, actualRotation, true));
	}

	private static List<DungeonCreeperBeamsRules.ObservedBlock> observations(
		DungeonCreeperBeamsCatalog.CatalogResult catalog, int actualRotation, boolean activateCatalogPairs) {
		Map<DungeonCreeperBeamsRules.WorldPosition, DungeonCreeperBeamsRules.BlockKind> blocks = new HashMap<>();
		for (DungeonCreeperBeamsRules.WorldPosition position
			: DungeonCreeperBeamsRules.catalogWorldPositions(ROOM_BASE_X, ROOM_BASE_Z, catalog)) {
			blocks.put(position, DungeonCreeperBeamsRules.BlockKind.OTHER);
		}
		if (activateCatalogPairs) {
			for (DungeonCreeperBeamsCatalog.Pair pair : catalog.pairs()) {
				activate(blocks, pair.first(), actualRotation);
				activate(blocks, pair.second(), actualRotation);
			}
		}
		return blocks.entrySet().stream().map(entry -> new DungeonCreeperBeamsRules.ObservedBlock(
			entry.getKey(), entry.getValue())).toList();
	}

	private static List<DungeonCreeperBeamsRules.ObservedBlock> observationsWithProgressedPairs(
		DungeonCreeperBeamsCatalog.CatalogResult catalog, int rotation, int progressedPairs) {
		Map<DungeonCreeperBeamsRules.WorldPosition, DungeonCreeperBeamsRules.BlockKind> blocks = new HashMap<>();
		for (DungeonCreeperBeamsRules.ObservedBlock block : observations(catalog, rotation, true)) {
			blocks.put(block.position(), block.blockKind());
		}
		for (int index = 0; index < Math.min(progressedPairs, catalog.pairs().size()); index++) {
			DungeonCreeperBeamsCatalog.Point point = catalog.pairs().get(index).first();
			DungeonGridRules.Position rotated = DungeonGridRules.toWorld(rotation, point.x(), point.z());
			DungeonCreeperBeamsRules.WorldPosition world = new DungeonCreeperBeamsRules.WorldPosition(
				ROOM_BASE_X + rotated.x(), point.y(), ROOM_BASE_Z + rotated.z());
			blocks.put(world, DungeonCreeperBeamsRules.BlockKind.PRISMARINE);
		}
		return blocks.entrySet().stream().map(entry -> new DungeonCreeperBeamsRules.ObservedBlock(
			entry.getKey(), entry.getValue())).toList();
	}

	private static Set<DungeonCreeperBeamsRules.Pair> newlyProgressedPairs(
		DungeonCreeperBeamsCatalog.CatalogResult catalog, int rotation, int progressedPairs,
		Set<DungeonCreeperBeamsRules.Pair> alreadyReported) {
		List<DungeonCreeperBeamsRules.ObservedBlock> observations = observationsWithProgressedPairs(
			catalog, rotation, progressedPairs);
		Map<DungeonCreeperBeamsRules.Endpoint, DungeonCreeperBeamsRules.BlockKind> states = new HashMap<>();
		for (DungeonCreeperBeamsRules.ObservedBlock block : observations) {
			states.put(new DungeonCreeperBeamsRules.Endpoint(block.position().x(), block.position().y(),
				block.position().z()), block.blockKind());
		}
		Set<DungeonCreeperBeamsRules.Pair> result = new java.util.HashSet<>();
		for (DungeonCreeperBeamsRules.Pair pair : DungeonCreeperBeamsRules.catalogPairsForRotation(
			ROOM_BASE_X, ROOM_BASE_Z, catalog, rotation)) {
			var first = states.get(pair.first());
			var second = states.get(pair.second());
			if ((first == DungeonCreeperBeamsRules.BlockKind.PRISMARINE
				|| second == DungeonCreeperBeamsRules.BlockKind.PRISMARINE)
				&& (first == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN
					|| first == DungeonCreeperBeamsRules.BlockKind.PRISMARINE)
				&& (second == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN
					|| second == DungeonCreeperBeamsRules.BlockKind.PRISMARINE)
				&& !alreadyReported.contains(pair)) result.add(pair);
		}
		return result;
	}

	private static int countSeaLanternPairs(DungeonCreeperBeamsCatalog.CatalogResult catalog, int rotation,
		List<DungeonCreeperBeamsRules.ObservedBlock> observations) {
		Map<DungeonCreeperBeamsRules.Endpoint, DungeonCreeperBeamsRules.BlockKind> states = new HashMap<>();
		for (DungeonCreeperBeamsRules.ObservedBlock block : observations) {
			states.put(new DungeonCreeperBeamsRules.Endpoint(block.position().x(), block.position().y(),
				block.position().z()), block.blockKind());
		}
		int count = 0;
		for (DungeonCreeperBeamsRules.Pair pair : DungeonCreeperBeamsRules.catalogPairsForRotation(
			ROOM_BASE_X, ROOM_BASE_Z, catalog, rotation)) {
			if (states.get(pair.first()) == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN
				&& states.get(pair.second()) == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN) count++;
		}
		return count;
	}

	private static void activate(Map<DungeonCreeperBeamsRules.WorldPosition,
		DungeonCreeperBeamsRules.BlockKind> blocks, DungeonCreeperBeamsCatalog.Point point, int rotation) {
		DungeonGridRules.Position rotated = DungeonGridRules.toWorld(rotation, point.x(), point.z());
		DungeonCreeperBeamsRules.WorldPosition world = new DungeonCreeperBeamsRules.WorldPosition(
			ROOM_BASE_X + rotated.x(), point.y(), ROOM_BASE_Z + rotated.z());
		blocks.put(world, DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN);
	}

	private static List<String> localPairKeys(List<DungeonCreeperBeamsRules.Pair> pairs) {
		List<String> result = new ArrayList<>();
		for (DungeonCreeperBeamsRules.Pair pair : pairs) {
			result.add(endpointKey(pair.first(), pair.second()));
		}
		return result;
	}

	private static List<String> catalogPairKeys(DungeonCreeperBeamsCatalog.CatalogResult catalog, int rotation) {
		List<String> result = new ArrayList<>();
		for (DungeonCreeperBeamsCatalog.Pair pair : catalog.pairs()) {
			DungeonGridRules.Position first = DungeonGridRules.toWorld(rotation,
				pair.first().x(), pair.first().z());
			DungeonGridRules.Position second = DungeonGridRules.toWorld(rotation,
				pair.second().x(), pair.second().z());
			DungeonCreeperBeamsRules.Endpoint firstEndpoint = new DungeonCreeperBeamsRules.Endpoint(
				ROOM_BASE_X + first.x(), pair.first().y(), ROOM_BASE_Z + first.z());
			DungeonCreeperBeamsRules.Endpoint secondEndpoint = new DungeonCreeperBeamsRules.Endpoint(
				ROOM_BASE_X + second.x(), pair.second().y(), ROOM_BASE_Z + second.z());
			result.add(endpointKey(firstEndpoint, secondEndpoint));
		}
		return result;
	}

	private static String endpointKey(DungeonCreeperBeamsRules.Endpoint first,
		DungeonCreeperBeamsRules.Endpoint second) {
		String a = (first.x() - ROOM_BASE_X) + ":" + first.y() + ":" + (first.z() - ROOM_BASE_Z);
		String b = (second.x() - ROOM_BASE_X) + ":" + second.y() + ":" + (second.z() - ROOM_BASE_Z);
		return a + "|" + b;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
