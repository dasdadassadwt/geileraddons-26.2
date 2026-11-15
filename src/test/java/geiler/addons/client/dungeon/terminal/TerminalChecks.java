package geiler.addons.client.dungeon.terminal;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the Terminal Solver rules (Odin port): detection, the six solvers, prediction and protection. */
public final class TerminalChecks {
	private TerminalChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkDetection();
		checkPanes();
		checkNumbers();
		checkRubix();
		checkStartsWithAndSelect();
		checkMelody();
		checkPredictionAndProtection();
		checkRegistryIds();
	}

	private static final class Cfg implements TerminalConfig {
		boolean prediction = true;
		boolean leftOnly = false;
		boolean replace = false;
		int ms = 0;
		boolean ticks = false;
		int tickCount = 8;
		public boolean getClickPrediction() { return prediction; }
		public boolean getRubixLeftClicksOnly() { return leftOnly; }
		public boolean getRubixReplaceClicks() { return replace; }
		public int getFirstClickProtectionMillis() { return ms; }
		public boolean getProtectWithServerTicks() { return ticks; }
		public int getProtectionTicks() { return tickCount; }
	}

	private static List<TermSlot> blank(int n) {
		List<TermSlot> slots = new ArrayList<>();
		for (int i = 0; i < n; i++) slots.add(new TermSlot("black_stained_glass_pane", 1, " ", false, false));
		return slots;
	}

	private static TermSlot pane(String color, int count) {
		return new TermSlot(color + "_stained_glass_pane", count, "", false, false);
	}

	private static TermSlot named(String item, String name, boolean glint, boolean override) {
		return new TermSlot(item, 1, name, glint, override);
	}

	private static TerminalHandler open(String title, Cfg cfg) {
		return TerminalHandler.open(title, cfg, () -> 1_000L);
	}

	private static void checkDetection() {
		Cfg cfg = new Cfg();
		require(open("Correct all the panes!", cfg).getType() == TerminalType.PANES, "panes title");
		require(open("Change all to same color!", cfg).getType() == TerminalType.RUBIX, "rubix title");
		require(open("Click in order!", cfg).getType() == TerminalType.NUMBERS, "numbers title");
		require(open("What starts with: 'B'?", cfg).getType() == TerminalType.STARTS_WITH, "starts with title");
		require(open("Select all the RED items!", cfg).getType() == TerminalType.SELECT, "select title");
		require(open("Select all the SILVER items!", cfg).getType() == TerminalType.SELECT, "silver maps to light gray");
		require(open("Select all the PLAID items!", cfg) == null, "unknown color gives no handler");
		require(open("Click the button on time!", cfg).getType() == TerminalType.MELODY, "melody title");
		require(open("Chest", cfg) == null, "other containers are not terminals");
		require(TerminalType.NUMBERS.getWindowSize() == 36 && TerminalType.SELECT.getWindowSize() == 54, "window sizes");
	}

	private static void checkPanes() {
		TerminalHandler h = open("Correct all the panes!", new Cfg());
		List<TermSlot> slots = blank(45);
		slots.set(11, pane("red", 1));
		slots.set(14, pane("lime", 1));
		slots.set(15, pane("red", 1));
		h.updateSlot(11, false, slots);
		require(h.getSolution().equals(List.of(11, 15)), "panes: red panes are the solution " + h.getSolution());
		TerminalClick click = h.click(11, 0, true);
		require(click != null && click.getSlot() == 11 && click.getButton() == 2, "panes click is a middle click");
		require(h.getSolution().equals(List.of(15)), "click prediction removes the slot");
		require(h.click(14, 0, true) == null, "a non solution slot cannot be clicked");
		require(h.visual(15).getColor() == ColorKey.PANES && h.visual(14) == null, "visuals only for solution slots");
	}

	private static void checkNumbers() {
		TerminalHandler h = open("Click in order!", new Cfg());
		List<TermSlot> slots = blank(36);
		slots.set(10, pane("red", 3));
		slots.set(11, pane("red", 1));
		slots.set(12, pane("red", 2));
		h.updateSlot(10, false, slots);
		require(h.getSolution().equals(List.of(11, 12, 10)), "numbers sorted by stack size " + h.getSolution());
		require(h.click(12, 0, true) == null, "only the next number can be clicked");
		require(h.click(11, 0, true) != null && h.getSolution().equals(List.of(12, 10)), "next number clicks and advances");
		require(h.visual(12).getText().equals("9") && h.visual(12).getColor() == ColorKey.NUMBERS_1,
			"label is 11 minus the remaining count: " + h.visual(12).getText());
	}

	private static void checkRubix() {
		Cfg cfg = new Cfg();
		TerminalHandler h = open("Change all to same color!", cfg);
		List<TermSlot> slots = blank(45);
		slots.set(12, pane("orange", 1));
		slots.set(13, pane("yellow", 1));
		slots.set(14, pane("green", 1));
		slots.set(15, pane("green", 1));
		slots.set(32, pane("green", 1));
		h.updateSlot(32, false, slots);
		// goal green costs 3 clicks (orange 2, yellow 1); yellow would cost 4
		require(h.getSolution().stream().filter(i -> i == 12).count() == 2 && h.getSolution().contains(13)
			&& !h.getSolution().contains(14), "rubix solves toward the cheapest color " + h.getSolution());
		require(h.canClick(12, 0) && !h.canClick(12, 1), "left clicks while forward is cheapest");

		TerminalHandler f = open("Change all to same color!", new Cfg());
		List<TermSlot> far = blank(45);
		far.set(12, pane("red", 1));
		far.set(13, pane("red", 1));
		far.set(14, pane("orange", 1));
		far.set(32, pane("red", 1));
		f.updateSlot(32, false, far);
		// goal red: the orange pane is four steps forward, one backward in fewest-clicks mode
		require(f.getSolution().equals(List.of(14)) && f.canClick(14, 1) && !f.canClick(14, 0)
			&& f.visual(14).getText().equals("-1"), "fewest clicks mixes in a right click: " + f.getSolution());
		Cfg left = new Cfg();
		left.leftOnly = true;
		TerminalHandler l = open("Change all to same color!", left);
		l.updateSlot(32, false, far);
		// left clicks only: moving the lone orange pane to red costs 4, moving three red panes to orange costs 3
		require(l.getSolution().equals(List.of(12, 13, 32)) && l.canClick(12, 0) && !l.canClick(12, 1)
			&& l.visual(12).getText().equals("1") && l.visual(12).getColor() == ColorKey.RUBIX_1,
			"left clicks only picks the cheaper forward goal: " + l.getSolution());

		Cfg replace = new Cfg();
		replace.replace = true;
		TerminalHandler r = open("Change all to same color!", replace);
		r.updateSlot(32, false, far);
		// replace clicks: a left click on the -1 slot goes out as the right click
		TerminalClick wrongButton = r.click(14, 0, false);
		require(wrongButton != null && wrongButton.getSlot() == 14 && wrongButton.getButton() == 1,
			"replace clicks turns a left click on a -1 slot into a right click");
		require(r.click(0, 0, false) == null, "replace clicks still rejects slots outside the solution");
		Cfg replaceLeft = new Cfg();
		replaceLeft.replace = true;
		replaceLeft.leftOnly = true;
		TerminalHandler rl = open("Change all to same color!", replaceLeft);
		rl.updateSlot(32, false, far);
		// a right click on a slot that needs a left click goes out as that slot's normal click
		TerminalClick rightOnLeft = rl.click(12, 1, false);
		require(rightOnLeft != null && rightOnLeft.getSlot() == 12 && rightOnLeft.getButton() == 2,
			"replace clicks turns a right click on a left slot into the left-slot click");
		require(l.click(12, 1, false) == null, "without replace clicks the wrong button is rejected");
	}

	private static void checkStartsWithAndSelect() {
		TerminalHandler h = open("What starts with: 'b'?", new Cfg());
		List<TermSlot> slots = blank(45);
		slots.set(10, named("bone", "Bone", false, false));
		slots.set(11, named("bread", "Bread", true, false));
		slots.set(12, named("book", "Book", true, true));
		slots.set(13, named("apple", "Apple", false, false));
		h.updateSlot(10, false, slots);
		require(h.getSolution().equals(List.of(10, 12)), "starts with skips glinting items unless they always glint " + h.getSolution());
		h.click(10, 0, true);
		slots.set(10, named("bone", "Bone", true, false));
		h.updateSlot(10, false, slots);
		require(h.getSolution().equals(List.of(12)), "a clicked slot stays out of the solution");

		TerminalHandler s = open("Select all the BLUE items!", new Cfg());
		List<TermSlot> items = blank(54);
		items.set(10, named("lapis_lazuli", "Lapis Lazuli", false, false));
		items.set(11, named("blue_wool", "Blue Wool", false, false));
		items.set(12, named("blue_dye", "Blue Dye", true, false));
		items.set(13, named("red_wool", "Red Wool", false, false));
		s.updateSlot(10, false, items);
		require(s.getSolution().equals(List.of(10, 11)), "select matches prefixes and ignores glint " + s.getSolution());
	}

	private static void checkMelody() {
		TerminalHandler h = open("Click the button on time!", new Cfg());
		List<TermSlot> slots = blank(54);
		slots.set(4, pane("magenta", 1));
		slots.set(21, pane("lime", 1));
		slots.set(25, new TermSlot("lime_terracotta", 1, "", false, false));
		h.updateSlot(4, false, slots);
		require(h.getSolution().equals(List.of(21, 4, 40)), "melody tracks pointer and column " + h.getSolution());
		require(h.canClick(16, 0) && h.canClick(25, 0) && h.canClick(34, 0) && !h.canClick(21, 0), "melody buttons are fixed");
		require(h.visual(4).getColor() == ColorKey.MELODY_COLUMN && h.visual(21).getColor() == ColorKey.MELODY_POINTER,
			"column color on the top row, pointer color elsewhere");
	}

	private static void checkPredictionAndProtection() {
		Cfg cfg = new Cfg();
		cfg.ms = 500;
		long[] now = {1_000L};
		TerminalHandler h = TerminalHandler.open("Correct all the panes!", cfg, () -> now[0]);
		List<TermSlot> slots = blank(45);
		slots.set(11, pane("red", 1));
		h.updateSlot(11, false, slots);
		require(h.click(11, 0, true) == null, "clicks are blocked inside the first click protection window");
		now[0] = 1_600L;
		require(h.click(11, 0, true) != null, "clicks pass once the window is over");
		require(h.getClickedSlots().size() == 1, "the click is remembered until the server confirms it");
		h.updateSlot(0, false, slots);
		require(h.getSolution().isEmpty(), "a reload re-applies the remembered click as prediction");
		slots.set(11, pane("lime", 1));
		h.updateSlot(11, false, slots);
		require(h.getClickedSlots().isEmpty(), "the server update of that slot retires the remembered click");

		Cfg lag = new Cfg();
		lag.ticks = true;
		TerminalHandler t = TerminalHandler.open("Correct all the panes!", lag, () -> 1_000L);
		t.updateSlot(11, false, new ArrayList<>(slots));
		require(t.shouldProtect(), "server lag protection holds until enough ticks have passed");
		t.setTicksOpened(8);
		require(!t.shouldProtect(), "and releases at the configured tick count");
		TerminalHandler ignored = TerminalHandler.open("Correct all the panes!", cfg, () -> 1_000L);
		slots.set(11, pane("red", 1));
		ignored.updateSlot(11, true, slots);
		require(ignored.getSolution().isEmpty(), "updates for black panes are ignored");
	}

	/** The solvers key on registry paths; confirm the 26.2 colored items still use the classic ids. */
	private static void checkRegistryIds() {
		net.minecraft.server.Bootstrap.bootStrap();
		for (String color : new String[] {"red", "black", "lime", "magenta", "orange", "yellow", "green", "blue"}) {
			net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.valueOf(color.toUpperCase());
			String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(net.minecraft.world.item.Items.STAINED_GLASS_PANE.pick(dye)).getPath();
			require(path.equals(color + "_stained_glass_pane"), "pane registry path for " + color + " is " + path);
		}
		String clay = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
			net.minecraft.world.item.Items.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.LIME)).getPath();
		require(clay.equals("lime_terracotta"), "terracotta registry path is " + clay);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
