package geiler.addons.client.module.impl;

import geiler.addons.client.combat.LegionRules;
import geiler.addons.client.combat.ShareMarks;

public final class LegionLootshareChecks {
	private LegionLootshareChecks() { }

	public static void run() {
		check("§d§lLegion: §b3 §7(+1.05%)".equals(LegionRules.legionText(5, 3)), LegionRules.legionText(5, 3));
		check("§d§lLegion: §b20 §7(+7%)".equals(LegionRules.legionText(5, 40)), "players cap at 20: " + LegionRules.legionText(5, 40));
		check("§3§lBobbin': §b5 §7(+5%)".equals(LegionRules.bobbinText(5, 9)), "bobbers cap at 5: " + LegionRules.bobbinText(5, 9));
		check("§3§lBobbin': §b0 §7(+0%)".equals(LegionRules.bobbinText(3, 0)), "none nearby");
		check(LegionRules.inRange(30.0 * 30.0) && !LegionRules.inRange(30.01 * 30.01), "30 blocks inclusive");

		ShareMarks marks = new ShareMarks();
		check(!marks.hit(5), "starts not hit");
		marks.onAttack(5);
		check(marks.hit(5) && !marks.hit(6), "an attack marks only that mob");
		marks.onDamage(6, 99, 42);
		check(!marks.hit(6), "someone else's damage does not mark");
		marks.onDamage(6, 42, 42);
		check(marks.hit(6), "damage caused by you marks");
		marks.retain(java.util.Set.of(6));
		check(!marks.hit(5) && marks.hit(6), "gone mobs are forgotten");
		marks.clear();
		check(!marks.hit(6), "cleared");

		check(!LegionDisplayModule.INSTANCE.isEnabled() && !LootshareRangeModule.INSTANCE.isEnabled(), "both off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
