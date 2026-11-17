package geiler.addons.client.fishing;

/** Offline checks for the Geyser particle rules, the Hotspot Radar filter and its particle-path fitter. */
public final class FishingParticleChecks {
	private static final long SECOND = 1_000_000_000L;

	private FishingParticleChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkGeyserRules();
		checkHotspotFilter();
		checkFitterAcceptance();
		checkFitterSolve();
		System.out.println("Fishing particle checks passed");
	}

	private static void checkGeyserRules() {
		require(GeyserRules.matches(15, 0.05f, 0.1f, 0.6f, 0.1f), "the geyser cloud signature matches");
		require(!GeyserRules.matches(14, 0.05f, 0.1f, 0.6f, 0.1f), "a different count is not a geyser");
		require(!GeyserRules.matches(15, 0.06f, 0.1f, 0.6f, 0.1f), "a different speed is not a geyser");
		require(!GeyserRules.matches(15, 0.05f, 0.2f, 0.6f, 0.1f), "a different offset is not a geyser");
		double[] box = GeyserRules.box(100.5, -20.25);
		require(box[0] == 98.5 && box[2] == -22.25 && box[3] == 102.5 && box[5] == -18.25,
			"the box is four blocks wide around the particle");
		require(box[1] == 117.9 && box[4] == 117.91, "the box is a thin slab at y 117.9");
		require(GeyserRules.inDrawRange(96.0) && !GeyserRules.inDrawRange(96.1), "the box draws within 96 blocks");
		require(GeyserRules.hides(2.9) && !GeyserRules.hides(3.0), "particles hide only within 3 blocks of the bobber");
	}

	private static void checkHotspotFilter() {
		require(HotspotRules.matches(true, 1, 0f), "a single still flame particle is a radar particle");
		require(!HotspotRules.matches(false, 1, 0f), "another particle type is not");
		require(!HotspotRules.matches(true, 2, 0f), "a burst is not");
		require(!HotspotRules.matches(true, 1, 0.1f), "a moving flame is not");
		require("§e1,234m".equals(HotspotRules.distanceText(1234.7)), "distance reads in whole metres with separators");
	}

	private static void checkFitterAcceptance() {
		ParticlePathFitter fitter = new ParticlePathFitter();
		long now = 10 * SECOND;
		require(!fitter.tryAdd(0, 64, 0, 3.0, now, now), "the first point is stored but not reported as added");
		require(fitter.count() == 1, "the first point is kept");
		require(fitter.tryAdd(0.5, 64, 0, 3.0, now, now), "a near point is added");
		require(!fitter.tryAdd(0.5, 64, 0, 3.0, now, now), "an exact repeat is rejected");
		require(!fitter.tryAdd(0.505, 64, 0, 3.0, now, now), "a point within 0.01 of a used one is rejected");
		require(!fitter.tryAdd(40, 64, 0, 3.0, now, now), "a point too far from the last is rejected");
		require(!fitter.tryAdd(1.0, 64, 0, 3.0, now - 2 * SECOND, now), "points more than a second after the radar use are ignored");
		fitter.reset();
		require(fitter.count() == 0, "reset empties the fitter");
	}

	private static void checkFitterSolve() {
		ParticlePathFitter fitter = new ParticlePathFitter();
		long now = 5 * SECOND;
		for (int i = 0; i < 3; i++) fitter.tryAdd(10 + i * 0.5, 64, 20, 3.0, now, now);
		require(fitter.solve() == null, "fewer than four points cannot be solved");
		fitter.tryAdd(11.5, 64, 20, 3.0, now, now);
		double[] guess = fitter.solve();
		require(guess != null, "four points can be solved");
		double expected = 10 + 3 * Math.sqrt(7.0);
		require(Math.abs(guess[0] - expected) < 1e-6 && Math.abs(guess[1] - 64) < 1e-6 && Math.abs(guess[2] - 20) < 1e-6,
			"a level straight path extrapolates 3*sqrt(7) blocks past its first point");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
