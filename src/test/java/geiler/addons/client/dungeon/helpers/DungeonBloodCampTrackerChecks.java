package geiler.addons.client.dungeon.helpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Registry-free checks for Odin-style Watcher and Blood Camp packet tracking. */
public final class DungeonBloodCampTrackerChecks {
	private DungeonBloodCampTrackerChecks() { }

	public static void run() {
		checkTrackerClassBoundary();
		checkJavaRecordBoundary();
		checkPositionOperations();
		checkRecordValueAndNullBehavior();

		DungeonBloodCampTracker tracker = new DungeonBloodCampTracker();
		long start = 1_000_000_000L;
		var watcher = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		var marker = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		tracker.observeWatcher(90, null);
		require(!tracker.hasWatcher() && tracker.watcherEntityId() == -1,
			"a null Watcher position remains a no-op");
		tracker.observeWatcher(91, watcher);

		require(!tracker.observeCandidateHead(7, false, true, true, true, marker).accepted(),
			"a recognized head on a non-ArmorStand is rejected");
		require(!tracker.observeCandidateHead(7, true, false, true, true, marker).accepted(),
			"a recognized head outside the ArmorStand HEAD slot is rejected");
		require(!tracker.observeCandidateHead(7, true, true, false, true, marker).accepted(),
			"a non-player head is rejected");
		require(!tracker.observeCandidateHead(7, true, true, true, false, marker).accepted(),
			"an unrecognized player-head texture is rejected");
		var distant = tracker.observeCandidateHead(8, true, true, true, true,
			new DungeonBloodCampTracker.Position(21.0, 200.0, 0.5));
		require(!distant.accepted() && distant.reason().equals("outside-watcher-range"),
			"recognized heads beyond 20 blocks are rejected");
		var missingPosition = tracker.observeCandidateHead(8, true, true, true, true, null);
		require(!missingPosition.accepted() && missingPosition.reason().equals("position-unavailable"),
			"a matching head without a decoded position is rejected cleanly");

		var accepted = tracker.observeCandidateHead(7, true, true, true, true, marker);
		require(accepted.accepted() && accepted.watcherDistance() == 0.0,
			"a recognized head within 20 blocks is accepted without a room or height gate");
		require(tracker.candidateSnapshot(start, 38).isEmpty(),
			"a matching head becomes a predicted candidate only after a movement packet");
		var missingMovementPosition = tracker.observeEntityMove(7, null, start, 38);
		require(missingMovementPosition.reading() == null
			&& missingMovementPosition.reason().equals("position-unavailable") && tracker.tracksCandidate(7),
			"an unavailable movement position leaves the eligible marker in place");

		var firstMove = tracker.observeEntityMove(7,
			new DungeonBloodCampTracker.Position(1.5, 200.0, 0.5), start + 50_000_000L, 38);
		require(firstMove.reading() != null && !firstMove.reading().hasDirection()
			&& firstMove.reading().firstWave() && firstMove.reading().maxTravelTicks() == 38
			&& firstMove.reading().timeLeftMillis() == 3_900L && firstMove.reading().remainingTicks() == 78,
			"the first movement packet starts an initial-wave prediction with the default 38-tick timer");
		var firstDirection = tracker.observeEntityMove(7,
			new DungeonBloodCampTracker.Position(2.5, 200.0, 0.5), start + 100_000_000L, 38);
		require(firstDirection.reading() != null && firstDirection.reading().hasDirection()
			&& near(firstDirection.reading().destination().x(), firstDirection.reading().spawn().x() + 16.1),
			"the initial wave projects 16.1 blocks along the observed direction");

		tracker.observeWatcherMoved();
		var laterMarker = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		require(tracker.observeCandidateHead(9, true, true, true, true, laterMarker).accepted(),
			"a later-wave mob head remains eligible after the Watcher move line");
		var laterMove = tracker.observeEntityMove(9,
			new DungeonBloodCampTracker.Position(0.5, 200.0, 1.5), start + 100_000_000L, 38);
		require(laterMove.reading() != null && !laterMove.reading().firstWave()
			&& !laterMove.reading().hasDirection() && laterMove.reading().timeLeftMillis() == 1_900L,
			"the later-wave candidate starts without a direction sample or initial-wave timer offset");
		var laterDirection = tracker.observeEntityMove(9,
			new DungeonBloodCampTracker.Position(0.5, 200.0, 2.5), start + 150_000_000L, 38);
		require(laterDirection.reading() != null && laterDirection.reading().hasDirection()
			&& near(laterDirection.reading().destination().z(), laterDirection.reading().spawn().z() + 11.9),
			"the move-line transition preserves later-wave 11.9-block prediction");
		require(tracker.candidateSnapshot(start + 12_000_000_000L, 38).size() == 2,
			"tracked candidates remain until entity removal rather than expiring by age");

		tracker.removeEntity(7);
		require(!tracker.tracksCandidate(7)
			&& tracker.candidateSnapshot(start + 100_000_000L, 38).size() == 1,
			"entity removal clears only that mob's candidate");
		tracker.removeEntity(91);
		require(!tracker.hasWatcher() && tracker.eligibleCount() == 0 && tracker.trackedCount() == 0,
			"Watcher removal clears Watcher and mob state");
		require(!tracker.firstWave(), "Watcher removal preserves the move-line wave transition");
		tracker.clearEntities();
		require(!tracker.firstWave(), "clearing entity observations preserves the move-line wave transition");
		tracker.reset();
		require(tracker.firstWave(), "a full world reset restores first-wave prediction");

		checkCandidateBoundsAndEviction();
		checkRollingDeltaWindow();
		checkTimerBounds();
	}

