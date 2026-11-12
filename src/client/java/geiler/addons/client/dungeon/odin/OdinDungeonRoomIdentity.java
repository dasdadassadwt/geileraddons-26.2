package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGridRules;
import geiler.addons.client.dungeon.DungeonMapGeometry;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Read-only exact identity for the physically confirmed current Puzzle map cell. */
public final class OdinDungeonRoomIdentity {
	private OdinDungeonRoomIdentity() { }

	/**
	 * Returns the current physical cell and its exact optional Odin catalog row only when the map
	 * detector confirms a Puzzle tile and the player remains in that same dungeon grid cell. The
	 * snapshot includes the map geometry and its connected cell offsets; it does not infer rotation,
	 * a canonical corner, or any world transform from the room name.
	 */
	public static Snapshot currentPuzzleRoom(Minecraft client) {
		return OdinDungeonRoomIdentityLogic.currentPuzzleRoom(client);
	}

	/** Stable identity for a physically confirmed Puzzle cell, even while its optional room name is unavailable. */
	public static String mapIdentity(DungeonFloor floor, String mapSignature, DungeonGridRules.Cell cell) {
		return OdinDungeonRoomIdentityLogic.mapIdentity(floor, mapSignature, cell);
	}

	public record Snapshot(DungeonFloor floor, String mapSignature, DungeonGridRules.Cell cell,
		DungeonMapGeometry.RoomGeometry geometry, OdinDungeonRoomCatalog.RoomData roomData) {
		public Snapshot {
			mapSignature = mapSignature == null ? "" : mapSignature;
		}

		/** Exact catalog name, or null while the current center chunk or signature is unavailable. */
		public String roomName() { return roomData == null ? null : roomData.name(); }

		/** Immutable physical footprint offsets from the map detector's current cell. */
		public List<DungeonMapGeometry.CellOffset> connectedCells() {
			return geometry == null ? List.of() : geometry.connectedCells();
		}
	}
}
