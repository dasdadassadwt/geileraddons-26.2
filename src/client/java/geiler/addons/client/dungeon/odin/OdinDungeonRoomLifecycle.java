package geiler.addons.client.dungeon.odin;

import java.util.List;
import java.util.Set;

/** Run-local current-room and walked-into state consumed by the Odin-style map projection. */
public final class OdinDungeonRoomLifecycle {
	private final OdinDungeonRoomLifecycleCore core = new OdinDungeonRoomLifecycleCore();

	public Snapshot observe(Object levelIdentity, boolean inDungeon, boolean inBoss,
		String candidateRoomId, boolean roomReady, int playerTile, List<Integer> roomTiles) {
		return core.observe(levelIdentity, inDungeon, inBoss, candidateRoomId, roomReady, playerTile, roomTiles);
	}

	/** Stable map-room key from zero-based 6x6 tile indexes. */
	public static String roomId(List<Integer> tileIndices) {
		return OdinDungeonRoomLifecycleCore.roomId(tileIndices);
	}

	public Snapshot snapshot() { return core.snapshot(); }

	public void reset() { core.reset(); }

	public record Snapshot(boolean inDungeon, boolean inBoss, String currentRoomId,
		String enteredRoomId, Set<Integer> walkedIntoTiles) {
		public Snapshot {
			walkedIntoTiles = walkedIntoTiles == null ? Set.of() : Set.copyOf(walkedIntoTiles);
		}
		public boolean roomEntered() { return enteredRoomId != null; }
	}
}
