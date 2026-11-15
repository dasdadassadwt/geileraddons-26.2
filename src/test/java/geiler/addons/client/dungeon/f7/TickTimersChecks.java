package geiler.addons.client.dungeon.f7;

/** Offline checks for Tick Timers (Odin port) and Relic Timer (Devonian port) pure state. */
public final class TickTimersChecks {
	private TickTimersChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkGoldorAndStormFlow();
		checkOutsideBossDoesNotCount();
		checkFormatting();
		checkRelicTimer();
		checkFireFreezeTimer();
	}

	private static void checkFireFreezeTimer() {
		FireFreezeTimerLogic f = new FireFreezeTimerLogic();
		require(f.text(false, true, true) == null, "no text before the Professor line");
		f.onChat("[BOSS] The Professor: Oh? You found my Guardians' one weakness?");
		require(f.getTicks() == 206 && f.text(false, true, true).equals("Fire Freeze: §a5.30s"),
			"Professor line starts 206 ticks, 106 shown: " + f.text(false, true, true));
		for (int i = 0; i < 106; i++) f.onServerTick(true);
		require(f.getTicks() == 100 && f.text(false, true, true).equals("Use Fire Freeze"),
			"at 100 ticks left it says to use the staff: " + f.text(false, true, true));
		f.onServerTick(false);
		require(f.getTicks() == 100, "ticks outside the boss room do nothing");
		for (int i = 0; i < 101; i++) f.onServerTick(true);
		require(f.getTicks() == -1 && f.text(false, true, true) == null, "timer ends after the last tick");
		f.onChat("[BOSS] The Professor: Oh? You found my Guardians' one weakness?");
		f.reset();
		require(f.text(false, true, true) == null, "reset clears the timer");
	}

	private static void checkGoldorAndStormFlow() {
		TickTimersLogic t = new TickTimersLogic();
		t.onChat("[BOSS] Goldor: Who dares trespass into my domain?");
		require(t.getGoldorTick() == 60, "Goldor chat starts the 60 tick timer");
		for (int i = 0; i < 61; i++) t.onServerTick(true, true, true);
		require(t.getGoldorTick() == 59, "Goldor timer restarts at 60 then counts on while its HUD is on: " + t.getGoldorTick());
		t.onChat("The Core entrance is opening!");
		require(t.getGoldorTick() == -1 && t.getGoldorStart() == -1, "Core opening clears both Goldor timers");

		t.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.");
		require(t.getPad() == 20 && t.getLightning() == 520 && t.getStormTick() == 0, "Storm start sets pad, lightning and tick");
		t.onServerTick(true, false, false);
		require(t.getPad() == 19 && t.getLightning() == 519 && t.getStormTick() == 1, "one tick moves every storm timer");
		t.onChat("[BOSS] Storm: ENERGY HEED MY CALL!");
		require(t.getPy() == 55, "PY starts from the first power line");
		t.onServerTick(true, false, false);
		t.onChat("[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!");
		require(t.getPy() == 54, "PY is not restarted by a second power line");
		t.onChat("[BOSS] Storm: I should have known that I stood no chance.");
		require(t.getGoldorStart() == 64 && t.getPad() == -1 && t.getStormTick() == -1, "Storm end starts the Goldor start timer");
		t.reset();
		require(t.getGoldorStart() == -1 && t.getPy() == -1 && t.getLightning() == -1,
			"reset clears everything");
		t.onChat("[BOSS] Storm: ENERGY HEED MY CALL!");
		require(t.getPy() == 55, "reset re-arms the PY trigger");
	}

	private static void checkOutsideBossDoesNotCount() {
		TickTimersLogic t = new TickTimersLogic();
		t.onChat("[BOSS] Goldor: Who dares trespass into my domain?");
		t.onServerTick(false, true, true);
		require(t.getGoldorTick() == 60, "ticks outside the boss room do nothing");
		t.onChat("x [BOSS] Goldor: Who dares trespass into my domain?");
		t.onChat("unrelated chat");
		require(t.getGoldorTick() == 60, "non matching chat is ignored");
	}

	private static void checkFormatting() {
		require(TickTimersLogic.format(60, 60, "§7Tick:", null, false, true, true).equals("§7Tick: §a3.00s"),
			"full timer is green seconds with prefix and symbol");
		require(TickTimersLogic.format(30, 60, "§7Tick:", null, false, true, true).equals("§7Tick: §61.50s"),
			"half timer is gold");
		require(TickTimersLogic.format(10, 60, "§bPad:", null, true, true, false).equals("§c10t"),
			"low timer is red ticks without prefix");
		require(TickTimersLogic.format(200, 540, "§bStorm:", "§a", false, false, true).equals("§bStorm: §a10.00"),
			"override color and no symbol");
	}

	private static void checkRelicTimer() {
		RelicTimerLogic r = new RelicTimerLogic();
		require(r.text(100L) == null, "no text before the Necron line");
		r.onChat("[BOSS] Necron: All this, for nothing...", 1000L);
		require(r.remaining(1000L) == 45 && r.text(1000L).equals("§a2.25s"), "Necron line starts 45 ticks: " + r.text(1000L));
		require(r.text(1044L).equals("§a0.05s"), "44 ticks later: " + r.text(1044L));
		require(r.text(1045L) == null && r.text(1200L) == null, "timer ends at zero and stays hidden");
		// The countdown is an absolute target: skipped ticks (extrapolation jumps) cannot make it drift.
		r.onChat("[BOSS] Necron: All this, for nothing...", 2000L);
		require(r.remaining(2020L) == 25 && r.remaining(2020L) == 25, "remaining depends only on the current tick");
		require(r.remaining(2019L) == 26 && r.remaining(2040L) == 5, "a tick jump of 20 moves it by exactly 20");
		require(r.ticksSinceStart(2030L) == 30, "ticks since the Necron line are reported for diagnostics");
		r.onChat("[BOSS] Necron: not the line", 2030L);
		require(r.remaining(2030L) == 15, "other lines do not restart it");
		r.setSpawnTicks(42);
		r.onChat("[BOSS] Necron: All this, for nothing...", 3000L);
		require(r.remaining(3000L) == 42, "the spawn tick count is configurable");
		r.reset();
		require(r.text(3000L) == null && r.ticksSinceStart(3000L) == -1L, "reset clears the timer");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
