package geiler.addons.client.module.impl;

import java.util.List;
import kotlin.Pair;

/** Offline checks for the Wardrobe and Loadout keybind rules and module declarations. */
public final class ContainerKeybindChecks {
	private ContainerKeybindChecks() { }

	private static final String NEXT = "key.keyboard.right";
	private static final String PREVIOUS = "key.keyboard.left";
	private static final List<String> SET_KEYS = List.of("key.keyboard.1", "key.keyboard.2", "key.keyboard.3", "key.keyboard.4",
		"key.keyboard.5", "key.keyboard.6", "key.keyboard.7", "key.keyboard.8", "key.keyboard.9");

	public static void run() {
		check("key.keyboard.1".equals(ContainerKeybindRules.normalize("1")) && "key.keyboard.a".equals(ContainerKeybindRules.normalize("A"))
			&& "key.keyboard.right".equals(ContainerKeybindRules.normalize(" key.keyboard.right ")) && "".equals(ContainerKeybindRules.normalize(null)),
			"saved keys normalise to full key names");
		check(ContainerKeybindRules.isWardrobeTitle("Wardrobe (2/3) Armor Sets") && ContainerKeybindRules.isWardrobeTitle("(1/2) Equipment Sets")
			&& !ContainerKeybindRules.isWardrobeTitle("Wardrobe") && ContainerKeybindRules.isLoadoutTitle("(1/2) Loadout")
			&& !ContainerKeybindRules.isLoadoutTitle("Loadout"), "menu titles are recognised by their page counter");
		check(Integer.valueOf(40).equals(ContainerKeybindRules.equippedWardrobeSlot(List.of(
			new Pair<>(36, "Slot 1: Empty"), new Pair<>(40, "Slot 5: Equipped")))) && ContainerKeybindRules.equippedWardrobeSlot(List.of()) == null,
			"the equipped set is found by its item name");

		String title = "Wardrobe (2/3) Armor Sets";
		check(wardrobe(title, NEXT, null, false).getSlot() == 53 && wardrobe(title, NEXT, null, false).getHandled(), "Next Page clicks slot 53 before the last page");
		check(!wardrobe("Wardrobe (3/3) Armor Sets", NEXT, null, false).getHandled(), "Next Page does nothing on the last page");
		check(wardrobe(title, PREVIOUS, null, false).getSlot() == 45 && !wardrobe("Wardrobe (1/3) Armor Sets", PREVIOUS, null, false).getHandled(),
			"Previous Page clicks slot 45 and does nothing on the first page");
		check(wardrobe(title, "key.keyboard.3", null, false).getSlot() == 38, "set key 3 clicks slot 38");
		check(wardrobe(title, "key.keyboard.unknown", null, false).getHandled() == false, "an unbound key is ignored");
		check(ContainerKeybindRules.wardrobe(title, "key.keyboard.u", NEXT, PREVIOUS, "key.keyboard.u", SET_KEYS, 40, false, slot -> false).getSlot() == 40,
			"the Unequip key clicks the equipped slot");
		check(!ContainerKeybindRules.wardrobe(title, "key.keyboard.u", NEXT, PREVIOUS, "key.keyboard.u", SET_KEYS, null, false, slot -> false).getHandled(),
			"the Unequip key does nothing without an equipped set");
		ContainerKeybindRules.Outcome already = wardrobe(title, "key.keyboard.3", 38, true);
		check(!already.getHandled() && ContainerKeybindRules.ALREADY_EQUIPPED.equals(already.getMessage()),
			"with Disable Unequip, the equipped set's key shows a message and passes through");
		check(!ContainerKeybindRules.wardrobe(title, "key.keyboard.4", NEXT, PREVIOUS, "", SET_KEYS, 38, true, slot -> true).getHandled(),
			"with Disable Unequip, an empty target slot is ignored");
		check(!ContainerKeybindRules.wardrobe(title, "", NEXT, PREVIOUS, "", SET_KEYS, null, false, slot -> false).getHandled(),
			"an empty pressed key never matches an unbound setting");

		String loadout = "(2/3) Loadout";
		List<String> keys = List.of("key.keyboard.1", "key.keyboard.2", "key.keyboard.3", "key.keyboard.4", "key.keyboard.5", "key.keyboard.6",
			"key.keyboard.7", "key.keyboard.8", "key.keyboard.9", "key.keyboard.0", "key.keyboard.minus", "key.keyboard.equal");
		check(ContainerKeybindRules.loadout(loadout, NEXT, NEXT, PREVIOUS, keys).getSlot() == 44
			&& ContainerKeybindRules.loadout(loadout, PREVIOUS, NEXT, PREVIOUS, keys).getSlot() == 17, "loadout page keys click slots 44 and 17");
		check(!ContainerKeybindRules.loadout("(3/3) Loadout", NEXT, NEXT, PREVIOUS, keys).getHandled()
			&& !ContainerKeybindRules.loadout("(1/3) Loadout", PREVIOUS, NEXT, PREVIOUS, keys).getHandled(), "loadout page keys stop at the ends");
		check(ContainerKeybindRules.loadout(loadout, "key.keyboard.1", NEXT, PREVIOUS, keys).getSlot() == 14
			&& ContainerKeybindRules.loadout(loadout, "key.keyboard.minus", NEXT, PREVIOUS, keys).getSlot() == 42
			&& ContainerKeybindRules.loadout(loadout, "key.keyboard.equal", NEXT, PREVIOUS, keys).getSlot() == 43,
			"loadout slot keys map onto the twelve menu slots");
		check(!ContainerKeybindRules.loadout("Chest", "key.keyboard.1", NEXT, PREVIOUS, keys).getHandled(), "other menus are left alone");

		WardrobeKeybindsModule wardrobe = WardrobeKeybindsModule.INSTANCE;
		check(!wardrobe.defaultEnabled() && wardrobe.textSettings().size() == 12 && wardrobe.booleanSettings().size() == 1
			&& NEXT.equals(wardrobe.textSettings().get(0).value()) && PREVIOUS.equals(wardrobe.textSettings().get(1).value())
			&& wardrobe.textSettings().get(2).value().isEmpty() && "key.keyboard.9".equals(wardrobe.textSettings().get(11).value())
			&& wardrobe.textSettings().stream().allMatch(setting -> setting.keybindCapture()),
			"Wardrobe Keybinds declares its page, unequip and nine set keys as key-capture settings");
		LoadoutKeybindsModule loadoutModule = LoadoutKeybindsModule.INSTANCE;
		check(!loadoutModule.defaultEnabled() && loadoutModule.textSettings().size() == 14
			&& "key.keyboard.equal".equals(loadoutModule.textSettings().get(13).value())
			&& loadoutModule.textSettings().stream().allMatch(setting -> setting.keybindCapture()),
			"Loadout Keybinds declares its page keys and twelve loadout keys as key-capture settings");
	}

	private static ContainerKeybindRules.Outcome wardrobe(String title, String key, Integer equipped, boolean disallow) {
		return ContainerKeybindRules.wardrobe(title, key, NEXT, PREVIOUS, "", SET_KEYS, equipped, disallow, slot -> false);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
