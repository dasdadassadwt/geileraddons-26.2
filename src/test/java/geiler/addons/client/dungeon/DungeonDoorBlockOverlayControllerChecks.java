package geiler.addons.client.dungeon;

import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.DoorKind;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Focused offline checks for the Java boundary and Kotlin projection engine. */
public final class DungeonDoorBlockOverlayControllerChecks {
	private DungeonDoorBlockOverlayControllerChecks() { }

	public static void main(String[] args) {
		controllerInteropAbi();
		nullSafeCompatibilityBoundary();
		authoritativeUpdateAndReplacementLifecycle();
		existingGlassIsNotClaimed();
	}

	private static void controllerInteropAbi() {
		var controllerType = DungeonDoorBlockOverlayController.class;
		check(java.lang.reflect.Modifier.isFinal(controllerType.getModifiers()),
			"the controller facade remains final");
		try {
			check(java.lang.reflect.Modifier.isPrivate(controllerType.getDeclaredConstructor().getModifiers()),
				"the controller facade retains its private constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the controller facade retains its no-argument constructor", exception);
		}

		assertStaticMethod("authoritativeState", BlockState.class, ClientLevel.class, BlockPos.class);
		assertStaticMethod("rememberDoorSource", boolean.class, ClientLevel.class, DoorKind.class, BlockPos.class);
		assertStaticMethod("rememberStartSource", boolean.class,
			ClientLevel.class, BlockPos.class, BlockState.class);
		assertStaticMethod("miningState", BlockState.class,
			ClientLevel.class, BlockPos.class, BlockState.class);
		assertStaticMethod("retainedPositions", java.util.Set.class, ClientLevel.class, DoorKind.class);
		assertStaticMethod("reconcile", void.class, ClientLevel.class, Map.class);
		assertStaticMethod("onServerBlockState", void.class,
			ClientLevel.class, BlockPos.class, BlockState.class);
		assertStaticMethod("onServerSectionState", void.class,
			ClientLevel.class, BlockPos.class, BlockState.class);
		assertStaticMethod("onChunkInstalled", void.class, ClientLevel.class, int.class, int.class);
		assertStaticMethod("onChunkUnloaded", void.class, ClientLevel.class, int.class, int.class);
		assertStaticMethod("restoreAndClear", void.class, ClientLevel.class);
		assertStaticMethod("stats", Map.class, ClientLevel.class);
		assertStaticMethod("suppressedDetailLogCount", long.class, ClientLevel.class);
		assertStaticMethod("recordCoalescedDetailSuppressions", void.class, ClientLevel.class, long.class);

		assertValueTypeAbi(DungeonDoorBlockOverlayController.Projection.class,
			new String[]{"kind", "state"}, new Class<?>[]{DoorKind.class, BlockState.class});
		assertValueTypeAbi(DungeonDoorBlockOverlayController.TypeStats.class,
			new String[]{"targets", "loaded", "sourceAir", "sourceNonAir", "active", "writes", "restores",
				"failures", "miningReads", "miningSourceReturns", "miningFallbacks", "miningOwnershipMismatches"},
			new Class<?>[]{int.class, int.class, int.class, int.class, int.class, long.class, long.class, long.class,
				long.class, long.class, long.class, long.class});
		try {
			check(DungeonDoorBlockOverlayController.BlockAccess.class.isInterface()
				&& DungeonDoorBlockOverlayController.BlockAccess.class.getMethod("gameTick").isDefault(),
				"the injectable world boundary keeps its Java default game-tick method");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the world boundary retains its default game-tick method", exception);
		}

		var projection = new DungeonDoorBlockOverlayController.Projection(
			DoorKind.WITHER, Blocks.COAL_BLOCK.defaultBlockState());
		equals(projection, new DungeonDoorBlockOverlayController.Projection(
			DoorKind.WITHER, Blocks.COAL_BLOCK.defaultBlockState()),
			"the Kotlin projection value keeps structural equality");
		equals("Projection[kind=WITHER, state=" + Blocks.COAL_BLOCK.defaultBlockState() + "]", projection.toString(),
			"the projection value keeps the previous record-style diagnostic text");
	}