	private static void checkTrackerClassBoundary() {
		Class<DungeonBloodCampTracker> trackerType = DungeonBloodCampTracker.class;
		int modifiers = trackerType.getModifiers();
		require(trackerType.getName().equals("geiler.addons.client.dungeon.helpers.DungeonBloodCampTracker")
			&& Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers) && !trackerType.isRecord(),
			"DungeonBloodCampTracker keeps its public final class identity");
		try {
			Constructor<?> constructor = trackerType.getDeclaredConstructor();
			require(Modifier.isPublic(constructor.getModifiers()),
				"DungeonBloodCampTracker keeps its public no-argument constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("DungeonBloodCampTracker lost its public no-argument constructor", exception);
		}
	}

	private static void checkJavaRecordBoundary() {
		checkRecord(DungeonBloodCampTracker.Position.class,
			new String[] { "x", "y", "z" }, double.class, double.class, double.class);
		checkRecord(DungeonBloodCampTracker.CandidateCheck.class,
			new String[] { "accepted", "reason", "watcherDistance" },
			boolean.class, String.class, double.class);
		checkRecord(DungeonBloodCampTracker.MovementCheck.class,
			new String[] { "reading", "reason", "watcherDistance" },
			DungeonBloodCampTracker.CandidateReading.class, String.class, double.class);
		checkRecord(DungeonBloodCampTracker.Prediction.class,
			new String[] { "id", "current", "endpoint", "etaNanos", "hasDirection" },
			Object.class, DungeonBloodCampTracker.Position.class, DungeonBloodCampTracker.Position.class,
			long.class, boolean.class);
		checkRecord(DungeonBloodCampTracker.CandidateReading.class,
			new String[] { "entityId", "serial", "spawn", "current", "destination", "previousDestination",
				"destinationUpdatedNanos", "velocityPerMs", "elapsedTicks", "remainingTicks", "maxTravelTicks",
				"timerColorArgb", "hasDirection", "firstWave", "startedNanos", "timeLeftMillis", "watcherDistance" },
			int.class, long.class, DungeonBloodCampTracker.Position.class, DungeonBloodCampTracker.Position.class,
			DungeonBloodCampTracker.Position.class, DungeonBloodCampTracker.Position.class, long.class,
			DungeonBloodCampTracker.Position.class, int.class, int.class, int.class, int.class,
			boolean.class, boolean.class, long.class, long.class, double.class);
		checkPublicMethods();
	}

