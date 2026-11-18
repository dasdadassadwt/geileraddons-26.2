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

		checkPets();
		checkCommandKeybinds();

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

	private static void checkCommandKeybinds() {
		List<String> ids = ContainerKeybindRules.COMMAND_PRESETS.stream().map(preset -> preset.getId()).toList();
		check(ids.equals(List.of("pets", "storage", "wardrobe", "equipment", "loadouts", "stats", "dungeonHub", "potionBag")),
			"Command Keybinds has Odin's eight presets");
		check("warp dungeon_hub".equals(ContainerKeybindRules.commandFor("key.keyboard.k", List.of("", "", "", "", "", "", "key.keyboard.k", "")))
			&& "pets".equals(ContainerKeybindRules.commandFor("key.keyboard.p", List.of("key.keyboard.p", "", "", "", "", "", "", "")))
			&& ContainerKeybindRules.commandFor("key.keyboard.x", List.of("key.keyboard.p", "", "", "", "", "", "", "")) == null
			&& ContainerKeybindRules.commandFor("", List.of("", "", "", "", "", "", "", "")) == null,
			"a pressed key maps to its preset command; an empty key never matches an unbound preset");
		CommandKeybindsModule module = CommandKeybindsModule.INSTANCE;
		check(!module.defaultEnabled() && module.textSettings().size() == 8 && module.textSettings().stream().allMatch(setting -> setting.keybindCapture() && setting.value().isEmpty()),
			"Command Keybinds declares eight unbound key-capture settings");
	}

	private static void checkPets() {
		check(ContainerKeybindRules.isPetsTitle("(1/3) Pets") && !ContainerKeybindRules.isPetsTitle("Pets") && !ContainerKeybindRules.isPetsTitle("(1/3) Loadout"),
			"the pets menu is recognised by its page counter");
		String pets = "(2/3) Pets";
		List<String> keys = List.of("key.keyboard.1", "key.keyboard.2", "key.keyboard.3", "key.keyboard.4", "key.keyboard.5",
			"key.keyboard.6", "key.keyboard.7", "key.keyboard.8", "key.keyboard.9");
		check(pet(pets, NEXT, keys, null, false, false).getSlot() == 53 && pet(pets, PREVIOUS, keys, null, false, false).getSlot() == 45,
			"pet page keys click slots 53 and 45");
		check(!pet("(3/3) Pets", NEXT, keys, null, false, false).getHandled() && !pet("(1/3) Pets", PREVIOUS, keys, null, false, false).getHandled(),
			"pet page keys stop at the ends");
		check(pet(pets, "key.keyboard.1", keys, null, false, false).getSlot() == 10 && pet(pets, "key.keyboard.7", keys, null, false, false).getSlot() == 16
			&& pet(pets, "key.keyboard.8", keys, null, false, false).getSlot() == 19 && pet(pets, "key.keyboard.9", keys, null, false, false).getSlot() == 20,
			"pet keys map onto the first nine pet slots");
		check(!pet(pets, "key.keyboard.x", keys, null, false, false).getHandled(), "an unbound key is ignored in the pets menu");
		ContainerKeybindRules.Outcome again = pet(pets, "key.keyboard.2", keys, 11, true, false);
		check(again.getHandled() && again.getSlot() == -1 && ContainerKeybindRules.PET_ALREADY_EQUIPPED.equals(again.getMessage()) && !again.getClose(),
			"with Disable Unequip, the equipped pet's key only shows a message");
		check(pet(pets, "key.keyboard.2", keys, 11, true, true).getClose(), "Close If Equipped closes the menu");
		check(pet(pets, "key.keyboard.2", keys, 11, false, false).getSlot() == 11, "without Disable Unequip, the equipped pet's key clicks it");
		check(ContainerKeybindRules.pets(pets, "key.keyboard.u", NEXT, PREVIOUS, "key.keyboard.u", keys, 12, false, false).getSlot() == 12
			&& !ContainerKeybindRules.pets(pets, "key.keyboard.u", NEXT, PREVIOUS, "key.keyboard.u", keys, null, false, false).getHandled()
			&& !ContainerKeybindRules.pets(pets, "key.keyboard.u", NEXT, PREVIOUS, "key.keyboard.u", keys, 12, true, false).getHandled(),
			"the Unequip key clicks the equipped pet, and does nothing without one or with Disable Unequip");
		check(ContainerKeybindRules.equippedPetSlot(List.of(new Pair<>(10, List.of("Click to summon!")), new Pair<>(14, List.of("x", "Click to despawn!")))).equals(14)
			&& ContainerKeybindRules.equippedPetSlot(List.of(new Pair<>(9, List.of("Click to despawn!")), new Pair<>(43, List.of("Click to despawn!")))) == null,
			"the equipped pet is the slot 10-42 whose lore says Click to despawn!");
		PetKeybindsModule module = PetKeybindsModule.INSTANCE;
		check(!module.defaultEnabled() && module.textSettings().size() == 12 && module.booleanSettings().size() == 2
			&& module.textSettings().stream().allMatch(setting -> setting.keybindCapture()),
			"Pet Keybinds declares its navigation and nine pet keys as key-capture settings");
	}

	private static ContainerKeybindRules.Outcome pet(String title, String key, List<String> keys, Integer equipped, boolean disallow, boolean close) {
		return ContainerKeybindRules.pets(title, key, NEXT, PREVIOUS, "", keys, equipped, disallow, close);
	}

	private static ContainerKeybindRules.Outcome wardrobe(String title, String key, Integer equipped, boolean disallow) {
		return ContainerKeybindRules.wardrobe(title, key, NEXT, PREVIOUS, "", SET_KEYS, equipped, disallow, slot -> false);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