	private static void assertStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			var method = DungeonDoorBlockOverlayController.class.getDeclaredMethod(name, parameterTypes);
			check(java.lang.reflect.Modifier.isPublic(method.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				"the static Java controller ABI remains available for " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("missing static Java controller ABI method " + name, exception);
		}
	}

	private static void assertValueTypeAbi(Class<?> type, String[] names, Class<?>[] componentTypes) {
		check(java.lang.reflect.Modifier.isFinal(type.getModifiers()),
			type.getSimpleName() + " remains a final value type");
		check(names.length == componentTypes.length,
			type.getSimpleName() + " value accessors have matching component declarations");
		try {
			check(java.lang.reflect.Modifier.isPublic(type.getConstructor(componentTypes).getModifiers()),
				type.getSimpleName() + " retains its public component constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " is missing its public component constructor", exception);
		}
		for (int index = 0; index < names.length; index++) {
			try {
				var accessor = type.getDeclaredMethod(names[index]);
				check(java.lang.reflect.Modifier.isPublic(accessor.getModifiers())
					&& accessor.getReturnType() == componentTypes[index],
					type.getSimpleName() + " retains Java component accessor " + names[index]);
			} catch (NoSuchMethodException exception) {
				throw new AssertionError(type.getSimpleName() + " is missing component accessor " + names[index],
					exception);
			}
		}
	}

	private static void nullSafeCompatibilityBoundary() {
		var position = new BlockPos(1, 70, 2);
		var coal = Blocks.COAL_BLOCK.defaultBlockState();
		check(DungeonDoorBlockOverlayController.authoritativeState(null, position) == null,
			"the static authoritative-state entrypoint preserves its null-level guard");
		check(!DungeonDoorBlockOverlayController.rememberDoorSource(null, DoorKind.WITHER, position),
			"the static door-source entrypoint preserves its null-level guard");
		check(!DungeonDoorBlockOverlayController.rememberStartSource(null, position, coal),
			"the static Start-source entrypoint preserves its null-level guard");
		check(DungeonDoorBlockOverlayController.miningState(null, position, coal) == coal,
			"the static mining entrypoint passes through the observed state without a level");
		check(DungeonDoorBlockOverlayController.retainedPositions(null, DoorKind.START).isEmpty(),
			"the static retained-mask entrypoint returns an empty set without a level");
		DungeonDoorBlockOverlayController.reconcile(null, null);
		DungeonDoorBlockOverlayController.onServerBlockState(null, position, coal);
		DungeonDoorBlockOverlayController.onServerSectionState(null, position, coal);
		DungeonDoorBlockOverlayController.onChunkInstalled(null, 0, 0);
		DungeonDoorBlockOverlayController.onChunkUnloaded(null, 0, 0);
		DungeonDoorBlockOverlayController.restoreAndClear(null);
		check(DungeonDoorBlockOverlayController.stats(null).keySet().containsAll(
			java.util.Set.of(DoorKind.START, DoorKind.WITHER, DoorKind.BLOOD)),
			"the static stats entrypoint retains all three replacement types without a level");
		DungeonDoorBlockOverlayController.suppressedDetailLogCount(null);
		DungeonDoorBlockOverlayController.recordCoalescedDetailSuppressions(null, 0L);
	}

	private static void authoritativeUpdateAndReplacementLifecycle() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var position = new BlockPos(-20, 70, -19);
		long packed = position.asLong();
		var kind = DoorKind.WITHER;
		var coal = Blocks.COAL_BLOCK.defaultBlockState();
		var barrier = Blocks.BARRIER.defaultBlockState();
		var blackGlass = Blocks.STAINED_GLASS.pick(DyeColor.BLACK).defaultBlockState();
		var blueGlass = Blocks.STAINED_GLASS.pick(DyeColor.BLUE).defaultBlockState();
		var projection = new DungeonDoorBlockOverlayController.Projection(kind, blackGlass);

		world.serverSet(position, coal);
		check(engine.rememberDoorSource(world, kind, position),
			"a valid coal source can be retained before the Kotlin engine projects glass");
		engine.reconcile(world, Map.of(packed, projection));
		same(blackGlass, world.getBlockState(position), "the selected source receives local glass");
		equals(18, world.lastFlags, "the local projection keeps the existing client update flags");
		equals(1L, engine.stats(world).get(kind).writes(), "a successful projection write is counted");

		world.serverSet(position, barrier);
		engine.onServerBlockState(world, position, barrier);
		same(blackGlass, world.getBlockState(position), "a non-air server update keeps the glass projection");
		same(barrier, engine.authoritativeState(world, position),
			"the authoritative server state remains available behind projected glass");
		same(barrier, engine.miningState(world, position, blackGlass),
			"mining reads the authoritative state while the owned glass remains in the client world");

		engine.reconcile(world, Map.of(packed,
			new DungeonDoorBlockOverlayController.Projection(kind, blueGlass)));
		same(blueGlass, world.getBlockState(position),
			"changing the desired glass state restores the source before applying the replacement");
		equals(3L, engine.stats(world).get(kind).writes(),
			"server re-projection and a changed glass selection each count as successful writes");
		equals(1L, engine.stats(world).get(kind).restores(),
			"changing the desired glass state counts the intermediate source restoration");

		engine.reconcile(world, Map.of());
		same(barrier, world.getBlockState(position), "disabling restores the latest server-provided source");
		equals(2L, engine.stats(world).get(kind).restores(),
			"disabling records the final owned-projection restoration");
	}