	private static void checkRecordValueAndNullBehavior() {
		var position = new DungeonBloodCampTracker.Position(1.0, -2.0, 3.5);
		var samePosition = new DungeonBloodCampTracker.Position(1.0, -2.0, 3.5);
		require(position.equals(samePosition) && position.hashCode() == samePosition.hashCode()
			&& position.toString().equals("Position[x=1.0, y=-2.0, z=3.5]"),
			"Position keeps record value equality, hashing, and string form");

		var candidateCheck = new DungeonBloodCampTracker.CandidateCheck(true, "accepted", 2.0);
		require(candidateCheck.toString().equals(
			"CandidateCheck[accepted=true, reason=accepted, watcherDistance=2.0]"),
			"CandidateCheck keeps Java record string form");
		var movementCheck = new DungeonBloodCampTracker.MovementCheck(null, null, Double.NaN);
		require(movementCheck.reading() == null && movementCheck.reason() == null
			&& movementCheck.toString().equals(
				"MovementCheck[reading=null, reason=null, watcherDistance=NaN]"),
			"MovementCheck keeps null component values and Java record string form");
		var prediction = new DungeonBloodCampTracker.Prediction(null, null, null, 7L, true);
		require(prediction.id() == null && prediction.current() == null && prediction.endpoint() == null
			&& prediction.toString().equals(
				"Prediction[id=null, current=null, endpoint=null, etaNanos=7, hasDirection=true]"),
			"Prediction keeps null component values and Java record string form");
		var reading = new DungeonBloodCampTracker.CandidateReading(
			1, 2L, null, null, null, null, 3L, null, 4, 5, 6, 7, true, false, 8L, 9L, 10.0);
		require(reading.spawn() == null && reading.current() == null && reading.destination() == null
			&& reading.previousDestination() == null && reading.velocityPerMs() == null
			&& reading.toString().equals(
				"CandidateReading[entityId=1, serial=2, spawn=null, current=null, destination=null, " +
					"previousDestination=null, destinationUpdatedNanos=3, velocityPerMs=null, elapsedTicks=4, " +
					"remainingTicks=5, maxTravelTicks=6, timerColorArgb=7, hasDirection=true, firstWave=false, " +
					"startedNanos=8, timeLeftMillis=9, watcherDistance=10.0]"),
			"CandidateReading keeps null component values and Java record string form");
		var nullableReason = new DungeonBloodCampTracker.CandidateCheck(true, null, 0.0);
		require(nullableReason.reason() == null, "CandidateCheck keeps nullable reference components");
	}

