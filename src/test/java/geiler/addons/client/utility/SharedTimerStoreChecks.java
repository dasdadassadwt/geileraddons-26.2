package geiler.addons.client.utility;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline checks for the shared timer: snapshot round trip, revisions, the file, and a timer that ran out while closed. */
public final class SharedTimerStoreChecks {
	private SharedTimerStoreChecks() {
	}

	public static void main(String[] arguments) throws Exception {
		run();
	}

	public static void run() throws Exception {
		checkSnapshotRoundTrip();
		checkRevisions();
		checkFile();
		System.out.println("Shared timer checks passed");
	}

	private static final long SEC = 1_000_000_000L;

	private static void checkSnapshotRoundTrip() {
		CountdownTimerState a = new CountdownTimerState();
		check(a.snapshot(0L, 0L) == null, "an unset timer has no snapshot");
		a.setSeconds(120, 0L);
		CountdownTimerState.Snapshot running = a.snapshot(30 * SEC, 1_000_000L);
		check(running.getRunning() && running.getInitialMs() == 120_000L && running.getRemainingMs() == 90_000L
			&& running.getEndEpochMs() == 1_090_000L, "a running snapshot carries the wall-clock end");

		// Another instance, 10 s of wall time later, with an unrelated nanoTime origin.
		CountdownTimerState b = new CountdownTimerState();
		b.restore(running, 5_000 * SEC, 1_010_000L);
		check(b.isRunning() && b.remainingSeconds(5_000 * SEC) == 80L, "restore keeps counting down from the wall clock");
		check(b.initialDurationSeconds() == 120L, "restore keeps the original duration");

		a.pause(40 * SEC);
		CountdownTimerState.Snapshot paused = a.snapshot(99 * SEC, 9_999_999L);
		CountdownTimerState c = new CountdownTimerState();
		c.restore(paused, 0L, 99_999_999L);
		check(!c.isRunning() && c.remainingSeconds(0L) == 80L, "a paused timer stays paused however much time passed");

		// Ended 20 s ago while this instance was closed: still alerts once.
		CountdownTimerState recent = new CountdownTimerState();
		recent.restore(running, 0L, 1_110_000L);
		check(recent.tick(0L) && recent.isComplete(), "a timer that ended moments ago alerts once on load");
		check(!recent.tick(SEC), "and only once");

		// Ended long ago: complete, no alert.
		CountdownTimerState stale = new CountdownTimerState();
		stale.restore(running, 0L, 1_090_000L + CountdownTimerState.STALE_COMPLETION_MS + 1);
		check(stale.isComplete() && !stale.tick(0L), "a long-finished timer shows complete without alerting");
	}

	private static void checkRevisions() {
		check(SharedTimerStore.nextRevision(0L, 500L) == 500L, "revision follows wall time");
		check(SharedTimerStore.nextRevision(900L, 500L) == 901L, "revision never goes back when clocks disagree");
		CountdownTimerState.Snapshot s = new CountdownTimerState.Snapshot(1, 1, false, 0);
		check(SharedTimerStore.shouldApply(new SharedTimerStore.Record(5, "other", s), 4, "me"), "a newer record from another instance applies");
		check(!SharedTimerStore.shouldApply(new SharedTimerStore.Record(5, "other", s), 5, "me"), "an already-seen revision does not apply");
		check(!SharedTimerStore.shouldApply(new SharedTimerStore.Record(9, "me", s), 4, "me"), "an instance ignores its own writes");
		check(!SharedTimerStore.shouldApply(null, 0, "me"), "a missing record does not apply");
	}

	private static void checkFile() throws Exception {
		Path dir = Files.createTempDirectory("ga-shared-timer");
		Path file = dir.resolve("sub").resolve("shared-timer.json");
		check(SharedTimerStore.read(file) == null && SharedTimerStore.modifiedMs(file) < 0L, "a missing file reads as nothing");
		SharedTimerStore.Record record = new SharedTimerStore.Record(42L, "w",
			new CountdownTimerState.Snapshot(60_000L, 30_000L, true, 123_456L));
		SharedTimerStore.write(file, record);
		check(record.equals(SharedTimerStore.read(file)), "a record survives the file round trip");
		check(!Files.exists(file.resolveSibling("shared-timer.json.tmp")), "the temp file is moved away");
		Files.writeString(file, "{\"revision\":1,");
		check(SharedTimerStore.read(file) == null, "a truncated file reads as nothing");
		Files.writeString(file, "{\"revision\":1,\"writer\":\"w\"}");
		check(SharedTimerStore.decode(Files.readString(file)) == null, "a record with missing fields reads as nothing");
		check(SharedTimerStore.defaultPath().toString().contains(".geileraddons"), "the file lives in the shared home folder");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
