package geiler.addons.client.dungeon.odin;

import java.lang.reflect.Modifier;

/** Focused checks for the Livid snapshot's active-feature and dungeon-phase gate. */
public final class DungeonLividSnapshotGateChecks {
	private DungeonLividSnapshotGateChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		preservesJavaFacadeAbi();
		check(DungeonLividSnapshotGate.shouldScan(true, true, 5, false),
			"enabled Livid feature scans during the Floor 5 Livid phase");
		check(!DungeonLividSnapshotGate.shouldScan(false, true, 5, false),
			"disabled module or Livid setting clears the snapshot");
		check(!DungeonLividSnapshotGate.shouldScan(true, false, 5, false),
			"outside a dungeon the snapshot is not active");
		check(!DungeonLividSnapshotGate.shouldScan(true, true, 4, false),
			"other floors do not scan for Livid");
		check(!DungeonLividSnapshotGate.shouldScan(true, true, 5, true),
			"the Mob ESP phase gate clears the snapshot");
	}

	private static void preservesJavaFacadeAbi() {
		try {
			var method = DungeonLividSnapshotGate.class.getMethod("shouldScan", boolean.class, boolean.class,
				int.class, boolean.class);
			var constructor = DungeonLividSnapshotGate.class.getDeclaredConstructor();
			check(Modifier.isFinal(DungeonLividSnapshotGate.class.getModifiers()),
				"the Livid snapshot gate remains a final utility class");
			check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == boolean.class,
				"shouldScan retains its public static (boolean, boolean, int, boolean) -> boolean ABI");
			check(Modifier.isPrivate(constructor.getModifiers()),
				"the Livid snapshot gate remains non-instantiable");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the Livid snapshot gate Java ABI is preserved", error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
