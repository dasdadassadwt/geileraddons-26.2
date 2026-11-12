package geiler.addons.client.dungeon;

import geiler.addons.client.dungeon.odin.OdinDungeonMapGrid;
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerMap;
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerRoster;

import java.util.List;

/** Focused checks for continuous dungeon-map player projection and teleport-only snapping. */
public final class DungeonRoomPlayerProjectionChecks {
	private DungeonRoomPlayerProjectionChecks() { }

	public static void run() {
		projectsContinuouslyAcrossRoomGaps();
		acceptsConnectorCoordinatesAndRejectsOutsideCoordinates();
		keepsMarkerRotationAlignedWithOdin();
		pairsOrderedMapDecorationsOnlyWithACompleteRoster();
		usesNamedLoadedPositionsWhenMapPairingIsUnavailable();
		usesCurrentTabOrderAndCachedDeadClass();
		snapsOnlyForSpatialDiscontinuities();
	}

	public static void main(String[] args) { run(); }

	private static void projectsContinuouslyAcrossRoomGaps() {
		DungeonRoomPlayerMarkerGeometry.MapPosition xBefore =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-169.5, -184.0);
		DungeonRoomPlayerMarkerGeometry.MapPosition xAfter =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-168.5, -184.0);
		require(xBefore != null && xAfter != null, "X-seam positions remain projectable");
		close(1.0 / DungeonGridRules.CELL_STRIDE, xAfter.x() - xBefore.x(),
			"one block of X movement stays one block of map movement across the connector gap");
		close(0.0, xAfter.z() - xBefore.z(), "X movement does not alter the projected Z coordinate");
		close(20.0 / DungeonGridRules.CELL_STRIDE,
			(xAfter.x() - xBefore.x()) * (16 + 4),
			"the 16px room plus 4px map gap keeps the marker continuous at an X seam");

		DungeonRoomPlayerMarkerGeometry.MapPosition zBefore =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-184.0, -169.5);
		DungeonRoomPlayerMarkerGeometry.MapPosition zAfter =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-184.0, -168.5);
		require(zBefore != null && zAfter != null, "Z-seam positions remain projectable");
		close(1.0 / DungeonGridRules.CELL_STRIDE, zAfter.z() - zBefore.z(),
			"one block of Z movement stays one block of map movement across the connector gap");
		close(0.0, zAfter.x() - zBefore.x(), "Z movement does not alter the projected X coordinate");

		var beforeCell = OdinDungeonPlayerMap.projectLoadedPosition("Local", true, "Mage",
			-168.01, -184.0, 0.0f);
		var afterCell = OdinDungeonPlayerMap.projectLoadedPosition("Local", true, "Mage",
			-167.99, -184.0, 0.0f);
		require(beforeCell != null && afterCell != null && beforeCell.x() != afterCell.x(),
			"the room-cell index can change without changing the continuous map position");
		double beforeMapX = beforeCell.x() + beforeCell.offsetX();
		double afterMapX = afterCell.x() + afterCell.offsetX();
		close(0.02 / DungeonGridRules.CELL_STRIDE, afterMapX - beforeMapX,
			"cell changes preserve the small world-space movement rather than jumping a room stride");
	}

	private static void acceptsConnectorCoordinatesAndRejectsOutsideCoordinates() {
		DungeonRoomPlayerMarkerGeometry.MapPosition connector =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-169.0, -169.0);
		require(connector != null, "the inter-room connector block does not drop the player marker");
		var connectorMarker = OdinDungeonPlayerMap.projectLoadedPosition("Local", true, "Mage",
			-169.0, -169.0, 0.0f);
		require(connectorMarker != null && connectorMarker.x() == 0 && connectorMarker.z() == 0
			&& connectorMarker.offsetX() > 0.9 && connectorMarker.offsetZ() > 0.9,
			"connector positions retain their near-edge location in their 32-block stride");

		DungeonRoomPlayerMarkerGeometry.MapPosition first =
			DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-200.0, -200.0);
		require(first != null && first.x() == 0.0 && first.z() == 0.0,
			"the first dungeon coordinate maps to the origin");
		require(DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-200.01, -180.0) == null
			&& DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(-180.0, -9.0) == null,
			"positions outside the dungeon grid do not produce markers");
		require(DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(Double.NaN, -180.0) == null,
			"non-finite coordinates do not produce markers");
	}

	private static void keepsMarkerRotationAlignedWithOdin() {
		require(DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_U == 2
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_V == 0
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_WIDTH == 5
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_HEIGHT == 7
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_TEXTURE_SIZE == 8,
			"the player marker uses Odin's 5x7 map-decoration sprite crop from the 8x8 atlas");
		close(Math.PI, DungeonRoomPlayerMarkerGeometry.facingRotationRadians(0.0f),
			"yaw zero follows Odin's 180-degree player-marker rotation");
		close(Math.PI * 1.5, DungeonRoomPlayerMarkerGeometry.facingRotationRadians(90.0f),
			"interpolated yaw 90 follows Odin's 270-degree marker rotation");
		close(Math.PI * 0.5, DungeonRoomPlayerMarkerGeometry.facingRotationRadians(-90.0f),
			"interpolated yaw -90 follows Odin's 90-degree marker rotation");
	}

	private static void pairsOrderedMapDecorationsOnlyWithACompleteRoster() {
		var layout = new OdinDungeonMapGrid.Layout(true, 16, 20, 22, 22, null);
		var frame = new DungeonMapRoomDetector.SavedMapDecoration(0, 0, 0, true);
		var firstIcon = new DungeonMapRoomDetector.SavedMapDecoration(-34, 16, 4, false);
		var secondIcon = new DungeonMapRoomDetector.SavedMapDecoration(80, 52, 8, false);
		var roster = List.of(
			new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("Downed", "", true),
			new OdinDungeonPlayerMap.TabPlayer("Bob", "Tank", false));
		var local = new DungeonRoomOverlayGeometry.PlayerMarker(5, 5, "Local", true,
			0.5, 0.5, "Healer", 25.0f);

		var projection = OdinDungeonPlayerMap.projectWithDiagnostics(layout, roster,
			List.of(frame, firstIcon, secondIcon), List.of(local), "Local");
		var markers = projection.players();
		require(markers.size() == 3, "ordered map decorations show unloaded teammates and the local entity marker");
		var alice = markers.get(0);
		var bob = markers.get(1);
		require(alice.name().equals("Alice") && alice.x() == 1 && alice.z() == 2,
			"the first non-frame map icon pairs with the first living roster player");
		close(0.25, alice.offsetX(), "the map decoration X is projected through calibrated map pixels");
		close(0.5, alice.offsetZ(), "the map decoration Z is projected through calibrated map pixels");
		close(-90.0, alice.yawDegrees(), "the map decoration's four-step heading reaches the shared marker renderer");
		require(bob.name().equals("Bob") && bob.x() == 4 && bob.z() == 3,
			"dead roster entries are skipped while preserving the order of living teammates");
		close(0.1, bob.offsetX(), "the second icon retains its fractional X tile position");
		close(0.4, bob.offsetZ(), "the second icon retains its fractional Z tile position");
		require(markers.get(2).localPlayer() && markers.get(2).x() == 5
			&& projection.diagnostics().contains("pairing=ordered")
			&& projection.diagnostics().contains("paired=2")
			&& projection.diagnostics().contains("deadRoster=1"),
			"local position stays entity-based and diagnostics report bounded pairing counts");
	}

	private static void usesNamedLoadedPositionsWhenMapPairingIsUnavailable() {
		var layout = new OdinDungeonMapGrid.Layout(true, 16, 20, 22, 22, null);
		var roster = List.of(
			new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("Bob", "Archer", false));
		var alice = new DungeonRoomOverlayGeometry.PlayerMarker(1, 2, "Alice", false,
			0.25, 0.75, "Mage", 15.0f);
		var local = new DungeonRoomOverlayGeometry.PlayerMarker(5, 5, "Local", true,
			0.5, 0.5, "Healer", 25.0f);
		var mismatchedIcon = new DungeonMapRoomDetector.SavedMapDecoration(80, 52, 8, false);

		var projection = OdinDungeonPlayerMap.projectWithDiagnostics(layout, roster,
			List.of(mismatchedIcon), List.of(alice, local), "Local");
		require(projection.players().size() == 2, "mismatched icon counts do not shift roster identities");
		var mappedAlice = projection.players().getFirst();
		require(mappedAlice.name().equals("Alice") && mappedAlice.x() == 1 && mappedAlice.z() == 2,
			"only an explicitly name-matched loaded entity is used as a fallback");
		require(projection.players().stream().noneMatch(marker -> marker.name().equals("Bob"))
			&& projection.diagnostics().contains("pairing=countMismatch")
			&& projection.diagnostics().contains("pairRejected=1")
			&& projection.diagnostics().contains("fallback=1"),
			"the unmatched map icon is rejected instead of being assigned to a teammate");
	}

	private static void usesCurrentTabOrderAndCachedDeadClass() {
		var cached = List.of(
			new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("Bob", "Tank", false));
		var current = OdinDungeonPlayerRoster.currentDungeonRows(List.of(
			"[1] [MVP+] Bob (DEAD)",
			"[2] Alice (Archer L)",
			"[3] Spectator (MVP++)"), cached);
		require(current.size() == 2 && current.get(0).name().equals("Bob")
			&& current.get(0).dead() && current.get(0).dungeonClass().equals("Tank"),
			"current rows preserve tab order and dead teammates keep cached class identity");
		require(current.get(1).name().equals("Alice") && current.get(1).dungeonClass().equals("Archer")
			&& !current.get(1).dead(), "living class data comes from the current tab row");
	}

	private static void snapsOnlyForSpatialDiscontinuities() {
		require(!DungeonRoomPlayerMarkerGeometry.shouldSnap(-169.5, -184.0, -168.5, -184.0),
			"ordinary X movement through a room connector remains interpolated");
		require(!DungeonRoomPlayerMarkerGeometry.shouldSnap(-184.0, -169.5, -184.0, -168.5),
			"ordinary Z movement through a room connector remains interpolated");
		require(DungeonRoomPlayerMarkerGeometry.shouldSnap(-170.0, -184.0, -154.0, -184.0),
			"a 16-block X discontinuity snaps immediately");
		require(DungeonRoomPlayerMarkerGeometry.shouldSnap(-184.0, -170.0, -184.0, -154.0),
			"a 16-block Z discontinuity snaps immediately");
	}

	private static void close(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 0.000001) throw new AssertionError(message
			+ " (expected " + expected + ", got " + actual + ")");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
