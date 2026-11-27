package geiler.addons.client.module.impl;

import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;

public final class MuteEndermanSoundsChecks {
	private MuteEndermanSoundsChecks() { }

	public static void run() {
		MuteEndermanSoundsModule module = MuteEndermanSoundsModule.INSTANCE;
		check(!module.isEnabled(), "off by default");
		check(module.soundToggle("minecraft:entity.enderman.ambient").value()
			&& module.soundToggle("minecraft:entity.enderman.scream").value()
			&& module.soundToggle("minecraft:entity.enderman.stare").value(), "ambient, scream and stare default on");
		check(!module.soundToggle("minecraft:entity.enderman.teleport").value()
			&& !module.soundToggle("minecraft:entity.enderman.hurt").value()
			&& !module.soundToggle("minecraft:entity.enderman.death").value(), "teleport, hurt and death default off");
		check(module.soundToggle("minecraft:entity.wither.hurt") == null, "other sounds have no toggle");
		for (Island island : Island.values()) {
			if (island.selectable()) check(module.islandAllowed(island), island + " defaults on");
		}
		check(module.islandAllowed(Island.NONE) && module.islandAllowed(Island.OTHER), "unknown islands are muted too");

		check(!module.shouldMute("minecraft:entity.enderman.ambient", Island.THE_END), "disabled module mutes nothing");
		module.setEnabled(true);
		try {
			check(module.shouldMute("minecraft:entity.enderman.ambient", Island.THE_END), "ambient muted");
			check(!module.shouldMute("minecraft:entity.enderman.teleport", Island.THE_END), "teleport kept");
			check(!module.shouldMute("minecraft:block.anvil.land", Island.THE_END), "other sound kept");
			BooleanSetting end = module.islandToggle(Island.THE_END);
			end.setValue(false);
			try {
				check(!module.shouldMute("minecraft:entity.enderman.ambient", Island.THE_END), "island switched off");
				check(module.shouldMute("minecraft:entity.enderman.ambient", Island.HUB), "other islands unchanged");
			} finally {
				end.setValue(true);
			}
		} finally {
			module.setEnabled(false);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
