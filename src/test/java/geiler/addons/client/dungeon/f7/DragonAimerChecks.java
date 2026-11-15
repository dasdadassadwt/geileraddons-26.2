package geiler.addons.client.dungeon.f7;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the dragon stack aimer: Lambert W, the arrow solver, the spawn queue, ping median and HUD text. */
public final class DragonAimerChecks {
	private DragonAimerChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkLambert();
		checkSolver();
		checkQueue();
		checkPingAndText();
		checkPathData();
	}

	private static void checkLambert() {
		for (double x : new double[] {-0.36, -0.3, -0.1, -0.001, 0.001, 0.5, 1.0, 2.718281828, 10.0, 1000.0}) {
			double w = ProjectileSolver.lambertW0(x);
			require(Math.abs(w * Math.exp(w) - x) < 1e-9 * Math.max(1, Math.abs(x)), "W0 identity at " + x + " got " + w);
		}
		for (double x : new double[] {-0.36, -0.3, -0.2, -0.1, -0.01, -0.0001}) {
			double w = ProjectileSolver.lambertW1(x);
			require(w <= -1.0 && Math.abs(w * Math.exp(w) - x) < 1e-9, "W-1 identity at " + x + " got " + w);
		}
		require(Double.isNaN(ProjectileSolver.lambertW0(-0.5)) && Double.isNaN(ProjectileSolver.lambertW1(0.1)),
			"out of domain is NaN");
	}

	private static double horizontal(ProjectileSolver.Data d, double v, double drag) {
		return v * Math.sin(d.getPhi()) * (Math.pow(drag, d.getTicks()) - 1.0) / (drag - 1.0);
	}

	private static void checkSolver() {
		ProjectileSolver.Data low = ProjectileSolver.solve(30, 10, 0, 0.001, -0.05, 3.0, 0.99, false);
		require(low.getValid() && Math.abs(horizontal(low, 3.0, 0.99) - 30) < 0.5,
			"low arc covers the horizontal range, ticks=" + low.getTicks());
		require(Math.abs(low.getTheta()) < 1e-9, "yaw angle for +x is zero");
		ProjectileSolver.Data high = ProjectileSolver.solve(30, 10, 0, 0.001, -0.05, 3.0, 0.99, true);
		require(high.getValid() && Math.abs(horizontal(high, 3.0, 0.99) - 30) < 0.5, "high arc covers the range too");
		require(high.getPhi() < low.getPhi() && high.getTicks() > low.getTicks(), "the lobbed arc is steeper and slower");
		ProjectileSolver.Data far = ProjectileSolver.solve(5000, 0, 0, 0.001, -0.05, 3.0, 0.99, false);
		require(!far.getValid(), "an unreachable target is NaN");
		double[][] path = AimDragon.POWER.getPath();
		ProjectileSolver.Aim aim = ProjectileSolver.aim(60, path, 0, 0.001, -0.05, 3.0, 0.99, false, 60, 20, 60);
		require(aim.getIndex() >= 0 && aim.getIndex() < path.length, "aim picks a valid path index");
	}

	private static final class Log implements DragonSpawnQueue.Listener {
		final List<String> events = new ArrayList<>();
		public void activeChanged(DragonSpawnQueue.Spawn s) { events.add("active:" + s.getType().getLabel() + ":" + s.getHigh()); }
		public void dead(AimDragon d) { events.add("dead:" + d.getLabel()); }
	}

	private static void checkQueue() {
		Log log = new Log();
		DragonSpawnQueue q = new DragonSpawnQueue(log);
		require(q.onParticle(27, 19, 59, 20, 2f, 3f, 2f, 0f, true, 100) != null
			&& log.events.equals(List.of("active:Power:false")), "first burst becomes active: " + log.events);
		require(q.onParticle(27, 19, 59, 20, 2f, 3f, 2f, 0f, true, 150) == null, "same dragon inside its cooldown ignored");
		require(q.onParticle(27, 27, 94, 20, 2f, 3f, 2f, 0f, true, 120) != null && q.getQueued().size() == 2
			&& log.events.size() == 1, "second dragon waits behind the first, high flag from y 27");
		require(q.onParticle(84, 19, 94, 20, 2f, 3f, 2f, 0f, true, 250) != null
			&& log.events.contains("dead:Power") && log.events.contains("dead:Apex"),
			"a burst after the heads' spawn ticks retires them: " + log.events);
		require(q.onParticle(27, 18, 59, 20, 2f, 3f, 2f, 0f, true, 400) == null, "y must be 19 or 27");
		require(q.onParticle(27, 19, 59, 20, 2f, 3f, 2f, 0f, false, 400) == null, "non flame ignored");
		q.onDragonGone(AimDragon.ICE);
		require(log.events.contains("dead:Ice") && q.getQueued().isEmpty(), "gone dragon leaves the queue");
		require(q.nearestType(27.5, 14.2, 58.9) == AimDragon.POWER && q.nearestType(84.2, 14, 94.4) == AimDragon.ICE,
			"entity type is the dragon with the nearest flight start");
	}

	private static void checkPingAndText() {
		PingTracker ping = new PingTracker();
		ping.add(40, 1000);
		ping.add(100, 2000);
		ping.add(60, 3000);
		require(ping.median(3000) == 60.0, "median of three");
		ping.add(80, 4000);
		require(ping.median(4000) == 70.0, "median of four averages the middle pair");
		require(ping.median(40_000) == 0.0, "old samples expire");
		require(DragonAimerLogic.hudText(0, 20, 0, 0) == null, "no text once spawned");
		require("§bNOW".equals(DragonAimerLogic.hudText(30, 29.5, 100, 0)), "negative window shows NOW");
		require(DragonAimerLogic.hudText(30, 0, 0, -5000) == null, "far past the window hides");
		require("§61500".equals(DragonAimerLogic.hudText(40, 10, 0, 0)), "1500 ms is gold of 5000: "
			+ DragonAimerLogic.hudText(40, 10, 0, 0));
	}

	private static void checkPathData() {
		for (AimDragon d : AimDragon.values()) {
			require(d.getPath().length == 30, d.name() + " path has 30 points");
			require(Math.abs(d.getPath()[0][0] - d.getParticleX()) < 1e-9 && Math.abs(d.getPath()[0][2] - d.getParticleZ()) < 1e-9,
				d.name() + " path starts at its particle column");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
