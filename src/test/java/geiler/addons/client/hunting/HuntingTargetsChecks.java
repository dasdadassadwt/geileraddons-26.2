package geiler.addons.client.hunting;

import kotlin.random.RandomKt;

/** Offline checks for Queen Ant Alert, Blue Jay ESP and the Lasso HUD / Auto Reel rules. */
public final class HuntingTargetsChecks {
	private HuntingTargetsChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkQueenAnt();
		checkBlueJay();
		checkLasso();
		checkReelScheduler();
		System.out.println("Hunting targets checks passed");
	}

	private static void checkQueenAnt() {
		check(QueenAnt.matches("§c[Lv69] §fQueen Ant §a25,000§f/§a25,000❤"), "a levelled Queen Ant tag matches");
		check(QueenAnt.matches("Ant Queen"), "the other word order matches");
		check(!QueenAnt.matches("Queen Ant Shard"), "a shard label is not the mob");
		check(!QueenAnt.matches("[Lv5] Ant 100/100❤"), "a worker Ant is not the queen");
		check(!QueenAnt.matches(null), "no label, no match");
		check(QueenAnt.partyMessage(123, 23, 511).equals("Queen Ant at x: 123 y: 23 z: 511"), "party line uses the requested coordinate format");
	}

	private static void checkBlueJay() {
		check(BlueJay.state("Blue Jay", null) == BlueJay.State.SAFE, "no mark: safe to lasso");
		check(BlueJay.state("§c/!\\ §fBlue Jay", 0xFF5555) == BlueJay.State.ALERT, "red mark: alert");
		check(BlueJay.state("/!\\ Blue Jay", 0xFFFF55) == BlueJay.State.ALMOST, "yellow mark: almost settled");
		check(BlueJay.state("§e/!\\ §fBlue Jay", null) == BlueJay.State.ALMOST, "a legacy yellow mark: almost settled");
		check(BlueJay.state("/!\\ Blue Jay", null) == BlueJay.State.ALERT, "a mark with unknown colour counts as alert");
		check(BlueJay.state("Blue Jay Shard", null) == null, "a shard label is not the bird");
		check(BlueJay.state("Bluebird", null) == null, "the Safari Bluebird is not a Blue Jay");
	}

	private static void checkLasso() {
		check(LassoLogic.reelCells("ABYSMAL_LASSO") == 2 && LassoLogic.reelCells("VINERIP_LASSO") == 3
			&& LassoLogic.reelCells("ENTANGLER_LASSO") == 3 && LassoLogic.reelCells("EVERSTRETCH_LASSO") == 4, "lasso tiers");
		check(!LassoLogic.isLasso("MOODY_GRAPPLESHOT") && !LassoLogic.isLasso(null), "other leashing items are not lassos");
		check(LassoLogic.isBar("                    ", 2), "a 20-cell blank label with two parts is the bar");
		check(!LassoLogic.isBar("                    ", 1) && !LassoLogic.isBar("   ", 2) && !LassoLogic.isBar("REEL", 2), "other labels are not the bar");
		check(LassoLogic.percent(20, 2) == 100 && LassoLogic.percent(11, 2) == 50 && LassoLogic.percent(2, 2) == 0
			&& LassoLogic.percent(0, 4) == 0, "bar percent runs from full to the reel point");
		check(LassoLogic.isReelPrompt("§e§lREEL") && LassoLogic.isReelPrompt(" REEL ") && !LassoLogic.isReelPrompt("REELING"), "REEL prompt");
	}

	private static void checkReelScheduler() {
		ReelScheduler s = new ReelScheduler(RandomKt.Random(7));
		check(s.onReel(1000, 200, 200, 80, 80), "a REEL schedules a click");
		check(!s.onReel(1001, 0, 0, 0, 0), "a second REEL while pending does not double-click");
		check(s.poll(1199, true) == ReelScheduler.Action.NONE, "waits out the reaction delay");
		check(s.poll(1200, true) == ReelScheduler.Action.PRESS && s.isPressed(), "presses after the reaction delay");
		check(s.poll(1279, false) == ReelScheduler.Action.NONE, "a held press is not cut short");
		check(s.poll(1280, true) == ReelScheduler.Action.RELEASE && !s.pending(), "releases after the hold");

		check(s.onReel(2000, 100, 300, 50, 120), "a later REEL schedules again");
		check(s.poll(2000, false) == ReelScheduler.Action.NONE && !s.pending(), "a guard failure cancels an unpressed click");

		ReelScheduler r = new ReelScheduler(RandomKt.Random(1));
		for (int i = 0; i < 200; i++) {
			r.onReel(0, 180, 320, 60, 110);
			long press = -1;
			for (long t = 0; t <= 500; t++) {
				ReelScheduler.Action action = r.poll(t, true);
				if (action == ReelScheduler.Action.PRESS) press = t;
				if (action == ReelScheduler.Action.RELEASE) {
					check(press >= 180 && press <= 320 && t - press >= 60 && t - press <= 110, "random delays stay inside the set ranges");
					break;
				}
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
