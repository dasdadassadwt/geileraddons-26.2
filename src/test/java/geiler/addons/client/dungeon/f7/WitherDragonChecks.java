package geiler.addons.client.dungeon.f7;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Offline checks for the Wither Dragons tracker, F7 phase thresholds and the personal best rules. */
public final class WitherDragonChecks {
	private WitherDragonChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkSpawnFlow();
		checkParticleFilter();
		checkDeathPaths();
		checkPhases();
		checkPersonalBest();
		checkStatues();
		checkRunningTimerKept();
		checkCountLineOrder();
	}

	/** Wither King count lines arrive ~3.5 s after each death; they must never kill a dragon that is still spawning. */
	private static void checkCountLineOrder() {
		Log log = new Log();
		WitherDragonTracker t = new WitherDragonTracker(log);
		burst(t, 27, 59);
		burst(t, 85, 94);
		for (int i = 0; i < 101; i++) t.onServerTick();
		UUID red = UUID.randomUUID();
		UUID blue = UUID.randomUUID();
		t.onEntityAdded(red, 30.0, 20.0, 60.0);
		t.onEntityAdded(blue, 85.0, 20.0, 95.0);
		t.onHealth(red, 0f);
		t.onHealth(blue, 0f);
		require(burst(t, 85, 56), "orange burst accepted");
		t.onWitherKingLine();
		require(log.events.contains("counts:Red") && !log.events.contains("counts:Blue"),
			"first count line belongs to the first death: " + log.events);
		t.onWitherKingLine();
		require(log.events.contains("counts:Blue"), "second count line belongs to the second death: " + log.events);
		require(t.entry(WitherDragon.Orange).getState() == WitherDragonState.SPAWNING
			&& t.entry(WitherDragon.Orange).getTimeToSpawn() > 0, "orange keeps spawning");
		int before = log.events.size();
		t.onWitherKingLine();
		require(log.events.size() == before && t.entry(WitherDragon.Orange).getState() == WitherDragonState.SPAWNING,
			"a count line with nothing queued never touches a spawning dragon: " + log.events);

		Log aliveLog = new Log();
		WitherDragonTracker u = new WitherDragonTracker(aliveLog);
		burst(u, 56, 125);
		for (int i = 0; i < 101; i++) u.onServerTick();
		require(u.entry(WitherDragon.Purple).getState() == WitherDragonState.ALIVE, "purple alive");
		u.onWitherKingLine();
		require(aliveLog.events.contains("counts:Purple") && u.entry(WitherDragon.Purple).getState() == WitherDragonState.DEAD,
			"an alive dragon with a missed death is counted and marked dead: " + aliveLog.events);
		u.onWitherKingLine();
		require(aliveLog.events.stream().filter(e -> e.equals("counts:Purple")).count() == 1, "the fallback is not queued again");
	}

	/** A burst for a dragon whose countdown still runs must not restart it, even after its statue broke. */
	private static void checkRunningTimerKept() {
		WitherDragonTracker t = new WitherDragonTracker(new Log());
		require(burst(t, 27, 59), "red burst accepted");
		for (int i = 0; i < 10; i++) t.onServerTick();
		t.onBlockAir(32, 22, 59);
		require(t.entry(WitherDragon.Red).getState() == WitherDragonState.DEAD, "statue air marks red dead while counting");
		require(!burst(t, 27, 59), "a burst while red still counts down is ignored");
		require(t.entry(WitherDragon.Red).getTimeToSpawn() == 90, "the running countdown is not restarted");
		require(t.getSpawnOrder().size() <= 1, "no duplicate spawn order entry");
		for (int i = 0; i < 90; i++) t.onServerTick();
		require(burst(t, 27, 59) && t.entry(WitherDragon.Red).getTimeToSpawn() == 100,
			"a burst after the countdown ended starts a new one");

		WitherDragonTracker u = new WitherDragonTracker(new Log());
		burst(u, 27, 59);
		for (int i = 0; i < 90; i++) u.onServerTick();
		burst(u, 56, 125);
		require(u.nextToSpawn() == WitherDragon.Red, "the lowest remaining time is shown first");
		for (int i = 0; i < 10; i++) u.onServerTick();
		require(u.entry(WitherDragon.Red).getTimeToSpawn() == 0 && u.nextToSpawn() == WitherDragon.Purple,
			"when the first reaches zero the next running countdown is shown");
		u.reset();
		require(u.nextToSpawn() == null, "no countdown after a reset");
	}

	private static void checkStatues() {
		WitherDragonTracker t = new WitherDragonTracker(new Log());
		for (WitherDragon d : WitherDragon.values()) require(t.statueIntact(d), "statue starts intact: " + d);
		t.onBlockAir(1, 2, 3);
		require(t.statueIntact(WitherDragon.Red), "an unrelated air block changes nothing");
		t.onBlockAir(80, 23, 56);
		require(!t.statueIntact(WitherDragon.Orange) && t.statueIntact(WitherDragon.Red), "only the orange statue breaks");
		t.reset();
		require(t.statueIntact(WitherDragon.Orange), "reset restores the statues");
	}

	private static final class Log implements WitherDragonTracker.Listener {
		final List<String> events = new ArrayList<>();
		public void spawning(WitherDragon d) { events.add("spawning:" + d.name()); }
		public void spawned(WitherDragon d, int times) { events.add("spawned:" + d.name() + ":" + times); }
		public void died(WitherDragon d, long ticks, boolean real) { events.add("died:" + d.name() + ":" + ticks + ":" + real); }
		public void counts(WitherDragon d) { events.add("counts:" + d.name()); }
		public void sprayed(WitherDragon d, long ticks) { events.add("sprayed:" + d.name() + ":" + ticks); }
	}

	private static boolean burst(WitherDragonTracker t, double x, double z) {
		return t.onSpawnParticle(x, 19.0, z, 20, 2f, 3f, 2f, 0f, true);
	}

	private static void checkSpawnFlow() {
		Log log = new Log();
		WitherDragonTracker t = new WitherDragonTracker(log);
		require(burst(t, 27, 59), "red spawn burst is accepted");
		require(t.getPriority() == WitherDragon.Red, "red is the priority dragon");
		require(!burst(t, 27, 59), "the same dragon cannot spawn twice while not dead");
		require(burst(t, 56, 125), "purple burst is accepted");
		require(t.getSpawnOrder().equals(List.of(WitherDragon.Red, WitherDragon.Purple)), "spawn order keeps arrival order");
		for (int i = 0; i < 99; i++) t.onServerTick();
		require(t.entry(WitherDragon.Red).getState() == WitherDragonState.SPAWNING, "still spawning after 99 ticks");
		t.onServerTick();
		require(t.entry(WitherDragon.Red).getTimeToSpawn() == 0, "counter reaches zero after 100 ticks");
		t.onServerTick();
		require(t.entry(WitherDragon.Red).getState() == WitherDragonState.ALIVE
			&& log.events.contains("spawned:Red:1"), "the tick after zero marks the dragon alive: " + log.events);
		UUID id = UUID.randomUUID();
		t.onEntityAdded(id, 30.0, 20.0, 60.0);
		require(id.equals(t.entry(WitherDragon.Red).getEntityUuid()), "entity inside the red box binds its uuid");
		t.onHealth(id, 100f);
		require(t.entry(WitherDragon.Red).getState() == WitherDragonState.ALIVE, "positive health keeps it alive");
		t.onHealth(id, 0f);
		require(t.entry(WitherDragon.Red).getState() == WitherDragonState.DEAD
			&& log.events.stream().anyMatch(e -> e.startsWith("died:Red:") && e.endsWith(":true")),
			"zero health kills it in real time: " + log.events);
		require(t.getPriority() == WitherDragon.Purple, "purple becomes the priority dragon");
	}

	private static void checkParticleFilter() {
		WitherDragonTracker t = new WitherDragonTracker(new Log());
		require(!t.onSpawnParticle(27, 19.0, 59, 19, 2f, 3f, 2f, 0f, true), "wrong count rejected");
		require(!t.onSpawnParticle(27, 20.0, 59, 20, 2f, 3f, 2f, 0f, true), "wrong y rejected");
		require(!t.onSpawnParticle(27, 19.0, 59, 20, 2f, 3f, 2f, 0f, false), "non flame rejected");
		require(!t.onSpawnParticle(27, 19.0, 59, 20, 1f, 3f, 2f, 0f, true), "wrong spread rejected");
		require(!t.onSpawnParticle(27.5, 19.0, 59, 20, 2f, 3f, 2f, 0f, true), "fractional x rejected");
		require(!t.onSpawnParticle(40, 19.0, 59, 20, 2f, 3f, 2f, 0f, true), "outside every dragon range rejected");
		require(t.onSpawnParticle(85, 19.0, 56, 20, 2f, 3f, 2f, 0f, true) && t.getPriority() == WitherDragon.Orange,
			"orange range accepted");
	}

	private static void checkDeathPaths() {
		Log log = new Log();
		WitherDragonTracker t = new WitherDragonTracker(log);
		burst(t, 27, 94);
		for (int i = 0; i < 101; i++) t.onServerTick();
		UUID id = UUID.randomUUID();
		t.onEntityAdded(id, 20.0, 15.0, 95.0);
		t.onIceSpray(u -> 3.0);
		t.onIceSpray(u -> 3.0);
		require(log.events.stream().filter(e -> e.startsWith("sprayed:Green")).count() == 1, "spray is reported once");
		t.onBlockAir(32, 23, 94);
		require(t.entry(WitherDragon.Green).getState() == WitherDragonState.DEAD
			&& log.events.stream().anyMatch(e -> e.startsWith("died:Green:") && e.endsWith(":false")),
			"statue turning to air kills it without a real time");
		t.onWitherKingLine();
		require(log.events.contains("counts:Green"), "Wither King line confirms the last dead dragon");
		t.reset();
		require(t.getPriority() == null && t.entry(WitherDragon.Green).getTimesSpawned() == 0, "reset clears dragons");
	}

	private static void checkPhases() {
		require(F7Support.phaseForY(211) == F7Support.Phase.P1 && F7Support.phaseForY(210) == F7Support.Phase.P2
			&& F7Support.phaseForY(155.5) == F7Support.Phase.P2 && F7Support.phaseForY(155) == F7Support.Phase.P3
			&& F7Support.phaseForY(100.1) == F7Support.Phase.P3 && F7Support.phaseForY(100) == F7Support.Phase.P4
			&& F7Support.phaseForY(45.1) == F7Support.Phase.P4 && F7Support.phaseForY(45) == F7Support.Phase.P5,
			"phase thresholds follow Odin's y cut-offs");
	}

	private static void checkPersonalBest() {
		PersonalBests pb = new PersonalBests(java.util.Map.of("Red", 1.0f));
		PersonalBests.Outcome slower = pb.record("Red", 1.5f);
		require(!slower.getNewBest() && slower.getOld() == 1.0f && pb.get("Red") == 1.0f, "slower keeps the best");
		PersonalBests.Outcome faster = pb.record("Red", 0.9f);
		require(faster.getNewBest() && faster.getOld() == 1.0f && pb.get("Red") == 0.9f, "faster replaces it");
		PersonalBests.Outcome first = pb.record("Blue", 2.0f);
		require(first.getNewBest() && first.getOld() == 9999f && pb.get("Blue") == 2.0f, "first time is a best over 9999");
		require(!pb.record("Blue", 2.0f).getNewBest(), "an equal time is not a new best");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
