package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGridRules;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Modifier;
import java.util.List;

/** Focused checks for clue-time chest positions and room/world cache invalidation. */
public final class DungeonWeirdosMarkerCacheChecks {
	private DungeonWeirdosMarkerCacheChecks() { }

	public static void run() {
		javaBoundaryAbiIsStable();
		markerOrderAndRetentionAreStable();
		DungeonWeirdosMarkerCache cache = new DungeonWeirdosMarkerCache();
		Object world = new Object();
		cache.put("F7|room-a|10|20|Three Weirdos", world, "Alice", 31, 69, 44, true);

		// The snapshot has no entity parameter: the clue-time coordinates remain after the NPC vanishes.
		List<DungeonWeirdosMarkerCache.Marker> afterNpcDisappears = cache.snapshot(
			"F7|room-a|10|20|Three Weirdos", world);
		require(afterNpcDisappears.size() == 1 && afterNpcDisappears.getFirst().speaker().equals("Alice")
			&& afterNpcDisappears.getFirst().x() == 31 && afterNpcDisappears.getFirst().y() == 69
			&& afterNpcDisappears.getFirst().z() == 44 && afterNpcDisappears.getFirst().correctChest(),
			"confirmed clue position and classification persist independently of the NPC entity");

		cache.clear();
		require(cache.snapshot("F7|room-a|10|20|Three Weirdos", world).isEmpty(),
			"solver reset clears clue-time markers");
		cache.put("F7|room-a|10|20|Three Weirdos", world, "Alice", 31, 69, 44, true);
		Object replacementWorld = new Object();
		cache.beginRoom("F7|room-a|10|20|Three Weirdos", replacementWorld);
		require(cache.snapshot("F7|room-a|10|20|Three Weirdos", replacementWorld).isEmpty(),
			"reusing a room signature in a new client world cannot reuse old coordinates");

		cache.put("F7|room-a|10|20|Three Weirdos", replacementWorld, "Alice", 31, 69, 44, true);
		cache.beginRoom("F7|room-b|10|20|Three Weirdos", replacementWorld);
		require(cache.snapshot("F7|room-b|10|20|Three Weirdos", replacementWorld).isEmpty(),
			"entering a different room clears the prior room's markers");
	}

	private static void javaBoundaryAbiIsStable() {
		try {
			Class<?> cache = DungeonWeirdosMarkerCache.class;
			require(!cache.isRecord() && cache.getAnnotation(kotlin.Metadata.class) != null,
				"Weirdos cache retains Kotlin class metadata without Java record metadata");
			cache.getConstructor();
			require(cache.getMethod("beginRoom", String.class, Object.class).getReturnType() == void.class
				&& cache.getMethod("put", String.class, Object.class, String.class, int.class, int.class,
					int.class, boolean.class).getReturnType() == void.class
				&& cache.getMethod("snapshot", String.class, Object.class).getReturnType() == List.class
				&& cache.getMethod("clear").getReturnType() == void.class,
				"Weirdos cache retains its public Java constructor and method descriptors");
			Class<?> markerType = DungeonWeirdosMarkerCache.Marker.class;
			require(!markerType.isRecord() && markerType.getAnnotation(kotlin.Metadata.class) != null,
				"Weirdos Marker keeps constructor normalization in a Kotlin class");
			require(markerType.getConstructor(String.class, int.class, int.class, int.class, boolean.class) != null
				&& markerType.getMethod("speaker").getReturnType() == String.class
				&& markerType.getMethod("x").getReturnType() == int.class
				&& markerType.getMethod("y").getReturnType() == int.class
				&& markerType.getMethod("z").getReturnType() == int.class
				&& markerType.getMethod("correctChest").getReturnType() == boolean.class,
				"Weirdos Marker retains its public constructor and record-style accessor names");
			var normalizedMarker = new DungeonWeirdosMarkerCache.Marker(null, 1, 2, 3, false);
			require(normalizedMarker.speaker().isEmpty(), "the Marker constructor still normalizes a null speaker");
			var equalMarker = new DungeonWeirdosMarkerCache.Marker("", 1, 2, 3, false);
			var expectedMarker = new ExpectedMarker("", 1, 2, 3, false);
			require(normalizedMarker.equals(equalMarker) && equalMarker.equals(normalizedMarker)
				&& normalizedMarker.hashCode() == expectedMarker.hashCode()
				&& normalizedMarker.toString().equals("Marker[speaker=, x=1, y=2, z=3, correctChest=false]"),
				"Weirdos Marker preserves record-style equality, hash, and diagnostic text after normalization");

			Class<?> identity = OdinDungeonRoomIdentity.class;
			require(Modifier.isPublic(identity.getMethod("currentPuzzleRoom", Minecraft.class).getModifiers())
				&& Modifier.isStatic(identity.getMethod("currentPuzzleRoom", Minecraft.class).getModifiers())
				&& identity.getMethod("currentPuzzleRoom", Minecraft.class).getReturnType()
					== OdinDungeonRoomIdentity.Snapshot.class,
				"current puzzle room retains its public static Java signature");
			require(identity.getMethod("mapIdentity", DungeonFloor.class, String.class,
				DungeonGridRules.Cell.class).getReturnType() == String.class,
				"room identity retains its public static Java signature");
			Class<?> snapshot = OdinDungeonRoomIdentity.Snapshot.class;
			require(snapshot.isRecord() && java.util.Arrays.stream(snapshot.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList()
				.equals(List.of("floor", "mapSignature", "cell", "geometry", "roomData")),
				"room Snapshot retains its Java record components and order");
			OdinDungeonRoomIdentity.Snapshot empty = new OdinDungeonRoomIdentity.Snapshot(null, null, null, null, null);
			require(empty.mapSignature().isEmpty() && empty.roomName() == null && empty.connectedCells().isEmpty(),
				"room Snapshot keeps null normalization and empty derived views");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Weirdos/room identity Java ABI changed", exception);
		}
	}

	private static void markerOrderAndRetentionAreStable() {
		DungeonWeirdosMarkerCache cache = new DungeonWeirdosMarkerCache();
		Object world = new Object();
		String room = "F7|room-order|10|20|Three Weirdos";
		cache.put(room, world, " Alice ", 1, 2, 3, false);
		cache.put(room, world, "duplicate ignored", 1, 2, 3, false);
		cache.put(room, world, "Bob", 4, 5, 6, false);
		cache.put(room, world, "Carol", 7, 8, 9, true);
		cache.put(room, world, "Dana", 10, 11, 12, true);
		List<DungeonWeirdosMarkerCache.Marker> markers = cache.snapshot(room, world);
		require(markers.size() == 3 && markers.get(0).speaker().equals("Alice")
			&& markers.get(1).speaker().equals("Bob") && markers.get(2).speaker().equals("Dana"),
			"wrong-marker insertion order and deduplication remain stable, with the latest correct marker last");
		boolean immutable = false;
		try {
			markers.clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "the marker snapshot remains an immutable copy");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record ExpectedMarker(String speaker, int x, int y, int z, boolean correctChest) { }
}
