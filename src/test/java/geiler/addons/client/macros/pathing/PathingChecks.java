package geiler.addons.client.macros.pathing;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Offline parity and round-trip checks of the pathfinder pieces that do not need a world: heap, route hash, disk formats. */
public final class PathingChecks {
	private PathingChecks() { }

	public static void run() throws Exception {
		checkNodeHeap();
		checkLatticeParity();
		checkAreaMemoryFile();
		checkWorldStoreFile();
	}

	private static PathNode node(double f) {
		PathNode node = new PathNode(new Terrain.Stand(0, 0, 0, 0.0, 1.0, false, false, false, 0.0, 0, 0.0));
		node.f = f;
		return node;
	}

	private static void checkNodeHeap() {
		NodeHeap heap = new NodeHeap();
		check(heap.isEmpty(), "a new heap is empty");
		Random random = new Random(7);
		double[] values = new double[2000];
		PathNode[] nodes = new PathNode[values.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = random.nextDouble() * 100.0;
			nodes[i] = node(values[i]);
			heap.insert(nodes[i]);
		}
		// Lower some queued nodes: the heap must put them in their new place.
		for (int i = 0; i < values.length; i += 7) {
			nodes[i].f = nodes[i].f - 40.0;
			values[i] -= 40.0;
			heap.decreased(nodes[i]);
		}
		java.util.Arrays.sort(values);
		for (int i = 0; i < values.length; i++) {
			check(!heap.isEmpty(), "the heap still has nodes");
			PathNode popped = heap.pop();
			check(popped.f == values[i], "nodes come out cheapest first, also after a decrease");
			check(popped.heapIndex == -1, "a popped node knows it is out");
		}
		check(heap.isEmpty(), "the heap is empty again");
	}

	/** The original's lattice hash, copied as written (unsigned 64-bit hex constants as Java longs). */
	private static double originalLattice(long seed, int x, int z) {
		long hash = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		hash ^= hash >>> 33;
		hash *= 0xFF51AFD7ED558CCDL;
		hash ^= hash >>> 33;
		hash *= 0xC4CEB9FE1A85EC53L;
		hash ^= hash >>> 33;
		return (hash >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
	}

	private static void checkLatticeParity() {
		Random random = new Random(11);
		for (int i = 0; i < 4000; i++) {
			long seed = random.nextLong();
			int x = random.nextInt(2000) - 1000;
			int z = random.nextInt(2000) - 1000;
			check(Double.compare(geiler.addons.client.macros.pathing.Path.lattice(seed, x, z), originalLattice(seed, x, z)) == 0,
				"the route weave hash matches the original for seed " + seed + " at " + x + "," + z);
		}
		check(geiler.addons.client.macros.pathing.Path.field(5L, 1.5, -2.25) >= -1.0 && geiler.addons.client.macros.pathing.Path.field(5L, 1.5, -2.25) <= 1.0, "the weave field stays within -1 to 1");
		check(geiler.addons.client.macros.pathing.Path.field(5L, 3.0, 4.0) == geiler.addons.client.macros.pathing.Path.field(5L, 3.0, 4.0), "the same seed and point always give the same weave");
	}

	private static void checkAreaMemoryFile() throws Exception {
		Path directory = Files.createTempDirectory("area-memory-checks");
		try {
			Path file = directory.resolve("area.learn");
			Long2FloatOpenHashMap data = new Long2FloatOpenHashMap();
			data.put(1L, 10.0f);
			data.put(-5L, 59.0f);
			data.put(99L, 200.0f);
			AreaMemory.write(file, data);
			Long2FloatOpenHashMap read = AreaMemory.read(file);
			check(read.size() == 3 && read.get(1L) == 10.0f && read.get(-5L) == 59.0f,
				"the learned places come back from the file");
			check(read.get(99L) == 60.0f, "a penalty above the ceiling is read back at the ceiling");
			check(AreaMemory.read(directory.resolve("missing.learn")).isEmpty(), "a missing file is nothing known");
			Files.writeString(file, "not a gzip file");
			check(AreaMemory.read(file).isEmpty(), "a damaged file is nothing known, not an exception");
			try (var listing = Files.list(directory)) {
				check(listing.count() == 1, "no temporary file is left behind");
			}
		} finally {
			deleteAll(directory);
		}
	}

	private static void checkWorldStoreFile() throws Exception {
		Path directory = Files.createTempDirectory("world-store-checks");
		try {
			Path file = directory.resolve("world.bin");
			Long2ObjectOpenHashMap<WorldStore.Section> data = new Long2ObjectOpenHashMap<>();
			data.put(7L, new WorldStore.Section(new int[] {1}, null, null));
			byte[] small = new byte[4096];
			small[0] = 1;
			small[4095] = 2;
			data.put(8L, new WorldStore.Section(new int[] {10, 20, 30}, small, null));
			char[] wide = new char[4096];
			wide[10] = 299;
			int[] palette = new int[300];
			for (int i = 0; i < palette.length; i++) palette[i] = i * 3;
			data.put(9L, new WorldStore.Section(palette, null, wide));
			WorldStore.write(file, data);
			Long2ObjectOpenHashMap<WorldStore.Section> read = WorldStore.read(file);
			check(read.size() == 3, "all sections come back from the file");
			check(read.get(7L).id(123) == 1, "a single-state section reads as that state everywhere");
			check(read.get(8L).id(0) == 20 && read.get(8L).id(4095) == 30 && read.get(8L).id(1) == 10,
				"a small-palette section keeps its bytes");
			check(read.get(9L).id(10) == 299 * 3 && read.get(9L).id(11) == 0, "a wide-palette section keeps its characters");
			check(WorldStore.read(directory.resolve("missing.bin")).isEmpty(), "a missing file is an empty store");
			Files.writeString(file, "garbage");
			check(WorldStore.read(file).isEmpty(), "a damaged file is an empty store, not an exception");
		} finally {
			deleteAll(directory);
		}
	}

	private static void deleteAll(Path directory) throws Exception {
		try (var listing = Files.list(directory)) {
			listing.forEach(path -> path.toFile().delete());
		}
		Files.deleteIfExists(directory);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
