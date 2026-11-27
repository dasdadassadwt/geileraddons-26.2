package geiler.addons.client.dungeon.f7;

import java.util.Random;

/** Offline checks for Auto Mask: which mask follows a proc, name matching and the step order and timing. */
public final class AutoMaskChecks {
	private AutoMaskChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		require(AutoMaskRules.SPIRIT.equals(AutoMaskRules.otherMask("BONZO")), "a Bonzo proc equips the Spirit Mask");
		require(AutoMaskRules.BONZO.equals(AutoMaskRules.otherMask("SPIRIT")), "a Spirit proc equips the Bonzo Mask");
		require(AutoMaskRules.otherMask("PHOENIX") == null, "a Phoenix proc equips nothing");
		require(AutoMaskRules.isMask("Ancient ⚚ Bonzo's Mask ✪✪✪✪✪", AutoMaskRules.BONZO), "reforges, stars and fragged still match");
		require(!AutoMaskRules.isMask("Spirit Boots", AutoMaskRules.SPIRIT), "other Spirit gear is not the mask");

		require(AutoMaskRules.next(AutoMaskRules.Step.IDLE, false) == AutoMaskRules.Step.OPEN_MENU, "without a rod the menu comes first");
		AutoMaskRules.Sequence seq = new AutoMaskRules.Sequence(new Random(5));
		require(!seq.running(), "an idle sequence does nothing");
		seq.start(1_000, AutoMaskRules.SPIRIT, true, 100, 100, 5_000);
		require(seq.getStep() == AutoMaskRules.Step.ROD_SWITCH, "with a rod it starts by switching to it");
		require(!seq.due(1_099) && seq.due(1_100), "the first step waits its pause");
		AutoMaskRules.Step[] order = {
			AutoMaskRules.Step.ROD_USE, AutoMaskRules.Step.ROD_BACK, AutoMaskRules.Step.OPEN_MENU, AutoMaskRules.Step.WAIT_MENU,
			AutoMaskRules.Step.CLICK_MASK, AutoMaskRules.Step.CLOSE, AutoMaskRules.Step.DONE,
		};
		long t = 1_100;
		for (AutoMaskRules.Step want : order) {
			seq.advance(t, 100, 100);
			require(seq.getStep() == want, "next step is " + want + ", got " + seq.getStep());
			t += 100;
		}
		require(!seq.running(), "the sequence ends at DONE");
		seq.start(0, AutoMaskRules.BONZO, false, 0, 0, 2_000);
		require(!seq.timedOut(1_999) && seq.timedOut(2_000), "a stuck sequence times out");
		seq.stop();
		require(!seq.running() && seq.getWanted() == null, "stop clears the sequence");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
