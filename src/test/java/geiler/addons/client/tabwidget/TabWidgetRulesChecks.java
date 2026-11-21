package geiler.addons.client.tabwidget;

import geiler.addons.client.location.Island;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline checks for the Tab Widget rules: the widget table and splitting the tab list into widget blocks. */
public final class TabWidgetRulesChecks {
	private TabWidgetRulesChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkTable();
		checkParse();
		checkIslands();
	}

	private static void checkTable() {
		Set<String> ids = new HashSet<>();
		for (TabWidgetRules.Widget widget : TabWidgetRules.INSTANCE.getWIDGETS()) {
			require(ids.add(widget.getId()), "widget ids are unique: " + widget.getId());
			require(!widget.getSample().isEmpty(), widget.getId() + " has a sample for the HUD editor");
			require(TabWidgetRules.INSTANCE.headerOf(widget.getSample().get(0)) == widget,
				widget.getId() + " recognises the first line of its own sample: " + widget.getSample().get(0));
		}
		require(TabWidgetRules.INSTANCE.getWIDGETS().size() >= 30, "the table covers the common widgets");
	}

	private static void checkParse() {
		Map<String, List<String>> tab = TabWidgetRules.INSTANCE.parse(java.util.Arrays.asList(
			"Players (12)", "Alice", "Bob", "",
			"§6Commissions:", "Mithril Miner: 45.2%", "Goblin Slayer: DONE", " ",
			"Powders:", "Mithril: 1,000",
			"Pests:", "Plots: 3, 4", "Server: m12", "stray row",
			"Pests:", "Plots: 9"));
		require(tab.get("players").equals(List.of("Players (12)", "Alice", "Bob")), "a blank row ends a widget: " + tab.get("players"));
		require(tab.get("commissions").equals(List.of("Commissions:", "Mithril Miner: 45.2%", "Goblin Slayer: DONE")),
			"colour codes are stripped and the rows follow their header: " + tab.get("commissions"));
		require(tab.get("powders").equals(List.of("Powders:", "Mithril: 1,000")), "the next header ends a widget");
		require(tab.get("pests").equals(List.of("Pests:", "Plots: 3, 4")), "a known header that is not shown still ends the block, and the first block wins: " + tab.get("pests"));
		require(!tab.containsKey("bank") && tab.size() == 4, "widgets that are not in the tab list are absent: " + tab.keySet());

		require(TabWidgetRules.INSTANCE.parse(null).isEmpty() && TabWidgetRules.INSTANCE.parse(List.of()).isEmpty(), "no tab list, no widgets");
		require(TabWidgetRules.INSTANCE.parse(java.util.Arrays.asList("stray", null, "rows")).isEmpty(), "rows before any header are ignored");

		java.util.ArrayList<String> long_ = new java.util.ArrayList<>();
		long_.add("Collection:");
		for (int i = 0; i < 40; i++) long_.add("Row " + i);
		require(TabWidgetRules.INSTANCE.parse(long_).get("collection").size() == TabWidgetRules.MAX_LINES, "a widget is cut at the line limit");

		require(TabWidgetRules.INSTANCE.clean("§a Soulflow: 5 §r ").equals("Soulflow: 5"), "clean strips codes and no-break spaces");
		require(TabWidgetRules.INSTANCE.clean(null).isEmpty(), "clean accepts null");
	}

	private static void checkIslands() {
		TabWidgetRules.Widget commissions = widget("commissions");
		require(commissions.getIslands().contains(Island.DWARVEN_MINES) && !commissions.getIslands().contains(Island.GARDEN),
			"Commissions belongs to the mining islands only");
		require(widget("soulflow").getIslands().isEmpty(), "an empty island set means everywhere");
		require(widget("pests").getIslands().equals(Set.of(Island.GARDEN)), "Pests belongs to the Garden");
		require(widget("dungeonparty").getIslands().equals(Set.of(Island.CATACOMBS)), "the dungeon party belongs to the Catacombs");
	}

	private static TabWidgetRules.Widget widget(String id) {
		for (TabWidgetRules.Widget widget : TabWidgetRules.INSTANCE.getWIDGETS()) {
			if (widget.getId().equals(id)) return widget;
		}
		throw new IllegalStateException("no widget " + id);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
