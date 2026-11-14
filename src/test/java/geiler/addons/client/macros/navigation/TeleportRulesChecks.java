package geiler.addons.client.macros.navigation;

/** What decides a warp is clicked: the green block, the landing it was planned for, nothing shut on the way, and the use of it. */
public final class TeleportRulesChecks {
	private TeleportRulesChecks() { }

	private static final double NONE = Double.NaN;

	public static void run() {
		checkVerdict();
		checkWarpRule();
		checkEscape();
	}

	private static void checkVerdict() {
		check(WarpVerdict.decide(true, "ok", 10.5, 64.05, 3.5, 10.5, 64.1, 3.5, false).equals("ok"), "a green block that is the planned landing and clear is ok");
		check(WarpVerdict.decide(false, "no room above the block", 0, 0, 0, 10.5, 64.1, 3.5, false).startsWith("sim: "), "a block the Etherwarp Sim does not paint green is refused with its reason");
		check(WarpVerdict.decide(false, "nothing in range", 0, 0, 0, NONE, NONE, NONE, false).contains("nothing in range"), "the reason is passed on");
		check(WarpVerdict.decide(true, "ok", 11.5, 64.05, 3.5, 10.5, 64.1, 3.5, false).equals("lands elsewhere"), "a green block a block from the plan is not the plan");
		check(WarpVerdict.decide(true, "ok", 10.5, 64.05, 3.5, 10.5, 64.1, 3.5, true).equals("crosses shut zone"), "a way through a wall is refused even when green");
		check(WarpVerdict.decide(true, "ok", 99, 64, 99, NONE, NONE, NONE, false).equals("ok"), "with no plan to match, a green clear block is ok");
		check(WarpVerdict.decide(true, "ok", 10.5, 64.05, 3.5, 10.5, 64.1, 3.5, true).equals("crosses shut zone"), "shut is checked after the landing matches");
		check(WarpVerdict.decide(true, "ok", 10.5, 70.0, 3.5, 10.5, 64.1, 3.5, false).equals("lands elsewhere"), "height counts in where it lands");
	}

	private static void checkWarpRule() {
		// remaining route, flight, route gain of the landing, mobs within reach of the walk.
		check(WarpRule.allow(60, 30, 28, false), "a long clear route and a landing that cuts the walk is allowed");
		check(!WarpRule.allow(30, 20, 19, false), "less than 35 blocks left: walk");
		check(!WarpRule.allow(60, 30, 28, true), "mobs close by: walk, they are the work");
		check(!WarpRule.allow(60, 30, 20, false), "a landing that gains less than 80 percent of the flight is a detour");
		check(WarpRule.allow(35, 30, 24, false), "exactly the minimum and exactly 80 percent are allowed");
		check(!WarpRule.allow(60, 0, 0, false), "no flight is no warp");
		check(WarpRule.allow(60, 30, 28, false, 20), "a lower minimum can be set");
		check(!WarpRule.allow(10, 30, 28, false, 20), "and is still a minimum");
	}

	private static void checkEscape() {
		// OFF_PATH is not a stall: replan first; a second one within the window, or a real STUCK, may escape.
		check(!StuckEscape.warpAllowed("OFF_PATH", 0, 0), "off the path once: replan, no warp");
		check(StuckEscape.warpAllowed("OFF_PATH", 2, 3000), "off the path twice within four seconds: escape");
		check(!StuckEscape.warpAllowed("OFF_PATH", 2, 5000), "twice but far apart: replan");
		check(StuckEscape.warpAllowed("STUCK", 1, 0), "a real stall may escape at once");
		check(!StuckEscape.warpAllowed("NO_PATH", 1, 0), "no path is not an escape");
		// A warp onto a mob only when walking is a poor way there.
		check(StuckEscape.mobWarpUseful(40.0, 10.0), "walk cost over twice the straight distance: warp onto it");
		check(StuckEscape.mobWarpUseful(Double.POSITIVE_INFINITY, 10.0), "unreachable on foot: warp onto it");
		check(!StuckEscape.mobWarpUseful(15.0, 10.0), "a walk that is not much longer: walk");
		check(StuckEscape.mobWarpUseful(Double.NaN, 10.0), "no map to say: the old rule, warp allowed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
