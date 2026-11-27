package geiler.addons.client.module.impl;

import geiler.addons.client.inventory.EquipmentRules;

public final class EquipmentInInventoryChecks {
	private EquipmentInInventoryChecks() { }

	public static void run() {
		check(EquipmentRules.isMenu("Stats & Equipment") && EquipmentRules.isMenu("Your Equipment and Stats"), "both titles");
		check(!EquipmentRules.isMenu("(1/2) Equipment Sets") && !EquipmentRules.isMenu("Equipment"), "other menus");
		int[] menu = {10, 19, 28, 37};
		for (int i = 0; i < 4; i++) check(EquipmentRules.menuSlot(i) == menu[i], "menu slot " + i);

		check("0d57b1ab-5f0c-4a3a-9c9a-1f2e3d4c5b6a".equals(
			EquipmentRules.profileId("Profile ID: 0d57b1ab-5f0c-4a3a-9c9a-1f2e3d4c5b6a")), "profile line");
		check(EquipmentRules.profileId("Steve: Profile ID: 0d57b1ab-5f0c-4a3a-9c9a-1f2e3d4c5b6a") == null, "player chat is ignored");
		check(EquipmentRules.profileId("Profile ID: nope") == null, "not an id");
		check("abc".equals(EquipmentRules.key("abc", false)) && "abc:rift".equals(EquipmentRules.key("abc", true)),
			"the Rift keeps its own equipment");

		// The column takes the offhand's place, right of the player preview, in line with the armor slots.
		int left = 200;
		int top = 50;
		check(EquipmentRules.slotX(left) == left + 77 && EquipmentRules.slotY(top, 0) == top + 8
			&& EquipmentRules.slotY(top, 3) == top + 62, "slot positions");
		check(EquipmentRules.OFFHAND_HOME_X == 77 && EquipmentRules.OFFHAND_MOVED_X == 95, "offhand moves one slot right");
		check(EquipmentRules.panelContains(left, top, left + 77, top + 8), "inside a slot");
		check(!EquipmentRules.panelContains(left, top, left + 95, top + 62), "the moved offhand is outside");
		check(!EquipmentRules.panelContains(left, top, left + 8, top + 8), "the armor column is outside");
		check(EquipmentRules.slotAt(left, top, left + 77, top + 8) == 0
			&& EquipmentRules.slotAt(left, top, left + 92, top + 77) == 3
			&& EquipmentRules.slotAt(left, top, left + 77, top + 90) == -1, "hover");

		check(!EquipmentInInventoryModule.INSTANCE.isEnabled(), "off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
