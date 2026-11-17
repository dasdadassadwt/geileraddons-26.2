package geiler.addons.client.mining;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Offline checks for the Glacite Tunnels graph and route rules, with the shipped graph. */
public final class GlaciteGuideChecks {
	private static final long S = 1_000_000_000L;

	private GlaciteGuideChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() throws RuntimeException {
		String json;
		try (var in = GlaciteGuideChecks.class.getResourceAsStream("/geileraddons/mining/glacite_tunnels_graph.json")) {
			require(in != null, "the graph ships as a resource");
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException failure) {
			throw new RuntimeException(failure);
		}
		GlaciteGraph graph = GlaciteGraph.parse(json);
		require(graph.size() == 825, "all 825 nodes load, got " + graph.size());
		List<String> names = graph.targetNames();
		require(names.contains("Campfire") && names.contains("Ruby Gemstone") && names.contains("Tungsten"), "campfire, gemstones and ores are targets");
		require(names.stream().anyMatch(n -> n.startsWith("Fairy Soul")), "fairy souls are targets");
		require(!names.contains("Dulin"), "NPCs are not route targets");
		require(graph.spots("Ruby Gemstone").size() == 2, "Ruby has two spots");

		GlaciteGraph.Node campfire = graph.spots("Campfire").get(0);
		GlaciteGraph.Node ruby = graph.spots("Ruby Gemstone").get(0);
		GlaciteGraph.Path path = graph.shortest(campfire, ruby);
		require(path != null && path.getNodes().size() >= 2 && path.getLength() > 0, "a path from the campfire to a ruby spot exists");
		require(path.getNodes().get(0).getId() == campfire.getId() && path.getNodes().get(path.getNodes().size() - 1).getId() == ruby.getId(), "the path runs from start to goal");
		require(graph.shortest(campfire, campfire).getLength() == 0.0, "a node is zero blocks from itself");
		require(graph.nearest(ruby.getX(), ruby.getY(), ruby.getZ()).getId() == ruby.getId(), "the nearest node to a node is itself");

		GlaciteRoute route = new GlaciteRoute(graph);
		route.setTarget("Ruby Gemstone", campfire, 0);
		require(route.getGoal() != null && "Ruby Gemstone".equals(route.getGoal().getName()), "a target picks a goal spot");
		GlaciteGraph.Node first = route.getGoal();
		require(!route.update(campfire.getX(), campfire.getY(), campfire.getZ(), S), "walking from the campfire is not arriving");
		require(route.getPath() != null && route.getPath().getLength() > 0, "a path is computed");
		require(route.update(first.getX(), first.getY(), first.getZ(), 2 * S), "standing on the goal reaches it");
		require(route.getGoal() == null && route.getPath() == null, "reaching clears the goal");
		require(route.next(campfire, 3 * S) && route.getGoal() != null && route.getGoal().getId() != first.getId(), "the next spot skips the one that is resting");
		route.clear();
		require(route.getTarget() == null && route.getGoal() == null, "clear drops the target");

		List<String> targets = graph.targetNames();
		require("Ruby Gemstone".equals(GlaciteGraph.targetForCommission("Ruby Gemstone Collector", targets)), "a gemstone collector maps to its spots");
		require("Tungsten".equals(GlaciteGraph.targetForCommission("Tungsten Collector", targets)), "an ore collector maps to the ore");
		require(GlaciteGraph.targetForCommission("Glacite Collector", targets) == null, "glacite has no spot");
		require(GlaciteGraph.targetForCommission("Goblin Raid Slayer", targets) == null, "a slayer commission is not a collector");
		System.out.println("Glacite guide checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