	private static void checkPositionOperations() {
		Class<DungeonBloodCampTracker.Position> positionType = DungeonBloodCampTracker.Position.class;
		try {
			Field zero = positionType.getField("ZERO");
			int modifiers = zero.getModifiers();
			require(zero.getType() == positionType && Modifier.isPublic(modifiers)
				&& Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
				&& zero.get(null) == DungeonBloodCampTracker.Position.ZERO,
				"Position.ZERO remains the public static final record constant");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Position.ZERO remains Java-visible", exception);
		}
		var zero = DungeonBloodCampTracker.Position.ZERO;
		requireExact(zero.x(), 0.0, "Position.ZERO keeps its x coordinate");
		requireExact(zero.y(), 0.0, "Position.ZERO keeps its y coordinate");
		requireExact(zero.z(), 0.0, "Position.ZERO keeps its z coordinate");
		checkPositionMethod("plus", positionType, positionType);
		checkPositionMethod("minus", positionType, positionType);
		checkPositionMethod("scale", positionType, double.class);
		checkPositionMethod("lengthSquared", double.class);
		checkPositionMethod("distanceSquared", double.class, positionType);

		var first = new DungeonBloodCampTracker.Position(0.1, -0.2, 0.3);
		var second = new DungeonBloodCampTracker.Position(0.2, 0.4, -0.1);
		var sum = first.plus(second);
		requireExact(sum.x(), first.x() + second.x(), "plus preserves x arithmetic precision");
		requireExact(sum.y(), first.y() + second.y(), "plus preserves y arithmetic precision");
		requireExact(sum.z(), first.z() + second.z(), "plus preserves z arithmetic precision");
		var difference = first.minus(second);
		requireExact(difference.x(), first.x() - second.x(), "minus preserves x arithmetic precision");
		requireExact(difference.y(), first.y() - second.y(), "minus preserves y arithmetic precision");
		requireExact(difference.z(), first.z() - second.z(), "minus preserves z arithmetic precision");
		double multiplier = 1.25;
		var scaled = first.scale(multiplier);
		requireExact(scaled.x(), first.x() * multiplier, "scale preserves x arithmetic precision");
		requireExact(scaled.y(), first.y() * multiplier, "scale preserves y arithmetic precision");
		requireExact(scaled.z(), first.z() * multiplier, "scale preserves z arithmetic precision");
		double expectedLengthSquared = first.x() * first.x() + first.y() * first.y() + first.z() * first.z();
		requireExact(first.lengthSquared(), expectedLengthSquared, "lengthSquared preserves operation order and precision");
		double expectedDistanceSquared = difference.x() * difference.x()
			+ difference.y() * difference.y() + difference.z() * difference.z();
		requireExact(first.distanceSquared(second), expectedDistanceSquared,
			"distanceSquared keeps the existing subtract-then-square calculation");
		require(Double.isInfinite(new DungeonBloodCampTracker.Position(Double.MAX_VALUE, 0.0, 0.0).lengthSquared()),
			"lengthSquared still returns infinity when the squared finite vector overflows");
		expectNullPosition(() -> first.plus(null), "plus preserves null rejection");
		expectNullPosition(() -> first.minus(null), "minus preserves null rejection");
		expectNullPosition(() -> first.distanceSquared(null), "distanceSquared preserves null rejection");

		for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
			expectInvalidPosition(() -> new DungeonBloodCampTracker.Position(invalid, 0.0, 0.0),
				"x rejects " + invalid);
			expectInvalidPosition(() -> new DungeonBloodCampTracker.Position(0.0, invalid, 0.0),
				"y rejects " + invalid);
			expectInvalidPosition(() -> new DungeonBloodCampTracker.Position(0.0, 0.0, invalid),
				"z rejects " + invalid);
		}
		expectInvalidPosition(() -> new DungeonBloodCampTracker.Position(Double.MAX_VALUE, 0.0, 0.0)
			.plus(new DungeonBloodCampTracker.Position(Double.MAX_VALUE, 0.0, 0.0)),
			"overflowing plus keeps constructor validation");
		expectInvalidPosition(() -> first.scale(Double.POSITIVE_INFINITY),
			"non-finite scale results keep constructor validation");
		expectInvalidPosition(() -> new DungeonBloodCampTracker.Position(Double.MAX_VALUE, 0.0, 0.0)
			.distanceSquared(new DungeonBloodCampTracker.Position(-Double.MAX_VALUE, 0.0, 0.0)),
			"overflowing subtraction inside distanceSquared keeps constructor validation");
	}

	private static void checkPositionMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = DungeonBloodCampTracker.Position.class.getMethod(name, parameterTypes);
			require(Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				"Position preserves public instance method " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("Position lost Java method " + name, exception);
		}
	}

	private static void expectInvalidPosition(Runnable operation, String message) {
		try {
			operation.run();
			throw new AssertionError(message + ": non-finite Position was accepted");
		} catch (IllegalArgumentException exception) {
			require("Blood Camp positions must be finite".equals(exception.getMessage()),
				message + ": constructor exception text remains unchanged");
		}
	}

	private static void expectNullPosition(Runnable operation, String message) {
		try {
			operation.run();
		} catch (NullPointerException exception) {
			return;
		}
		throw new AssertionError(message + ": null Position was accepted");
	}

	private static void requireExact(double actual, double expected, String message) {
		require(Double.doubleToLongBits(actual) == Double.doubleToLongBits(expected), message);
	}

	private static void checkRecord(Class<?> recordType, String[] expectedNames, Class<?>... expectedTypes) {
		require(recordType.isRecord(), recordType.getSimpleName() + " remains a Java record");
		require(recordType.getName().equals(DungeonBloodCampTracker.class.getName() + "$" + recordType.getSimpleName()),
			recordType.getSimpleName() + " keeps its nested Java binary name");
		var components = recordType.getRecordComponents();
		require(components.length == expectedNames.length && components.length == expectedTypes.length,
			recordType.getSimpleName() + " preserves its Java record component count");
		for (int i = 0; i < components.length; i++) {
			require(components[i].getName().equals(expectedNames[i]) && components[i].getType() == expectedTypes[i],
				recordType.getSimpleName() + " preserves record component " + i);
		}
		try {
			Constructor<?> constructor = recordType.getDeclaredConstructor(expectedTypes);
			require(Modifier.isPublic(constructor.getModifiers()),
				recordType.getSimpleName() + " preserves its public canonical constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(recordType.getSimpleName() + " lost its canonical constructor", exception);
		}
	}

	private static void checkPublicMethods() {
		checkPublicMethod("watcherEntityId", int.class);
		checkPublicMethod("watcherPosition", DungeonBloodCampTracker.Position.class);
		checkPublicMethod("hasWatcher", boolean.class);
		checkPublicMethod("firstWave", boolean.class);
		checkPublicMethod("eligibleCount", int.class);
		checkPublicMethod("trackedCount", int.class);
		checkPublicMethod("observeWatcher", void.class, int.class, DungeonBloodCampTracker.Position.class);
		checkPublicMethod("observeWatcherMoved", void.class);
		checkPublicMethod("observeCandidateHead", DungeonBloodCampTracker.CandidateCheck.class,
			int.class, boolean.class, boolean.class, boolean.class, boolean.class,
			DungeonBloodCampTracker.Position.class);
		checkPublicMethod("observeEntityMove", DungeonBloodCampTracker.MovementCheck.class,
			int.class, DungeonBloodCampTracker.Position.class, long.class, int.class);
		checkPublicMethod("candidateSnapshot", java.util.List.class, long.class, int.class);
		checkPublicMethod("tracksCandidate", boolean.class, int.class);
		checkPublicMethod("removeEntity", void.class, int.class);
		checkPublicMethod("removeCandidate", void.class, int.class);
		checkPublicMethod("clearEntities", void.class);
		checkPublicMethod("reset", void.class);
	}

	private static void checkPublicMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = DungeonBloodCampTracker.class.getDeclaredMethod(name, parameterTypes);
			require(Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				"DungeonBloodCampTracker preserves public instance method " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("DungeonBloodCampTracker lost Java method " + name, exception);
		}
	}

	private static void checkCandidateBoundsAndEviction() {
		var candidateTracker = new DungeonBloodCampTracker();
		var watcher = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		candidateTracker.observeWatcher(91, watcher);
		for (int entityId = 1; entityId <= 512; entityId++) {
			candidateTracker.observeCandidateHead(entityId, true, true, true, true, watcher);
		}
		require(candidateTracker.eligibleCount() == 512 && candidateTracker.trackedCount() == 0,
			"eligible candidate state stays within its 512-entry bound");
		var overflowCandidate = candidateTracker.observeCandidateHead(513, true, true, true, true, watcher);
		require(overflowCandidate.accepted() && candidateTracker.eligibleCount() == 512
			&& candidateTracker.tracksCandidate(513), "a new candidate replaces an old entry at capacity");

		var trackTracker = new DungeonBloodCampTracker();
		trackTracker.observeWatcher(91, watcher);
		long start = 2_000_000_000L;
		var moved = new DungeonBloodCampTracker.Position(1.5, 200.0, 0.5);
		for (int entityId = 1; entityId <= 511; entityId++) {
			trackTracker.observeCandidateHead(entityId, true, true, true, true, watcher);
			trackTracker.observeEntityMove(entityId, moved, start + entityId * 50_000_000L, 38);
		}
		trackTracker.observeCandidateHead(512, true, true, true, true, watcher);
		var newestTrack = trackTracker.observeEntityMove(512, moved, start + 512 * 50_000_000L, 38);
		require(newestTrack.reading() != null && !trackTracker.tracksCandidate(1)
			&& trackTracker.tracksCandidate(512) && trackTracker.eligibleCount() == 511
			&& trackTracker.trackedCount() == 511,
			"capacity eviction removes the oldest started track and its eligibility entry");
	}

	private static void checkRollingDeltaWindow() {
		var tracker = new DungeonBloodCampTracker();
		var watcher = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		tracker.observeWatcher(91, watcher);
		tracker.observeCandidateHead(7, true, true, true, true, watcher);
		long start = 3_000_000_000L;
		tracker.observeEntityMove(7, new DungeonBloodCampTracker.Position(1.5, 200.0, 0.5), start, 38);
		tracker.observeEntityMove(7, new DungeonBloodCampTracker.Position(2.5, 200.0, 0.5), start + 50_000_000L, 38);
		for (int step = 1; step <= 16; step++) {
			tracker.observeEntityMove(7, new DungeonBloodCampTracker.Position(2.5, 200.0, 0.5 + step),
				start + (step + 1) * 50_000_000L, 38);
		}
		var reading = tracker.candidateSnapshot(start + 17 * 50_000_000L, 38).getFirst();
		require(near(reading.destination().x(), reading.spawn().x())
			&& near(reading.destination().z(), reading.spawn().z() + 16.1),
			"direction prediction uses only the latest 16 movement deltas");
	}

	private static void checkTimerBounds() {
		var tracker = new DungeonBloodCampTracker();
		var watcher = new DungeonBloodCampTracker.Position(0.5, 200.0, 0.5);
		tracker.observeWatcher(91, watcher);
		tracker.observeCandidateHead(7, true, true, true, true, watcher);
		long start = 4_000_000_000L;
		var reading = tracker.observeEntityMove(7,
			new DungeonBloodCampTracker.Position(1.5, 200.0, 0.5), start, 0).reading();
		require(reading != null && reading.maxTravelTicks() == 35 && reading.timeLeftMillis() == 3_750L
			&& reading.remainingTicks() == 75 && reading.timerColorArgb() == 0xFF55FF55,
			"timer input is clamped to the minimum spawn tick count");
		var expired = tracker.candidateSnapshot(start + 4_000_000_000L, 0).getFirst();
		require(expired.timeLeftMillis() < 0L && expired.remainingTicks() == 0
			&& expired.timerColorArgb() == 0xFF55FFFF,
			"elapsed spawn timers stop at zero remaining ticks and switch to the expired color");
	}

	private static boolean near(double actual, double expected) {
		return Math.abs(actual - expected) < 1.0e-6;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
