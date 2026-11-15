package geiler.addons.client.waypoint;

import geiler.addons.client.config.ModConfigMigrations;
import geiler.addons.client.location.Island;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Offline checks for the Waypoints model, Skyblocker share strings, ordered routes and chat parsing. */
public final class WaypointChecks {
	private WaypointChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkTypes();
		checkGroupFiltering();
		checkShareRoundTrip();
		checkSkyblockerSample();
		checkShareRejects();
		checkStoreJson();
		checkOrderedRules();
		checkChatParser();
		checkChatBook();
		checkStoreV2();
		checkDisabledFiltering();
		checkLibraryHelpers();
		checkMigrations();
	}

	private static void checkTypes() {
		require(WaypointType.WAYPOINT.beam && WaypointType.WAYPOINT.fill && !WaypointType.WAYPOINT.outline,
			"WAYPOINT is a filled box with a beam");
		require(WaypointType.OUTLINED_WAYPOINT.beam && WaypointType.OUTLINED_WAYPOINT.outline,
			"OUTLINED_WAYPOINT adds an outline");
		require(!WaypointType.HIGHLIGHT.beam && WaypointType.HIGHLIGHT.fill, "HIGHLIGHT has no beam");
		require(WaypointType.OUTLINE.outline && !WaypointType.OUTLINE.fill, "OUTLINE draws only lines");
		require(WaypointType.fromId("outlined_highlight") == WaypointType.OUTLINED_HIGHLIGHT
			&& WaypointType.fromId("bogus") == WaypointType.WAYPOINT && WaypointType.fromId(null) == WaypointType.WAYPOINT,
			"type ids round-trip and fall back to WAYPOINT");
	}

	private static void checkGroupFiltering() {
		WaypointGroup everywhere = new WaypointGroup();
		WaypointGroup hub = new WaypointGroup();
		hub.setIsland("hub");
		require(everywhere.appliesTo(Island.HUB) && everywhere.appliesTo(Island.NONE),
			"a group without an island applies everywhere");
		require(hub.appliesTo(Island.HUB) && !hub.appliesTo(Island.DWARVEN_MINES) && !hub.appliesTo(Island.NONE),
			"an island group applies only to that island's mode");
	}

	private static List<WaypointGroup> sample() {
		WaypointGroup group = new WaypointGroup();
		group.setName("Route");
		group.setIsland("mining_3");
		group.setOrdered(true);
		group.setThroughWalls(false);
		group.setType("outlined_waypoint");
		group.getWaypoints().add(new WaypointEntry("A", 1, 2, 3, 0xFF8000, 0.25f, true));
		group.getWaypoints().add(new WaypointEntry("B", -10, 64, 500, 0x00FF00, 0.5f, false));
		List<WaypointGroup> list = new ArrayList<>();
		list.add(group);
		return list;
	}

	private static void checkShareRoundTrip() {
		String text = WaypointShare.encode(sample());
		require(text.startsWith(WaypointShare.PREFIX), "share strings carry the Skyblocker V1 prefix");
		WaypointShare.Result result = WaypointShare.decode(text);
		require(result.getError() == null && result.getGroups().size() == 1, "an encoded list decodes again");
		WaypointGroup group = result.getGroups().get(0);
		require("Route".equals(group.getName()) && "mining_3".equals(group.getIsland()) && group.getOrdered()
			&& !group.getThroughWalls() && "outlined_waypoint".equals(group.getType()),
			"group fields survive the share round trip");
		WaypointEntry b = group.getWaypoints().get(1);
		require(group.getWaypoints().size() == 2 && "B".equals(b.getName()) && b.getX() == -10 && b.getY() == 64
			&& b.getZ() == 500 && b.getRgb() == 0x00FF00 && !b.getEnabled() && Math.abs(b.getAlpha() - 0.5f) < 0.01f,
			"waypoint fields survive the share round trip");
	}

	private static String skyblockerString(String json) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
				gzip.write(json.getBytes(StandardCharsets.UTF_8));
			}
			return WaypointShare.PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		}
	}

	/** Shape taken from Skyblocker's WaypointGroup / NamedWaypoint codecs. */
	private static void checkSkyblockerSample() {
		String json = "[{\"name\":\"Mines\",\"island\":\"mining_3\",\"waypoints\":[{\"pos\":[5,6,7],\"name\":\"Gem\","
			+ "\"colorComponents\":[1.0,0.0,0.5],\"alpha\":0.5,\"shouldRender\":true}],\"ordered\":false,"
			+ "\"renderThroughWalls\":true,\"waypointType\":\"highlight\"}]";
		WaypointShare.Result result = WaypointShare.decode("  " + skyblockerString(json) + "\n");
		require(result.getError() == null && result.getGroups().size() == 1, "a Skyblocker string imports: " + result.getError());
		WaypointGroup group = result.getGroups().get(0);
		WaypointEntry gem = group.getWaypoints().get(0);
		require("highlight".equals(group.getType()) && "mining_3".equals(group.getIsland())
			&& gem.getX() == 5 && gem.getY() == 6 && gem.getZ() == 7 && "Gem".equals(gem.getName())
			&& gem.getRgb() == 0xFF0080 && gem.getEnabled(),
			"Skyblocker pos/colorComponents/name map onto the model");
		String unknownIsland = json.replace("mining_3", "unknown");
		WaypointShare.Result any = WaypointShare.decode(skyblockerString(unknownIsland));
		require(any.getError() == null && "".equals(any.getGroups().get(0).getIsland()),
			"the Skyblocker 'unknown' island imports as an everywhere group");
	}

	private static void checkShareRejects() {
		require(WaypointShare.decode(null).getError() != null && WaypointShare.decode("hello").getError() != null,
			"text without the prefix is rejected");
		require(WaypointShare.decode(WaypointShare.PREFIX + "!!!not base64!!!").getError() != null,
			"broken base64 is rejected");
		require(WaypointShare.decode(WaypointShare.PREFIX + Base64.getEncoder().encodeToString("plain".getBytes(StandardCharsets.UTF_8))).getError() != null,
			"data that is not gzip is rejected");
		require(WaypointShare.decode(skyblockerString("{\"not\":\"a list\"}")).getError() != null,
			"JSON that is not a group list is rejected");
		StringBuilder many = new StringBuilder("[{\"name\":\"X\",\"island\":\"hub\",\"waypoints\":[");
		for (int i = 0; i < WaypointShare.MAX_WAYPOINTS + 1; i++) {
			if (i > 0) many.append(',');
			many.append("{\"pos\":[0,0,0],\"name\":\"n\",\"colorComponents\":[0,0,0],\"alpha\":0.5,\"shouldRender\":true}");
		}
		many.append("]}]");
		require(WaypointShare.decode(skyblockerString(many.toString())).getError() != null,
			"more than MAX_WAYPOINTS waypoints are rejected");
	}

	private static void checkStoreJson() {
		String json = WaypointStore.toJson(sample());
		List<WaypointGroup> back = WaypointStore.fromJson(json);
		require(back.size() == 1 && back.get(0).getWaypoints().size() == 2
			&& "A".equals(back.get(0).getWaypoints().get(0).getName()), "the config file JSON round-trips");
		require(WaypointStore.fromJson("not json").isEmpty() && WaypointStore.fromJson(null).isEmpty()
			&& WaypointStore.fromJson("{}").isEmpty(), "broken or empty config JSON loads as no groups");
		WaypointGroup copy = sample().get(0).deepCopy();
		copy.getWaypoints().get(0).setName("changed");
		require("A".equals(sample().get(0).getWaypoints().get(0).getName()) && copy.getOrdered(),
			"deepCopy is independent of the original");
	}

	private static void checkOrderedRules() {
		int[][] route = {{0, 64, 0}, {10, 64, 0}, {20, 64, 0}, {30, 64, 0}};
		require(OrderedWaypointRules.advance(0, 10.5, 64, 0.5, route, 2.0, true, true) == 1,
			"standing on waypoint 1 makes it current");
		require(OrderedWaypointRules.advance(0, 100, 64, 100, route, 2.0, true, true) == 0,
			"standing far away keeps the index");
		require(OrderedWaypointRules.advance(0, 30.5, 64, 0.5, route, 2.0, true, true) == 3,
			"skipping on and going back on: waypoint 3 is reachable from 0 (previous of a 4-route)");
		require(OrderedWaypointRules.advance(0, 20.5, 64, 0.5, route, 2.0, true, false) == 0,
			"skipping off: waypoint 2 is neither next nor previous of 0");
		require(OrderedWaypointRules.advance(1, 0.5, 64, 0.5, route, 2.0, false, true) == 1,
			"going back off: the previous waypoint is not accepted");
		require(OrderedWaypointRules.advance(1, 20.5, 64, 0.5, route, 2.0, false, false) == 2,
			"the next waypoint is always accepted");
		require(OrderedWaypointRules.advance(3, 0.5, 64, 0.5, route, 2.0, false, false) == 0,
			"the route wraps from the last waypoint to the first");
		require(OrderedWaypointRules.relative(1, 2, 4) == OrderedWaypointRules.PREVIOUS
			&& OrderedWaypointRules.relative(2, 2, 4) == OrderedWaypointRules.CURRENT
			&& OrderedWaypointRules.relative(3, 2, 4) == OrderedWaypointRules.NEXT
			&& OrderedWaypointRules.relative(0, 2, 4) == OrderedWaypointRules.NONE,
			"relative role of each waypoint around the current index");
		require(OrderedWaypointRules.advance(0, 0, 64, 0, new int[0][], 2.0, true, true) == 0,
			"an empty route keeps index 0");
	}

	private static void checkChatParser() {
		ChatWaypointParser.Parsed party = ChatWaypointParser.parse("Party > [MVP+] Steve: x: 10, y: 70, z: -20");
		require(party != null && party.getChannel() == ChatWaypointParser.Channel.PARTY && "Steve".equals(party.getSender())
			&& party.getX() == 10 && party.getY() == 70 && party.getZ() == -20, "party coordinates parse");
		ChatWaypointParser.Parsed emblem = ChatWaypointParser.parse("Party > [VIP] Alex ⚒: x: 1 y: 2 z: 3 extra");
		require(emblem != null && "Alex".equals(emblem.getSender()) && emblem.getZ() == 3,
			"emblems, missing commas and trailing text are tolerated");
		ChatWaypointParser.Parsed all = ChatWaypointParser.parse("[MVP+] Bob: x: 5, y: 60, z: 5");
		require(all != null && all.getChannel() == ChatWaypointParser.Channel.ALL && "Bob".equals(all.getSender()),
			"all-chat coordinates parse");
		ChatWaypointParser.Parsed guild = ChatWaypointParser.parse("Guild > Cara: x: 5, y: 60, z: 5");
		require(guild != null && guild.getChannel() == ChatWaypointParser.Channel.GUILD, "guild coordinates parse");
		require(ChatWaypointParser.parse("Party > Steve: hello x: 1, y: 2, z: 3") == null,
			"coordinates must open the message");
		require(ChatWaypointParser.parse("Party > Steve: x: 1, y: 2000, z: 3") == null
			&& ChatWaypointParser.parse("Party > Steve: x: 1, y: -100, z: 3") == null,
			"an invalid Y is ignored");
		require(ChatWaypointParser.parse("Party > Steve: x: 9000, y: 70, z: 3") == null,
			"coordinates beyond the 5000 block bound are ignored");
		require(ChatWaypointParser.parse(null) == null && ChatWaypointParser.parse("") == null
			&& ChatWaypointParser.parse("Some system message x: 1, y: 2, z: 3") == null,
			"non-chat lines never match");
	}

	private static void checkChatBook() {
		ChatWaypointBook book = new ChatWaypointBook();
		book.put("Steve", 1, 70, 1, 1_000L, 60_000L);
		book.put("Steve", 2, 70, 2, 2_000L, 60_000L);
		require(book.size() == 1 && book.active(2_000L).get(0).getX() == 2,
			"a second waypoint from the same sender replaces the first");
		book.put("Alex", 3, 70, 3, 3_000L, 60_000L);
		require(book.size() == 2, "different senders keep separate waypoints");
		require(book.active(62_000L).size() == 1 && book.active(62_000L).get(0).getSender().equals("Alex"),
			"expired chat waypoints are dropped");
		book.clear();
		require(book.size() == 0, "clear empties the book");
		ChatWaypointBook bounded = new ChatWaypointBook();
		for (int i = 0; i < ChatWaypointBook.MAX_ENTRIES + 20; i++) bounded.put("p" + i, i, 70, i, 1L, 60_000L);
		require(bounded.size() == ChatWaypointBook.MAX_ENTRIES, "the book is bounded");
	}

	private static void checkStoreV2() {
		WaypointGroup group = sample().get(0);
		group.setEnabled(false);
		group.setShowText(false);
		String json = WaypointStore.toJson(List.of(group), List.of("hub", ""));
		WaypointStore.Data back = WaypointStore.parse(json);
		require(json.contains("\"version\"") && back.getGroups().size() == 1 && !back.getGroups().get(0).getEnabled()
			&& !back.getGroups().get(0).getShowText() && back.getDisabledIslands().containsAll(List.of("hub", "")),
			"the v2 file keeps group switches and disabled islands");
		String legacy = "[{\"name\":\"Old\",\"island\":\"hub\",\"waypoints\":[{\"name\":\"a\",\"x\":1,\"y\":2,\"z\":3}]}]";
		WaypointStore.Data old = WaypointStore.parse(legacy);
		require(old.getGroups().size() == 1 && old.getGroups().get(0).getEnabled() && old.getGroups().get(0).getShowText()
			&& old.getDisabledIslands().isEmpty() && old.getGroups().get(0).getWaypoints().get(0).getEnabled(),
			"a legacy bare-array file loads with every group on and text shown");
		require(WaypointStore.parse("{\"version\":2}").getGroups().isEmpty()
			&& WaypointStore.parse("[1,2]").getGroups().isEmpty(), "malformed v2 content loads empty");
		StringBuilder many = new StringBuilder("{\"version\":2,\"disabledIslands\":[");
		for (int i = 0; i < WaypointStore.MAX_DISABLED_ISLANDS + 50; i++) many.append(i == 0 ? "" : ",").append("\"i").append(i).append("\"");
		many.append("]}");
		require(WaypointStore.parse(many.toString()).getDisabledIslands().size() == WaypointStore.MAX_DISABLED_ISLANDS,
			"disabled island ids are bounded");
	}

	private static void checkDisabledFiltering() {
		WaypointGroup any = new WaypointGroup();
		WaypointGroup hub = new WaypointGroup();
		hub.setIsland("hub");
		WaypointGroup hubOff = new WaypointGroup();
		hubOff.setIsland("hub");
		hubOff.setEnabled(false);
		WaypointGroup mines = new WaypointGroup();
		mines.setIsland("mining_3");
		List<WaypointGroup> all = List.of(any, hub, hubOff, mines);
		List<WaypointGroup> inHub = WaypointStore.applicable(all, Set.of(), Island.HUB);
		require(inHub.size() == 2 && inHub.contains(any) && inHub.contains(hub), "a disabled group is skipped");
		require(WaypointStore.applicable(all, Set.of("hub"), Island.HUB).equals(List.of(any)),
			"a disabled island hides all of its groups");
		require(WaypointStore.applicable(all, Set.of(""), Island.HUB).equals(List.of(hub)),
			"disabling Any island hides only the island-less groups");
	}

	private static void checkLibraryHelpers() {
		require(java.util.Arrays.equals(WaypointLibrary.parsePosition("1, -2,3"), new int[] {1, -2, 3})
			&& java.util.Arrays.equals(WaypointLibrary.parsePosition(" 10 20 30 "), new int[] {10, 20, 30}),
			"positions parse from commas or spaces");
		require(WaypointLibrary.parsePosition("1,2") == null && WaypointLibrary.parsePosition("a,b,c") == null
			&& WaypointLibrary.parsePosition("1,2,99999999999") == null && WaypointLibrary.parsePosition("") == null,
			"bad positions are rejected");
		List<String> keys = WaypointLibrary.islandKeys(Island.DWARVEN_MINES);
		require(keys.get(0).isEmpty() && "mining_3".equals(keys.get(1)) && keys.size() == new java.util.HashSet<>(keys).size()
			&& keys.contains("hub") && !keys.contains(null), "Any island is first, then the current island, then the rest once");
		require(WaypointLibrary.islandKeys(Island.NONE).get(0).isEmpty() && WaypointLibrary.islandKeys(Island.NONE).size() > 20,
			"without a known island the list is Any island plus every island");
	}

	private static void checkMigrations() {
		Map<String, Boolean> toggles = new HashMap<>();
		Map<String, Boolean> enabled = new HashMap<>();
		Map<String, int[]> colors = new HashMap<>();
		Map<String, Float> numbers = new HashMap<>();
		enabled.put("Waypoints", true);
		toggles.put("Waypoints.From Guild Chat", true);
		toggles.put("Waypoints.From Party Chat", false);
		colors.put("Waypoints.Chat Waypoint Color", new int[] {1, 2, 3, 4});
		numbers.put("Waypoints.Duration", 90f);
		require(ModConfigMigrations.migrateChatWaypointSplit(enabled, toggles, colors, numbers)
			&& Boolean.TRUE.equals(enabled.get("Chat Waypoints"))
			&& Boolean.TRUE.equals(toggles.get("Chat Waypoints.From Guild Chat"))
			&& Boolean.FALSE.equals(toggles.get("Chat Waypoints.From Party Chat"))
			&& numbers.get("Chat Waypoints.Duration") == 90f && colors.get("Chat Waypoints.Chat Waypoint Color")[3] == 4,
			"chat waypoint settings move to the Chat Waypoints module");
		toggles.put("Chat Waypoints.From Guild Chat", false);
		require(!ModConfigMigrations.migrateChatWaypointSplit(enabled, toggles, colors, numbers)
			&& Boolean.FALSE.equals(toggles.get("Chat Waypoints.From Guild Chat")),
			"the split migration never overwrites newer values");
		Map<String, Boolean> off = new HashMap<>();
		off.put("Waypoints.Show Names", false);
		require(Boolean.FALSE.equals(ModConfigMigrations.takeLegacyWaypointShowNames(off)) && off.isEmpty()
			&& ModConfigMigrations.takeLegacyWaypointShowNames(off) == null,
			"the old global Show Names value is read once and removed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
