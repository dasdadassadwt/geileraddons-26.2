package geiler.addons.client.dungeon.odin;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure acceptance checks for observed TP Maze links and fail-closed route rendering. */
public final class DungeonTpMazeGraphChecks {
	private DungeonTpMazeGraphChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkCatalogFacadeValueContract();
		learnsOnlyUniqueDirectedTransitions();
		rejectsAmbiguousAndWeakCorrections();
		renderingRequiresCurrentObservedRoomState();
		projectsWorldCoordinatesWithoutTemplateRotation();
		worksWithObservedPadsAfterRoomRotation();
		infersOnlyOneUnvisitedInteriorFinalPad();
		keepsOdinPadVisualStatesDistinct();
		rulesRejectInvalidEvidenceAndRetainCatalogOrder();
		rulesPreserveLandingBoundsAndChoiceOrdering();
	}

	private static void learnsOnlyUniqueDirectedTransitions() {
		DungeonTpMazeGraph graph = new DungeonTpMazeGraph();
		var source = pad(0, 70, 0, 0.5, 70.5, 0.5);
		var destination = pad(32, 70, 0, 32.5, 70.5, 0.5);
		List<DungeonTpMazeGraph.Pad> pads = List.of(destination, source);
		check(graph.observeCorrection(pads, source.position(), destination.position(), true),
			"a large correction from one uniquely observed portal pad to another records one directed link");
		check(!graph.observeCorrection(pads, source.position(), destination.position(), true),
			"duplicate observations do not duplicate an existing link");
		check(graph.destinations(source.key()).equals(List.of(destination.key())),
			"observed destination is available from its source pad");
		check(graph.sources(destination.key()).equals(List.of(source.key())),
			"arrival guidance can identify the exact observed incoming link");
		check(graph.destinations(destination.key()).isEmpty(), "the reverse link is never inferred");
		check(graph.visibleLinks(pads, true, true, true, true).size() == 1,
			"a link is visible only while both observed pads are present");
	}

	private static void checkCatalogFacadeValueContract() {
		Class<?> pointType = DungeonTpMazeCatalog.Point.class;
		var components = pointType.getRecordComponents();
		check(pointType.isRecord() && java.util.Arrays.stream(components)
			.map(java.lang.reflect.RecordComponent::getName).toList().equals(List.of("x", "y", "z")),
			"TP Maze Point retains its native record components and order");
		Class<?> catalogType = DungeonTpMazeCatalog.CatalogResult.class;
		check(!catalogType.isRecord(),
			"TP Maze CatalogResult keeps constructor normalization in a Kotlin value class");
		try {
			check(catalogType.getConstructor(boolean.class, List.class, String.class) != null
				&& catalogType.getMethod("available").getReturnType() == boolean.class
				&& catalogType.getMethod("pads").getReturnType() == List.class
				&& catalogType.getMethod("reason").getReturnType() == String.class,
				"TP Maze CatalogResult retains its Java constructor and record-style accessors");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("TP Maze catalog Java value API changed", exception);
		}

		var pad = new DungeonTpMazeCatalog.Point(2, 69, 7);
		List<DungeonTpMazeCatalog.Point> input = new ArrayList<>(List.of(pad));
		var value = new DungeonTpMazeCatalog.CatalogResult(true, input, null);
		input.clear();
		check(value.available() && value.pads().equals(List.of(pad)) && value.reason().isEmpty(),
			"TP Maze CatalogResult snapshots pads and normalizes a null reason");
		boolean immutable = false;
		try {
			value.pads().clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(immutable, "TP Maze CatalogResult exposes an immutable pad snapshot");
		var equal = new DungeonTpMazeCatalog.CatalogResult(true, List.of(pad), "");
		var expected = new ExpectedCatalogResult(true, List.of(pad), "");
		check(value.equals(equal) && equal.equals(value) && value.hashCode() == expected.hashCode()
			&& value.toString().equals("CatalogResult[available=true, pads=[Point[x=2, y=69, z=7]], reason=]"),
			"TP Maze CatalogResult retains record-style equality, hash, and diagnostic text");
		var empty = new DungeonTpMazeCatalog.CatalogResult(false, null, null);
		check(empty.pads().isEmpty() && empty.reason().isEmpty(),
			"TP Maze CatalogResult normalizes null list and reason components");
	}

	private static void rejectsAmbiguousAndWeakCorrections() {
		var source = pad(0, 70, 0, 0.5, 70.5, 0.5);
		var nearby = pad(3, 70, 0, 3.0, 70.5, 0.5);
		var destination = pad(32, 70, 0, 32.5, 70.5, 0.5);
		var graph = new DungeonTpMazeGraph();
		var ambiguousBefore = new DungeonTpMazeGraph.Position(1.75, 70.5, 0.5);
		check(!graph.observeCorrection(List.of(source, nearby, destination), ambiguousBefore,
			destination.position(), true), "overlapping source-pad candidates reject a transition");
		check(!graph.observeCorrection(List.of(source, destination), source.position(), destination.position(), false),
			"an incomplete loaded scan cannot establish endpoints");
		var shortDestination = pad(4, 70, 0, 4.5, 70.5, 0.5);
		check(!graph.observeCorrection(List.of(source, shortDestination), source.position(),
			shortDestination.position(), true), "small position corrections are not treated as portal transitions");
		check(!graph.observeCorrection(List.of(source, destination), source.position(),
			new DungeonTpMazeGraph.Position(Double.NaN, 70.5, 0.5), true),
			"non-finite packet positions are rejected");
		check(!graph.observeCorrection(List.of(source, destination),
			new DungeonTpMazeGraph.Position(300, 70.5, 0.5), destination.position(), true),
			"a correction that starts outside every observed pad cannot add a guessed source");
	}

	private static void renderingRequiresCurrentObservedRoomState() {
		var graph = new DungeonTpMazeGraph();
		var source = pad(0, 70, 0, 0.5, 70.5, 0.5);
		var destination = pad(32, 70, 0, 32.5, 70.5, 0.5);
		List<DungeonTpMazeGraph.Pad> pads = List.of(source, destination);
		graph.observeCorrection(pads, source.position(), destination.position(), true);
		var links = graph.visibleLinks(pads, true, true, true, true);
		check(graph.visibleLinks(pads, true, false, true, true).isEmpty(),
			"incomplete scan hides observed links");
		check(graph.visibleLinks(pads, true, true, true, false).isEmpty(), "room changes hide observed links");
		check(graph.visibleLinks(pads, false, true, true, true).isEmpty(), "inactive solver hides observed links");
		check(DungeonTpMazeRenderGeometry.shouldRender(true, true, true, true, true, links),
			"render gate accepts one current complete loaded transition");
		check(!DungeonTpMazeRenderGeometry.shouldRender(true, true, true, true, false, links),
			"unloaded portal endpoints fail closed");
		check(!DungeonTpMazeRenderGeometry.shouldRender(false, true, true, true, true, links),
			"rendering is disabled outside the selected puzzle");
		graph.retainObservedPads(List.of(source), true);
		check(graph.linkCount() == 0, "a completed current scan prunes links whose physical pads disappeared");
	}

	private static void projectsWorldCoordinatesWithoutTemplateRotation() {
		var from = new DungeonTpMazeGraph.Position(-43.5, 70.5, 95.5);
		var to = new DungeonTpMazeGraph.Position(-11.5, 72.5, 95.5);
		var segment = DungeonTpMazeRenderGeometry.segment(from, to);
		check(segment != null && near(segment.from().x(), -43.5) && near(segment.from().y(), 70.65)
			&& near(segment.from().z(), 95.5) && near(segment.to().x(), -11.5)
			&& near(segment.to().y(), 72.65) && near(segment.to().z(), 95.5),
			"render projection preserves scanned world coordinates instead of rotating a room template");
		check(DungeonTpMazeRenderGeometry.segment(from,
			new DungeonTpMazeGraph.Position(Double.POSITIVE_INFINITY, 0, 0)) == null,
			"invalid projected coordinates produce no visual route");
	}

	private static void worksWithObservedPadsAfterRoomRotation() {
		var source = pad(4, 70, 2, 4.5, 70.5, 2.5);
		var destination = pad(20, 70, 2, 20.5, 70.5, 2.5);
		var rotatedSource = rotate90(source);
		var rotatedDestination = rotate90(destination);
		var graph = new DungeonTpMazeGraph();
		check(graph.observeCorrection(List.of(rotatedDestination, rotatedSource), rotatedSource.position(),
			rotatedDestination.position(), true),
			"a room-rotated layout still learns the transition from its actual loaded world coordinates");
		check(graph.destinations(rotatedSource.key()).equals(List.of(rotatedDestination.key())),
			"rotation does not require a canonical room coordinate table or yaw-based route guess");
	}

	private static void infersOnlyOneUnvisitedInteriorFinalPad() {
		var catalog = DungeonTpMazeCatalog.bundled();
		var pads = DungeonTpMazeRules.pads(-168, -136, catalog, 2);
		var canonical = DungeonTpMazeRules.pads(0, 0, catalog, 0);
		check(canonical.get(DungeonTpMazeRules.END_PAD_ID - 1).position().equals(
			new DungeonTpMazeRules.WorldPosition(15, 69, 14))
			&& canonical.get(DungeonTpMazeRules.START_PAD_ID - 1).position().equals(
				new DungeonTpMazeRules.WorldPosition(15, 69, 12)),
			"canonical pad roles keep the fixed end and start portals distinct");
		var visited = new java.util.HashSet<Integer>();
		for (int id = 1; id <= 27; id++) visited.add(id);
		DungeonTpMazeRules.Pad finalPad = DungeonTpMazeRules.uniqueFinalPad(pads, visited);
		check(finalPad != null && finalPad.id() == 28,
			"one remaining interior pad is inferred as final even while fixed start/end pads remain unvisited");
		check(DungeonTpMazeRules.finalPadSuggestion(false, pads, visited) == null,
			"Odin-parity mode does not infer a special final pad");
		check(DungeonTpMazeRules.finalPadSuggestion(true, pads, visited) != null
			&& DungeonTpMazeRules.finalPadSuggestion(true, pads, visited).id() == 28,
			"the optional non-Odin inference is available only when enabled");
		visited.add(28);
		check(DungeonTpMazeRules.uniqueFinalPad(pads, visited) == null,
			"no final pad is inferred when every interior pad has already been visited");
		visited.remove(28);
		visited.remove(27);
		check(DungeonTpMazeRules.uniqueFinalPad(pads, visited) == null,
			"two remaining interior pads remain ambiguous");
		check(DungeonTpMazeRules.uniqueFinalPad(pads.subList(0, 29), visited) == null,
			"incomplete canonical pad data cannot infer the final target");
	}

	private static void keepsOdinPadVisualStatesDistinct() {
		check(DungeonTpMazeRenderGeometry.padStyle(true, true, 1) == DungeonTpMazeRenderGeometry.PadStyle.VISITED,
			"visited pad styling wins even when a stale view set still contains the pad");
		check(DungeonTpMazeRenderGeometry.padStyle(false, true, 1) == DungeonTpMazeRenderGeometry.PadStyle.SINGLE_CANDIDATE,
			"one surviving view candidate has a distinct style");
		check(DungeonTpMazeRenderGeometry.padStyle(false, true, 3) == DungeonTpMazeRenderGeometry.PadStyle.MULTIPLE_CANDIDATES,
			"several surviving view candidates have a distinct style");
		check(DungeonTpMazeRenderGeometry.padStyle(false, false, 3) == DungeonTpMazeRenderGeometry.PadStyle.UNKNOWN,
			"other pads retain a neutral style");
		check(!DungeonTpMazeRenderGeometry.depthTested(DungeonTpMazeRenderGeometry.PadStyle.SINGLE_CANDIDATE),
			"the single confirmed green route remains visible through walls");
		check(DungeonTpMazeRenderGeometry.depthTested(DungeonTpMazeRenderGeometry.PadStyle.VISITED)
			&& DungeonTpMazeRenderGeometry.depthTested(DungeonTpMazeRenderGeometry.PadStyle.MULTIPLE_CANDIDATES)
			&& DungeonTpMazeRenderGeometry.depthTested(DungeonTpMazeRenderGeometry.PadStyle.UNKNOWN),
			"visited and ambiguous or unknown pads stay occluded");
		check(DungeonTpMazeRenderGeometry.tracerDepthTested(),
			"the cyan camera-to-pad hint stays occluded so only the green single-candidate pad shows through walls");
	}

	private static void rulesRejectInvalidEvidenceAndRetainCatalogOrder() {
		var catalog = DungeonTpMazeCatalog.bundled();
		check(catalog.available(), "the bundled TP Maze catalog is available for rule checks");
		List<DungeonTpMazeRules.WorldPosition> probes = DungeonTpMazeRules.probePositions(-168, -136, catalog);
		var expected = new LinkedHashSet<DungeonTpMazeRules.WorldPosition>();
		for (int rotation = 0; rotation < 4; rotation++) for (var point : catalog.pads()) {
			var rotated = geiler.addons.client.dungeon.DungeonGridRules.toWorld(rotation, point.x(), point.z());
			expected.add(new DungeonTpMazeRules.WorldPosition(-168 + rotated.x(), point.y(), -136 + rotated.z()));
		}
		check(probes.equals(List.copyOf(expected)),
			"probe positions preserve rotation-major, catalog-major insertion order and deduplicate consistently");
		check(DungeonTpMazeRules.probePositions(0, 0, null).isEmpty()
			&& DungeonTpMazeRules.pads(0, 0, null, 0).isEmpty()
			&& DungeonTpMazeRules.pads(0, 0, catalog, -1).isEmpty(),
			"missing catalogs and invalid rotations produce empty immutable results");
		check(DungeonTpMazeRules.detect(0, 0, null, null, false).unknownReason()
			== DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN,
			"an incomplete scan fails closed before inspecting catalog availability");
		check(DungeonTpMazeRules.detect(0, 0, null, null, true).unknownReason()
			== DungeonTpMazeRules.UnknownReason.CATALOG_UNAVAILABLE,
			"a complete scan without a catalog fails closed");
		check(DungeonTpMazeRules.detect(0, 0, catalog, List.of(), true).unknownReason()
			== DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN,
			"omitted required coordinates remain unknown even when the scan says complete");

		List<DungeonTpMazeRules.ObservedBlock> noPortals = new ArrayList<>();
		for (var position : probes) noPortals.add(new DungeonTpMazeRules.ObservedBlock(position, false));
		check(DungeonTpMazeRules.detect(-168, -136, catalog, noPortals, true).unknownReason()
			== DungeonTpMazeRules.UnknownReason.NO_CATALOG_MATCH,
			"complete loaded evidence with no matching portal layout is rejected");
		List<DungeonTpMazeRules.ObservedBlock> duplicate = new ArrayList<>(noPortals);
		duplicate.add(noPortals.getFirst());
		check(DungeonTpMazeRules.detect(-168, -136, catalog, duplicate, true).unknownReason()
			== DungeonTpMazeRules.UnknownReason.INVALID_OBSERVATION,
			"duplicate observations invalidate a detection instead of being silently merged");
		check(DungeonTpMazeRules.seedViewCandidates(null, null).isEmpty()
			&& DungeonTpMazeRules.seedViewCandidates(List.of(), Set.of(4, 7)).equals(Set.of(4, 7)),
			"candidate seeding preserves null and existing-set behavior");
		var normalized = new DungeonTpMazeRules.Detection(-1, null, null);
		check(normalized.pads().isEmpty() && !normalized.known()
			&& new DungeonTpMazeRules.Position(Double.NaN, 0, 0).finite() == false
			&& !new DungeonTpMazeRules.Bounds(0, 0, 0, 0, 1, 1).valid(),
			"value helper behavior and Detection constructor normalization stay intact");
	}

	private static void rulesPreserveLandingBoundsAndChoiceOrdering() {
		check(DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.5, 69.5, 0.5))
			&& !DungeonTpMazeRules.isTeleportLanding(null)
			&& !DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.25, 69.5, 0.5))
			&& !DungeonTpMazeRules.isTeleportLanding(new DungeonTpMazeRules.Position(1.5, 70.0, 0.5)),
			"only finite half-block landings at the exact maze Y are accepted");

		var onePad = List.of(mazePad(1, 0, 69, 0));
		check(DungeonTpMazeRules.padsIntersectingBounds(onePad,
			new DungeonTpMazeRules.Bounds(1.0, 69.0, 0.0, 2.0, 70.0, 1.0)).isEmpty(),
			"bounds that only touch a unit-pad edge do not count as an intersection");
		check(DungeonTpMazeRules.padsIntersectingBounds(onePad,
			new DungeonTpMazeRules.Bounds(0.99, 69.0, 0.0, 1.1, 70.0, 1.0)).equals(Set.of(1))
			&& DungeonTpMazeRules.padsIntersectingBounds(null,
				new DungeonTpMazeRules.Bounds(0, 69, 0, 1, 70, 1)).isEmpty(),
			"strictly overlapping bounds include the pad while null input remains empty");

		check(DungeonTpMazeRules.retainViewCandidates(onePad, Set.of(1), Set.of(),
			new DungeonTpMazeRules.Position(0.5, 70.5, -5.0), 0.0f, 0.0f, 6.0).equals(Set.of(1)),
			"view candidates include a pad intersected by the bounded forward ray");
		check(DungeonTpMazeRules.retainViewCandidates(onePad, Set.of(1), Set.of(), Set.of(1),
			new DungeonTpMazeRules.Position(0.5, 70.5, -5.0), 0.0f, 0.0f, 6.0).isEmpty()
			&& DungeonTpMazeRules.retainViewCandidates(onePad, Set.of(1), Set.of(1),
				new DungeonTpMazeRules.Position(0.5, 70.5, -5.0), 0.0f, 0.0f, 6.0).isEmpty(),
			"visited and currently occupied pads are excluded from view candidates");

		List<DungeonTpMazeRules.Pad> pads = List.of(
			mazePad(3, 5, 69, 5), mazePad(2, 5, 69, -5), mazePad(1, 0, 69, 0), mazePad(4, 0, 69, 5));
		check(DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(1), -90.0f).id() == 2,
			"equal yaw differences use the lower pad ID even when it appears later in catalog order");
		check(DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(1), Set.of(2, 3), -90.0f).id() == 3,
			"view candidates keep catalog ordering ahead of the yaw fallback");
		check(DungeonTpMazeRules.chooseNextPad(null, 1, Set.of(), 0.0f) == null
			&& DungeonTpMazeRules.chooseNextPad(pads, 29, Set.of(), 0.0f) == null
			&& DungeonTpMazeRules.chooseNextPad(pads, 1, Set.of(), Float.NaN) == null,
			"invalid route input and out-of-interior current pads produce no suggestion");
	}

	private static DungeonTpMazeRules.Pad mazePad(int id, int x, int y, int z) {
		return new DungeonTpMazeRules.Pad(id, new DungeonTpMazeRules.WorldPosition(x, y, z));
	}

	private static DungeonTpMazeGraph.Pad rotate90(DungeonTpMazeGraph.Pad pad) {
		return new DungeonTpMazeGraph.Pad(new DungeonTpMazeGraph.PadKey(-pad.key().anchorZ(),
			pad.key().anchorY(), pad.key().anchorX()), new DungeonTpMazeGraph.Position(-pad.position().z(),
			pad.position().y(), pad.position().x()));
	}

	private static DungeonTpMazeGraph.Pad pad(int x, int y, int z, double px, double py, double pz) {
		return new DungeonTpMazeGraph.Pad(new DungeonTpMazeGraph.PadKey(x, y, z),
			new DungeonTpMazeGraph.Position(px, py, pz));
	}

	private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1.0e-9; }

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record ExpectedCatalogResult(boolean available, List<DungeonTpMazeCatalog.Point> pads, String reason) { }
}
