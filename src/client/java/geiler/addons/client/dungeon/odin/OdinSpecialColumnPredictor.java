package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonMapGeometry;

import java.util.List;
import java.util.Map;

/** Java ABI facade for Kotlin's Odin special-column prediction rules. */
public final class OdinSpecialColumnPredictor {
	private OdinSpecialColumnPredictor() { }

	public static List<Prediction> predict(OdinDungeonMapGrid.ScanResult map,
		Map<Integer, OdinDungeonMapCoreScanner.RoomCore> cores, int floorNumber, int dungeonPuzzleCount,
		boolean enabled) {
		return OdinSpecialColumnPredictorLogic.INSTANCE.predict(map, cores, floorNumber, dungeonPuzzleCount, enabled);
	}

	public static List<Prediction> predict(OdinDungeonMapGrid.ScanResult map,
		Map<Integer, OdinDungeonMapCoreScanner.RoomCore> cores, int floorNumber, int dungeonPuzzleCount,
		boolean enabled, int walkedIntoTile) {
		return OdinSpecialColumnPredictorLogic.INSTANCE.predict(map, cores, floorNumber, dungeonPuzzleCount,
			enabled, walkedIntoTile);
	}

	public record Prediction(int x, int z, List<DungeonMapGeometry.RoomType> possibleTypes) {
		public Prediction {
			possibleTypes = possibleTypes == null ? List.of() : List.copyOf(possibleTypes);
		}
	}
}
