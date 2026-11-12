package geiler.addons.client.dungeon.odin;

import java.util.Map;

/** Bundled Odin room signatures, covered by the shipped Odin BSD-3-Clause notice. */
public final class OdinDungeonRoomCatalog {
	private OdinDungeonRoomCatalog() { }

	public static CatalogResult bundled() {
		return OdinDungeonRoomCatalogLogic.bundled();
	}

	static CatalogResult parse(String json) {
		return OdinDungeonRoomCatalogLogic.parse(json);
	}

	public record RoomData(String name, String type, String shape, int crypts, int maxSecrets, int trappedChests) { }
	public record CatalogResult(boolean available, int rows, Map<Integer, RoomData> byCore, String reason) {
		public CatalogResult {
			byCore = byCore == null ? Map.of() : Map.copyOf(byCore);
			reason = reason == null ? "" : reason;
		}
		public RoomData find(int core) { return byCore.get(core); }
		private static CatalogResult unavailable(String reason) { return new CatalogResult(false, 0, Map.of(), reason); }
	}
}
