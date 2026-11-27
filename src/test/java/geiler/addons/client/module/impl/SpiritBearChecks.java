package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.f7.SpiritBearState;
import net.minecraft.world.level.block.Blocks;

public final class SpiritBearChecks {
	private SpiritBearChecks() { }

	public static void run() {
		SpiritBearState state = new SpiritBearState();
		check("§6Bear: §d0/30".equals(state.text(true, 0L)), "M4 needs 30");
		check("§6Bear: §d0/25".equals(state.text(false, 0L)), "F4 needs 25");

		check(light(state, -7, 77, 32, true, 0L), "an M4 lamp counts");
		check("§6Bear: §d1/30".equals(state.text(true, 0L)), "one kill");
		check(!light(state, -9, 77, 31, true, 0L), "an F4 lamp does not count in M4");
		check(!state.onBlockChange(-7, 77, 32, Blocks.AIR, Blocks.SEA_LANTERN, true, 0L), "only coal to lantern counts");
		check(state.onBlockChange(-7, 77, 32, Blocks.SEA_LANTERN, Blocks.COAL_BLOCK, true, 0L), "lamp back to coal");
		check(state.kills() == 0, "count goes back down");
		state.onBlockChange(-7, 77, 32, Blocks.SEA_LANTERN, Blocks.COAL_BLOCK, true, 0L);
		check(state.kills() == 0, "never negative");

		for (int i = 0; i < 40; i++) light(state, -2, 77, 33, true, 0L);
		check(state.kills() == 30, "capped at 30");

		light(state, 7, 77, 34, true, 100L);
		check("§6Bear: §e3.40s".equals(state.text(true, 100L)), "68 server ticks countdown: " + state.text(true, 100L));
		check("§6Bear: §e0.05s".equals(state.text(true, 167L)), "last tick");
		check("§6Bear: §aAlive!".equals(state.text(true, 168L)), "alive");
		state.onBlockChange(7, 77, 34, Blocks.SEA_LANTERN, Blocks.COAL_BLOCK, true, 200L);
		check(state.text(true, 200L).endsWith("29/30"), "last lamp off cancels the spawn");
		state.reset();
		check(state.kills() == 0, "reset");

		check(!SpiritBearModule.INSTANCE.isEnabled(), "off by default");
	}

	private static boolean light(SpiritBearState state, int x, int y, int z, boolean master, long now) {
		return state.onBlockChange(x, y, z, Blocks.COAL_BLOCK, Blocks.SEA_LANTERN, master, now);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
