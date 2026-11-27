package geiler.addons.client.hunting;

import java.util.List;
import java.util.Map;

/** Offline checks for Hunting Methods: ranking, lost profit, fortune, missing data and the price history. */
public final class HuntingMethodsChecks {
	private HuntingMethodsChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkRanking();
		checkDefaults();
		checkView();
		checkHistory();
		checkGroups();
	}

	private static void checkGroups() {
		List<HuntingMethods.Method> methods = List.of(
			new HuntingMethods.Method("CHEEKY", "Cheeky Shard", "Cheeky Tiki", "Isle", null, 1.0, "tiki"),
			new HuntingMethods.Method("SHRIEKY", "Shrieky Shard", "Shrieky Tiki", "Isle", 60.0, 1.0, "tiki"),
			new HuntingMethods.Method("SNEAKY", "Sneaky Shard", "Sneaky Tiki", "Isle", null, 1.0, "tiki"),
			new HuntingMethods.Method("LONE", "Lone Shard", "Lone", "Field", 1.0, 1.0));
		List<HuntingMethods.Group> groups = List.of(new HuntingMethods.Group("tiki", "Tikis", 180.0));
		Map<String, ShardPrice> prices = Map.of(
			"CHEEKY", new ShardPrice(10.0, 10.0), "SHRIEKY", new ShardPrice(20.0, 20.0),
			"SNEAKY", new ShardPrice(30.0, 30.0), "LONE", new ShardPrice(1000.0, 1000.0));
		List<HuntingMethods.Method> resolved = HuntingMethods.resolve(methods, groups);
		require(near(resolved.get(0).getKillsPerHour(), 60.0) && near(resolved.get(2).getKillsPerHour(), 60.0),
			"unset members split what the group total leaves after the set members");
		require(near(resolved.get(1).getKillsPerHour(), 60.0), "a member's own rate wins");
		List<HuntingMethods.Row> rows = HuntingMethods.table(methods, prices, 0.0, true, (id, days) -> Double.NaN, groups);
		HuntingMethods.Row header = rows.stream().filter(r -> r.getGroup() != null).findFirst().orElseThrow();
		double taxed = 1.0 - ShardValue.TAX;
		require(near(header.getPerHour(), 60 * (10 + 20 + 30) * taxed), "a group earns the sum of its members");
		require(near(header.getLostPerHour(), 0.0), "the best unit loses nothing");
		HuntingMethods.Row lone = rows.stream().filter(r -> r.getMethod().getShardId().equals("LONE")).findFirst().orElseThrow();
		require(near(lone.getLostPerHour(), (3600 - 1000) * taxed), "lost profit compares groups and single shards");
		List<HuntingMethods.Row> view = HuntingMethods.view(rows, "", HuntingMethods.Column.PER_HOUR, true);
		require(view.get(0).getGroup() != null && view.get(1).getInGroup().equals("tiki") && view.get(4).getMethod().getShardId().equals("LONE"),
			"members follow their group header");
		require(HuntingMethods.view(rows, "sneaky", HuntingMethods.Column.SHARD, false).size() == 4,
			"a search hit in a member shows the whole group");
		List<HuntingMethods.Row> noTotal = HuntingMethods.table(methods, prices, 0.0, true, (id, days) -> Double.NaN,
			List.of(new HuntingMethods.Group("tiki", "Tikis", null)));
		require(noTotal.stream().filter(r -> "CHEEKY".equals(r.getMethod().getShardId())).noneMatch(HuntingMethods.Row::getRanked),
			"without a group total, unset members stay unranked");
	}

	private static HuntingMethods.Method method(String id, Double killsPerHour, double shardsPerKill) {
		return new HuntingMethods.Method(id, id, "mob", "place", killsPerHour, shardsPerKill);
	}

	private static void checkRanking() {
		List<HuntingMethods.Method> methods = List.of(
			method("SHARD_A", 100.0, 1.0),
			method("SHARD_B", 50.0, 1.0),
			method("SHARD_C", null, 1.0),
			method("SHARD_D", 80.0, 1.0));
		Map<String, ShardPrice> prices = Map.of(
			"SHARD_A", new ShardPrice(90.0, 100.0),
			"SHARD_B", new ShardPrice(380.0, 400.0),
			"SHARD_C", new ShardPrice(1.0, 1.0));
		List<HuntingMethods.Ranked> ranked = HuntingMethods.rank(methods, prices, 0.0, true);
		require(ranked.size() == 2, "only shards with a rate and a price are ranked");
		require(ranked.get(0).getMethod().getShardId().equals("SHARD_B"), "the best profit per hour is first");
		double taxed = 1.0 - ShardValue.TAX;
		require(near(ranked.get(0).getPerHour(), 50 * 400 * taxed) && near(ranked.get(0).getLostPerHour(), 0),
			"profit per hour is rate times price after tax; the best loses nothing");
		require(near(ranked.get(1).getLostPerHour(), (50 * 400 - 100 * 100) * taxed), "lost profit is the gap to the best");
		List<HuntingMethods.Ranked> fortune = HuntingMethods.rank(methods, prices, 100.0, true);
		require(near(fortune.get(0).getPerHour(), 2 * 50 * 400 * taxed), "100 Hunting Fortune doubles the shards");
		require(HuntingMethods.unranked(methods, prices).equals(List.of("SHARD_C: no kills/hour yet", "SHARD_D: no price")),
			"unranked shards say what is missing");
	}

	private static void checkDefaults() {
		require(HuntingMethods.defaults().isEmpty(), "the table starts empty; the player adds the shards they hunt");
		List<HuntingMethods.Method> one = HuntingMethods.add(List.of(), "SHARD_A", "Alpha Shard");
		require(one.size() == 1 && one.get(0).getKillsPerHour() == null && one.get(0).getShardsPerKill() == 1.0,
			"an added shard starts without a rate");
		require(HuntingMethods.add(one, "SHARD_A", "Alpha Shard").size() == 1, "a shard is listed once");
		require(HuntingMethods.remove(one, "SHARD_A").isEmpty(), "removing drops the shard");
	}

	private static void checkView() {
		List<HuntingMethods.Method> methods = List.of(
			new HuntingMethods.Method("SHARD_A", "Alpha Shard", "Alpha", "Forest", 100.0, 1.0),
			new HuntingMethods.Method("SHARD_B", "Beta Shard", "Beta", "Cavern", 50.0, 1.0),
			new HuntingMethods.Method("SHARD_C", "Gamma Shard", "Gamma", "Cavern", null, 1.0));
		Map<String, ShardPrice> prices = Map.of("SHARD_A", new ShardPrice(90.0, 100.0), "SHARD_B", new ShardPrice(380.0, 400.0));
		List<HuntingMethods.Row> rows = HuntingMethods.table(methods, prices, 0.0, true, (id, days) -> Double.NaN);
		require(rows.size() == 3 && !rows.get(2).getRanked(), "the table keeps unranked shards");
		List<HuntingMethods.Row> best = HuntingMethods.view(rows, "", HuntingMethods.Column.PER_HOUR, true);
		require(best.get(0).getMethod().getShardId().equals("SHARD_B") && best.get(2).getMethod().getShardId().equals("SHARD_C"),
			"sorting by coins per hour puts the best first and unknown values last");
		require(HuntingMethods.view(rows, "", HuntingMethods.Column.PER_HOUR, false).get(2).getMethod().getShardId().equals("SHARD_C"),
			"unknown values stay last in either direction");
		require(HuntingMethods.view(rows, "cavern", HuntingMethods.Column.SHARD, false).size() == 2, "search matches the place");
		require(HuntingMethods.view(rows, "", HuntingMethods.Column.SHARD, false).get(0).getMethod().getName().equals("Alpha Shard"),
			"the shard column sorts by name");
	}

	private static void checkHistory() {
		ShardPriceHistory history = new ShardPriceHistory();
		long day = 86_400_000L;
		history.record(Map.of("SHARD_A", new ShardPrice(10.0, 20.0)), 10 * day);
		history.record(Map.of("SHARD_A", new ShardPrice(30.0, 40.0)), 10 * day + 1000);
		history.record(Map.of("SHARD_A", new ShardPrice(100.0, 200.0)), 4 * day);
		require(near(history.average("SHARD_A", 3, 10 * day + 5000, false), 20.0), "a 3-day average uses only recent days");
		require(near(history.average("SHARD_A", 7, 10 * day + 5000, true), 115.0), "a 7-day average weights each day equally");
		require(Double.isNaN(history.average("SHARD_B", 7, 10 * day, true)), "no samples give no average");
		for (int i = 0; i < 40; i++) history.record(Map.of("SHARD_A", new ShardPrice(1.0, 1.0)), (20 + i) * day);
		require(history.days("SHARD_A") <= ShardPriceHistory.MAX_DAYS, "history keeps a bounded number of days");
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1e-6;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
