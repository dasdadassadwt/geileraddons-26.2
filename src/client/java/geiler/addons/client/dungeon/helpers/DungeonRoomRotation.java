package geiler.addons.client.dungeon.helpers;

import java.util.List;

/** Java ABI boundary for Kotlin-backed room rotation and probe rules. */
public final class DungeonRoomRotation {
	private DungeonRoomRotation() { }

	public static Result resolve(DungeonRoomCatalog.RoomData room, List<Integer> tileIndices,
		Integer highestBlock, ProbeLookup observations) {
		return DungeonRoomRotationLogic.resolve(room, tileIndices, highestBlock, observations);
	}

	/** Odin WorldScan's room-entry cutoff; single-player bypasses the height restriction. */
	public static boolean passesHeightGate(int playerBlockY, int highestBlock, boolean singlePlayer) {
		return DungeonRoomRotationLogic.passesHeightGate(playerBlockY, highestBlock, singlePlayer);
	}

	public enum Rotation {
		NORTH(15, 15), SOUTH(-15, -15), WEST(15, -15), EAST(-15, 15);
		private final int dx;
		private final int dz;
		Rotation(int dx, int dz) { this.dx = dx; this.dz = dz; }
		public int dx() { return dx; }
		public int dz() { return dz; }
	}

	public enum ProbeState { BLUE_TERRACOTTA, OTHER, UNAVAILABLE }
	@FunctionalInterface public interface ProbeLookup { ProbeState observe(ProbePosition position); }
	public record ProbePosition(int x, int y, int z) { }

	public record Result(Rotation rotation, ProbePosition clayPosition, String reason) {
		public Result { reason = reason == null ? "" : reason; }
		public boolean resolved() { return rotation != null && clayPosition != null; }
	}
}
