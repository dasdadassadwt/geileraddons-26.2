package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.dungeon.DungeonMapGeometry;

import java.util.List;
import java.util.Map;

/** Java ABI facade for Kotlin's Odin special-column prediction rules. */
public final class SpecialColumnPredictor {
	private SpecialColumnPredictor() { }

	public static List<Prediction> predict(DungeonMapGrid.ScanResult map,
		Map<Integer, DungeonMapCoreScanner.RoomCore> cores, int floorNumber, int dungeonPuzzleCount,
		boolean enabled) {
		return SpecialColumnPredictorLogic.INSTANCE.predict(map, cores, floorNumber, dungeonPuzzleCount, enabled);
	}

	public static List<Prediction> predict(DungeonMapGrid.ScanResult map,
		Map<Integer, DungeonMapCoreScanner.RoomCore> cores, int floorNumber, int dungeonPuzzleCount,
		boolean enabled, int walkedIntoTile) {
		return SpecialColumnPredictorLogic.INSTANCE.predict(map, cores, floorNumber, dungeonPuzzleCount,
			enabled, walkedIntoTile);
	}

	public record Prediction(int x, int z, List<DungeonMapGeometry.RoomType> possibleTypes) {
		public Prediction {
			possibleTypes = possibleTypes == null ? List.of() : List.copyOf(possibleTypes);
		}
	}
}
