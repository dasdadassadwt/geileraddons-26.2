package geiler.addons.client.dungeon;

import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Cell;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Direction;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedExit;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline checks for the Dungeon Map auto split: fixed blood-rush jobs, sides, balance and determinism. */
public final class DungeonAutoSplitChecks {
	private DungeonAutoSplitChecks() { }

	public static void main(String[] args) { run(); }

	private static final DungeonMapGeometry.RoomType N = DungeonMapGeometry.RoomType.ROOM;

	/**
	 * A 6-wide corridor along z = 0 plus a row below:
	 * x: 0=Entrance 1 2 3 4 5=Blood on z=0; rooms below each of x=1..4 on z=1.
	 */
	private static List<Room> map() {
		List<Room> rooms = new ArrayList<>();
		rooms.add(room("E", DungeonMapGeometry.RoomType.ENTRANCE, 0, 0, 0, Direction.EAST));
		for (int x = 1; x <= 4; x++) rooms.add(room("r" + x, N, x, 0, 3, Direction.WEST, Direction.EAST, Direction.SOUTH));
		rooms.add(room("B", DungeonMapGeometry.RoomType.BLOOD, 5, 0, 0, Direction.WEST));
		for (int x = 1; x <= 4; x++) rooms.add(room("d" + x, N, x, 1, 2, Direction.NORTH));
		return rooms;
	}

	private static Room room(String id, DungeonMapGeometry.RoomType type, int x, int z, int secrets, Direction... exits) {
		Cell cell = new Cell(x, z, type, id);
		List<ObservedExit> list = new ArrayList<>();
		for (Direction d : exits) list.add(new ObservedExit(cell, d));
		return new Room(id, id, type, List.of(cell), list, true, true, DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, secrets, "");
	}

	public static void run() {
		Map<String, Set<DungeonClass>> split = DungeonAutoSplit.split(map());
		require(split.get("B").equals(Set.of(DungeonClass.MAGE, DungeonClass.ARCHER, DungeonClass.HEALER)),
			"Mage, Archer and Healer rush blood together");
		require(split.get("r4").equals(Set.of(DungeonClass.MAGE)), "Mage takes the first room outside blood");
		require(!split.containsKey("E"), "the entrance belongs to nobody");
		for (String id : List.of("r1", "d1")) {
			Set<DungeonClass> owner = split.get(id);
			require(owner.equals(Set.of(DungeonClass.BERSERK)) || owner.equals(Set.of(DungeonClass.TANK)),
				id + " near the entrance goes to Berserk or Tank");
		}
		for (String id : List.of("d4", "r3")) {
			Set<DungeonClass> owner = split.get(id);
			require(owner.equals(Set.of(DungeonClass.ARCHER)) || owner.equals(Set.of(DungeonClass.HEALER)),
				id + " near blood goes to Archer or Healer");
		}
		require(split.size() == map().size() - 1, "every room but the entrance has an owner");

		List<Room> shuffled = new ArrayList<>(map());
		Collections.reverse(shuffled);
		require(DungeonAutoSplit.split(shuffled).equals(split), "room order does not change the result");

		List<Room> noBlood = new ArrayList<>(map());
		noBlood.removeIf(r -> r.type() == DungeonMapGeometry.RoomType.BLOOD);
		Map<String, Set<DungeonClass>> partial = DungeonAutoSplit.split(noBlood);
		require(partial.get("d4").equals(Set.of(DungeonClass.MAGE, DungeonClass.ARCHER, DungeonClass.HEALER)),
			"without blood the farthest known room stands in for it");
		require(!partial.isEmpty() && !partial.containsKey("E"), "a map that is not fully loaded still splits");
		List<Room> noEntrance = new ArrayList<>(map());
		noEntrance.removeIf(r -> r.type() == DungeonMapGeometry.RoomType.ENTRANCE);
		require(DungeonAutoSplit.split(noEntrance).isEmpty(), "no split until the entrance is on the map");

		simulatedMaps();
	}

	/**
	 * Simulated 6x6 dungeons (random spanning-tree doors, entrance and blood in far corners): the full map (illegal map)
	 * and a partly explored one (legal map, rooms within a few doors of the entrance) must both split.
	 */
	private static void simulatedMaps() {
		for (long seed = 1; seed <= 40; seed++) {
			java.util.Random random = new java.util.Random(seed);
			int size = 6;
			java.util.Map<Integer, java.util.EnumSet<Direction>> doors = new java.util.HashMap<>();
			for (int i = 0; i < size * size; i++) doors.put(i, java.util.EnumSet.noneOf(Direction.class));
			boolean[] seen = new boolean[size * size];
			java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
			seen[0] = true;
			stack.push(0);
			while (!stack.isEmpty()) {
				int at = stack.peek();
				List<Direction> options = new ArrayList<>();
				for (Direction d : Direction.values()) {
					int nx = at % size + d.dx(), nz = at / size + d.dz();
					if (nx >= 0 && nz >= 0 && nx < size && nz < size && !seen[nz * size + nx]) options.add(d);
				}
				if (options.isEmpty()) { stack.pop(); continue; }
				Direction d = options.get(random.nextInt(options.size()));
				int next = (at / size + d.dz()) * size + at % size + d.dx();
				doors.get(at).add(d);
				for (Direction back : Direction.values())
					if (back.dx() == -d.dx() && back.dz() == -d.dz()) doors.get(next).add(back);
				seen[next] = true;
				stack.push(next);
			}
			List<Room> full = new ArrayList<>();
			for (int i = 0; i < size * size; i++) {
				DungeonMapGeometry.RoomType type = i == 0 ? DungeonMapGeometry.RoomType.ENTRANCE
					: i == size * size - 1 ? DungeonMapGeometry.RoomType.BLOOD : N;
				full.add(room("c" + i, type, i % size, i / size, random.nextInt(6), doors.get(i).toArray(new Direction[0])));
			}
			Map<String, Set<DungeonClass>> all = DungeonAutoSplit.split(full);
			require(all.size() == full.size() - 1, "seed " + seed + ": the full map gives every room but the entrance an owner");
			for (DungeonClass c : List.of(DungeonClass.MAGE, DungeonClass.ARCHER, DungeonClass.HEALER, DungeonClass.BERSERK,
					DungeonClass.TANK)) {
				require(all.values().stream().anyMatch(o -> o.contains(c)), "seed " + seed + ": " + c + " gets rooms");
			}
			List<Room> legal = new ArrayList<>();
			for (Room r : full) {
				Cell cell = r.footprint().get(0);
				if (cell.x() + cell.z() <= 4) legal.add(r);
			}
			require(!DungeonAutoSplit.split(legal).isEmpty(), "seed " + seed + ": a partly explored map splits");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
