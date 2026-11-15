package geiler.addons.client.macros.pathing;

/** The search tiers: cheapest first, and more only for a search that failed or ran out of patience. */
public final class SearchTierChecks {
	private SearchTierChecks() { }

	public static void run() {
		checkOrder();
		checkWeights();
		checkRadius();
	}

	private static void checkOrder() {
		check(SearchTier.next(SearchTier.GREEDY, false) == SearchTier.STANDARD, "a greedy search that found nothing hands on");
		check(SearchTier.next(SearchTier.GREEDY, true) == SearchTier.STANDARD, "also when it ran into its limit");
		check(SearchTier.next(SearchTier.STANDARD, false) == null, "a standard search that saw everything there is has nothing more to try");
		check(SearchTier.next(SearchTier.STANDARD, true) == SearchTier.THOROUGH, "one that ran out of patience gets the thorough tier");
		check(SearchTier.next(SearchTier.THOROUGH, true) == null, "and that is the last");
		check(SearchTier.GREEDY.getMaxExpansions() < SearchTier.STANDARD.getMaxExpansions()
			&& SearchTier.STANDARD.getMaxExpansions() < SearchTier.THOROUGH.getMaxExpansions(), "each tier may look at more stands than the one before");
	}

	private static void checkWeights() {
		check(SearchTier.GREEDY.weight(1.1) > SearchTier.STANDARD.weight(1.1), "the greedy estimate counts for more");
		check(SearchTier.STANDARD.weight(1.4) == 1.4, "the standard weight is the plain one");
		check(SearchTier.THOROUGH.weight(1.7) == 1.0, "the thorough tier is exact");
		check(SearchTier.GREEDY.weight(0.2) >= 1.0, "no weight is ever under one");
	}

	private static void checkRadius() {
		double greedy = SearchTier.GREEDY.radius(10.0, 96.0);
		check(greedy < 96.0 && greedy >= 10.0 * 1.5, "a greedy search keeps to a corridor round a near goal (" + greedy + ")");
		check(SearchTier.GREEDY.radius(200.0, 96.0) == 96.0, "and never past the search radius");
		check(SearchTier.STANDARD.radius(10.0, 96.0) == 96.0, "a standard search has the whole radius");
		check(SearchTier.THOROUGH.radius(10.0, 96.0) == 120.0, "a thorough one more");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