	private static void existingGlassIsNotClaimed() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var position = new BlockPos(4, 71, -8);
		long packed = position.asLong();
		var kind = DoorKind.BLOOD;
		var source = Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState();
		var redGlass = Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState();

		world.serverSet(position, source);
		check(engine.rememberDoorSource(world, kind, position),
			"the 26.2 red dyed-terracotta source material remains selectable");
		world.serverSet(position, redGlass);
		engine.reconcile(world, Map.of(packed,
			new DungeonDoorBlockOverlayController.Projection(kind, redGlass)));
		equals(0L, engine.stats(world).get(kind).writes(),
			"glass already present at the source is recorded as unowned without writing");
		same(redGlass, engine.miningState(world, position, redGlass),
			"mining passes through glass that the controller did not write");

		engine.reconcile(world, Map.of());
		same(redGlass, world.getBlockState(position),
			"disabling never restores over a projection the controller did not own");
		equals(0L, engine.stats(world).get(kind).restores(),
			"unowned glass does not increment the restore counter");
	}

	private static final class FakeWorld implements DungeonDoorBlockOverlayController.BlockAccess {
		private final Object identity = new Object();
		private final Map<Long, BlockState> states = new HashMap<>();
		private int writeCalls;
		private int lastFlags;

		@Override public Object levelIdentity() { return identity; }
		@Override public boolean hasChunkAt(BlockPos position) { return true; }
		@Override public BlockState getBlockState(BlockPos position) {
			return states.getOrDefault(position.asLong(), Blocks.AIR.defaultBlockState());
		}
		@Override public boolean setBlock(BlockPos position, BlockState state, int flags) {
			writeCalls++;
			lastFlags = flags;
			if (getBlockState(position).equals(state)) return false;
			states.put(position.asLong(), state);
			return true;
		}
		void serverSet(BlockPos position, BlockState state) { states.put(position.asLong(), state); }
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void same(Object expected, Object actual, String message) {
		if (expected != actual) throw new AssertionError(message + ": expected the same object");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected " + expected + " but got " + actual);
		}
	}
}
