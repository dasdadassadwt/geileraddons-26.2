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
		require(GlaciteGraph.targetColor("Peridot Gemstone") == 0x55FF55, "peridot is green");
		require(GlaciteGraph.targetColor("Citrine Gemstone") == 0x8B5A2B, "citrine is brown");
		require(GlaciteGraph.targetColor("Onyx Gemstone") == 0x111111, "onyx is black");
		require(GlaciteGraph.targetColor("Aquamarine Gemstone") == 0x3355FF, "aquamarine is blue");
		require(GlaciteGraph.targetColor("Tungsten") == 0x9A9A9A, "tungsten is gray");
		require(GlaciteGraph.targetColor("Umber") == 0xFF8800, "umber is orange");
		require(GlaciteGraph.targetColor("Campfire") == null, "a target without a colour keeps the chosen path colour");
		require(GlaciteGraph.targetColor(null) == null, "no target keeps the chosen path colour");
		List<double[]> bent = List.of(new double[] {0, 0, 0}, new double[] {10, 0, 0}, new double[] {10, 0, 10});
		List<double[]> smooth = PathSmoothing.smooth(bent);
		require(smooth.size() == 2 * PathSmoothing.STEPS + 1, "smoothing adds points between the nodes");
		require(smooth.get(0)[0] == 0 && smooth.get(smooth.size() - 1)[2] == 10, "smoothing keeps the end points");
		require(smooth.get(PathSmoothing.STEPS)[0] == 10 && smooth.get(PathSmoothing.STEPS)[2] == 0, "smoothing passes through each node");
		require(smooth.get(PathSmoothing.STEPS - 1)[2] != 0 && smooth.get(PathSmoothing.STEPS - 1)[0] < 10, "the corner is rounded, not cut sharply");
		require(PathSmoothing.smooth(bent.subList(0, 2)).size() == 2, "two points stay a straight line");
		System.out.println("Glacite guide checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
