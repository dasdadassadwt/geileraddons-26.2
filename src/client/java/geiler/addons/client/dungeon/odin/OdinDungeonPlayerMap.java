package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonMapRoomDetector;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry;
import java.util.List;

/** Projects local entity and ordered map-decoration player positions into the dungeon grid. */
public final class OdinDungeonPlayerMap {
	private OdinDungeonPlayerMap() { }

	public static List<DungeonRoomOverlayGeometry.PlayerMarker> project(
		OdinDungeonMapGrid.Layout layout,
		List<TabPlayer> tabPlayers,
		List<DungeonMapRoomDetector.SavedMapDecoration> decorations,
		List<DungeonRoomOverlayGeometry.PlayerMarker> loadedPlayers,
		String localPlayerName) {
		return OdinDungeonPlayerMapLogic.project(layout, tabPlayers, decorations, loadedPlayers, localPlayerName);
	}

	/**
	 * Compatibility overload for callers without the scanned map layout. Without that calibration,
	 * map decorations cannot be projected safely and matching loaded entities are used instead.
	 */
	public static Projection projectWithDiagnostics(
		List<TabPlayer> tabPlayers,
		List<DungeonMapRoomDetector.SavedMapDecoration> decorations,
		List<DungeonRoomOverlayGeometry.PlayerMarker> loadedPlayers,
		String localPlayerName) {
		return OdinDungeonPlayerMapLogic.projectWithDiagnostics(tabPlayers, decorations, loadedPlayers, localPlayerName);
	}

	/**
	 * Pairs the ordered non-frame map decorations with the ordered living, non-self tab roster as
	 * Odin does. Pairing is used only when the decoration count exactly matches that roster and the
	 * 6x6 map layout is valid; otherwise no positional identity is guessed and loaded entities are
	 * used only when their names match the roster.
	 */
	public static Projection projectWithDiagnostics(
		OdinDungeonMapGrid.Layout layout,
		List<TabPlayer> tabPlayers,
		List<DungeonMapRoomDetector.SavedMapDecoration> decorations,
		List<DungeonRoomOverlayGeometry.PlayerMarker> loadedPlayers,
		String localPlayerName) {
		return OdinDungeonPlayerMapLogic.projectWithDiagnostics(layout, tabPlayers, decorations, loadedPlayers, localPlayerName);
	}

	/** Projects an already-interpolated loaded-player position into the same 32-block map grid as Odin. */
	public static DungeonRoomOverlayGeometry.PlayerMarker projectLoadedPosition(String name, boolean localPlayer,
		String dungeonClass, double worldX, double worldZ, float yawDegrees) {
		return OdinDungeonPlayerMapLogic.projectLoadedPosition(name, localPlayer, dungeonClass,
			worldX, worldZ, yawDegrees);
	}

	public record TabPlayer(String name, String dungeonClass, boolean dead) {
		public TabPlayer {
			name = name == null ? "" : name.strip();
			dungeonClass = dungeonClass == null ? "" : dungeonClass.strip();
		}
	}

	public record Projection(List<DungeonRoomOverlayGeometry.PlayerMarker> players, int rosterCandidates,
		int loadedEntities, int mapDecorations, int loadedMatches, int unresolvedRoster, boolean localMarker,
		int pairedDecorations, int fallbackMarkers, int rejectedDecorationPairs, int deadRosterRows,
		String pairingStatus) {
		public Projection(List<DungeonRoomOverlayGeometry.PlayerMarker> players, int rosterCandidates,
			int loadedEntities, int mapDecorations, int loadedMatches, int unresolvedRoster, boolean localMarker) {
			this(players, rosterCandidates, loadedEntities, mapDecorations, loadedMatches, unresolvedRoster,
				localMarker, 0, 0, mapDecorations, 0, "unassigned");
		}

		public Projection {
			players = players == null ? List.of() : List.copyOf(players);
			rosterCandidates = bounded(rosterCandidates);
			loadedEntities = bounded(loadedEntities);
			mapDecorations = bounded(mapDecorations);
			loadedMatches = bounded(loadedMatches);
			unresolvedRoster = bounded(unresolvedRoster);
			pairedDecorations = bounded(pairedDecorations);
			fallbackMarkers = bounded(fallbackMarkers);
			rejectedDecorationPairs = bounded(rejectedDecorationPairs);
			deadRosterRows = bounded(deadRosterRows);
			pairingStatus = pairingStatus == null ? "unknown" : pairingStatus;
		}

		public String diagnostics() {
			return "roster=" + rosterCandidates + ",deadRoster=" + deadRosterRows + ",loadedEntities=" + loadedEntities
				+ ",decorations=" + mapDecorations + ",pairing=" + pairingStatus + ",paired=" + pairedDecorations
				+ ",pairRejected=" + rejectedDecorationPairs + ",loadedMatches=" + loadedMatches
				+ ",fallback=" + fallbackMarkers + ",unresolvedRoster=" + unresolvedRoster
				+ ",local=" + localMarker + ",localSource=" + (localMarker ? "client.player" : "missing")
				+ ",decorationIdentity=" + (pairedDecorations > 0 ? "ordered" : "unassigned");
		}

		private static int bounded(int value) { return Math.max(0, Math.min(64, value)); }
	}
}
