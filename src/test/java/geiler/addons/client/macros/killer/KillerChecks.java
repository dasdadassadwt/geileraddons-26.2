package geiler.addons.client.macros.killer;

import geiler.addons.client.macros.config.AreaKillerConfig;

import java.util.List;

/** Offline checks of the Area Killer pieces that need no world: kill list entries, name and health parsing, the clock. */
public final class KillerChecks {
	private KillerChecks() { }

	public static void run() {
		checkNames();
		checkHealth();
		checkIds();
		checkKillList();
		checkClock();
		System.out.println("Area Killer checks passed.");
	}

	private static void checkNames() {
		check(MobNames.normalize("[Lv61] Treasure Hoarder 15,000/15,000").equals("treasure hoarder"), "level tag and health are not part of a name");
		check(MobNames.normalize("§c[Lv5] §fZombie Villager §a1.5k/2M❤").equals("zombie villager"), "formatting, short amounts and symbols are dropped");
		check(MobNames.normalize("Lv12 Wolf").equals("wolf"), "a bare level word is dropped");
		check(MobNames.normalize("Old-Wolf's Den").equals("old-wolf's den"), "apostrophes and hyphens stay");
		check(MobNames.normalize(null).isEmpty() && MobNames.normalize("  123 /// ").isEmpty(), "nothing in, nothing out");
		check(MobNames.isNameEntry("name:wolf") && !MobNames.isNameEntry("minecraft:wolf"), "a name entry starts with name:");
	}

	private static void checkHealth() {
		check(MobNames.healthFractionOf(List.of("[Lv61] Treasure Hoarder 7,500/15,000")) == 0.5, "health is read off the tag");
		check(MobNames.healthFractionOf(List.of("Boss 1.5M/3M")) == 0.5, "short amounts scale");
		check(MobNames.healthFractionOf(List.of("no numbers", "Mob 0/20")) == 0.0, "the first text with health counts");
		check(Double.isNaN(MobNames.healthFractionOf(List.of("Zombie"))), "no health is NaN");
		check(Double.isNaN(MobNames.healthFractionOf(List.of("Odd 30/20"))), "more than the maximum is not health");
	}

	private static void checkIds() {
		check(MobIds.normalize(" Zombie ").equals("minecraft:zombie"), "a bare id gets the vanilla namespace");
		check(MobIds.normalize("mod:Thing").equals("mod:thing"), "a namespaced id is kept, lower case");
		check(MobIds.normalize("name:[Lv3] Cave Spider").equals("name:cave spider"), "a name entry is normalized as a name");
		check("name:treasure hoarder".equals(MobIds.nameEntry("Treasure Hoarder")), "a name becomes a name entry");
		check("name:wolf".equals(MobIds.nameEntry("NAME:Wolf")), "an already prefixed name is not prefixed twice");
		check(MobIds.nameEntry("  12 ") == null, "a text without words is no entry");
		check(MobIds.label("minecraft:cave_spider").equals("Cave Spider"), "a type reads as words");
		check(MobIds.label("name:cave spider").equals("Cave Spider"), "so does a name");
	}

	private static void checkKillList() {
		AreaKillerConfig config = new AreaKillerConfig();
		config.addMob("Zombie");
		config.addMob("minecraft:zombie");
		config.addMob("name:Treasure Hoarder");
		check(config.mobs.equals(List.of("minecraft:zombie", "name:treasure hoarder")), "entries are normalized and not doubled");
		check(config.removeMob("zombie"), "a type is removed by its short form");
		check(config.removeMob("Treasure Hoarder"), "a name is removed without its prefix");
		check(!config.removeMob("skeleton") && config.mobs.isEmpty(), "what is not listed is not removed");
	}

	private static void checkClock() {
		check(AreaKillerMacro.clock(0L).equals("00:00"), "zero");
		check(AreaKillerMacro.clock(245_999L).equals("04:05"), "minutes and seconds");
		check(AreaKillerMacro.clock(3_845_000L).equals("1:04:05"), "hours once there are any");
		check(AreaKillerMacro.clock(-5L).equals("00:00"), "never negative");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
