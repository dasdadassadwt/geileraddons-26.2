package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.module.impl.DungeonRunSplitsServerTickChecks;
import geiler.addons.client.dungeon.DungeonFloor;

import java.util.List;

/** Isolated offline entry point for the server-tick split checks. */
public final class DungeonServerTickChecks {
	private DungeonServerTickChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkGameTimeAnchoring();
		checkPacketIdsAndSplitBoundaries();
		DungeonRunSplitsServerTickChecks.run();
	}

	/** Ping IDs overcount (up to ~23 per second in live logs); server world time is the authoritative tick clock. */
	private static void checkGameTimeAnchoring() {
		DungeonServerTickCounter counter = new DungeonServerTickCounter();
		for (int i = 1; i <= 50; i++) counter.observePingId(-i);
		counter.observeGameTime(1_000L);
		require(counter.count() == 50L, "anchoring on the first world-time packet keeps the count continuous");
		for (int i = 0; i < 40; i++) counter.onClientTick();
		require(counter.count() == 70L, "client ticks extrapolate at most one packet interval (20 ticks)");
		counter.observeGameTime(1_020L);
		require(counter.count() == 70L, "an on-time packet matches the extrapolation exactly");
		for (int i = 0; i < 5; i++) counter.onClientTick();
		counter.observeGameTime(1_040L);
		require(counter.count() == 90L, "elapsed server ticks follow the world-time delta, not ping IDs");
		for (int i = 1; i <= 500; i++) counter.observePingId(-i);
		require(counter.count() == 90L, "extra ping packets no longer inflate the count once anchored");
		counter.observeGameTime(900L);
		require(counter.count() >= 90L, "a backwards world-time packet never lowers the count");
		long beforeLeap = counter.count();
		counter.observeGameTime(2_000_000_000L);
		require(counter.count() - beforeLeap < 100L, "a world swap into a clock billions of ticks ahead does not add them to the count");
		counter.reset();
		require(counter.count() == 0L, "reset returns to the unanchored ping fallback");
	}

	private static void checkPacketIdsAndSplitBoundaries() {
		DungeonServerTickCounter counter = new DungeonServerTickCounter();
		require(!counter.observePingId(0L) && !counter.observePingId(1L)
			&& counter.count() == 0L && counter.observePingId(-1L)
			&& counter.observePingId(-42L) && counter.count() == 2L,
			"only negative ping IDs increment the server-tick counter");
		require(DungeonServerTickCounter.isServerTickPacket(Long.MIN_VALUE)
			&& !DungeonServerTickCounter.isServerTickPacket(Long.MAX_VALUE),
			"packet classification preserves the signed long boundaries");
		counter.reset();
		require(counter.count() == 0L, "reset clears the run-local server-tick count");

		DungeonSplitTracker tracker = new DungeonSplitTracker();
		require(tracker.observe(DungeonFloor.F5, "ENTRY", 1_000_000_000L, 1L, 40L, 80L)
			&& tracker.observe(DungeonFloor.F5, "BLOOD_OPEN", 2_200_000_000L, 2L, 44L, 100L),
			"server-tick baselines advance at client-observed split boundaries");
		List<DungeonSplitTracker.Split> closed = tracker.drainNewlyFinished();
		require(closed.size() == 2 && closed.getFirst().id().equals("ENTRY")
			&& closed.getFirst().elapsedServerTicks() == 20L
			&& closed.getFirst().elapsedTicks() == 1L && closed.getFirst().elapsedPingPackets() == 4L
			&& closed.getLast().id().equals("BLOOD") && closed.getLast().elapsedServerTicks() == 20L,
			"split server ticks stay independent from client ticks and legacy ping-event counts");
		List<DungeonSplitTracker.Split> live = tracker.snapshot(2_250_000_000L, 10_000L, 45L, 101L);
		require(live.stream().filter(row -> row.id().equals("BLOOD_OPEN")).findFirst().orElseThrow()
			.elapsedServerTicks() == 1L
			&& live.stream().filter(row -> row.id().equals("CAMP")).findFirst().orElseThrow()
				.elapsedServerTicks() == 1L
			&& live.stream().filter(row -> row.id().equals("RUN")).findFirst().orElseThrow()
				.elapsedServerTicks() == 21L,
			"active phase, Camp, and Run rows use separate server-tick baselines");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
