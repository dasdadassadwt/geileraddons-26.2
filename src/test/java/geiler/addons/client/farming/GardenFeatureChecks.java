package geiler.addons.client.farming;

import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.config.MacroScriptConfigCodec;
import geiler.addons.client.module.impl.CropFeverTimerModule;
import geiler.addons.client.module.impl.PestTrackerModule;

import java.util.List;

/** Focused no-server acceptance checks for the new Garden and Slayer state helpers. */
public final class GardenFeatureChecks {
	private GardenFeatureChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		cropFeverCountdown();
		pestChatAndWidgetParsing();
		cooldownReadyIsOnceAndWaitsForBothSources();
		spawnAverageAndToolFilter();
		infestedPlotWarpSelection();
		pestSessionResetPreservesLifetime();
		slayerTransitionsAndRates();
		SlayerAlertChecks.run();
		macroGardenEventsRoundTrip();
	}

	private static void cropFeverCountdown() {
		CropFeverTimerModule module = CropFeverTimerModule.INSTANCE;
		check(!module.defaultEnabled(), "Crop Fever Timer defaults off for a fresh config");
		equals("Crop Fever Timer", module.name(), "Crop Fever Timer module name");
		equals("Crop Fever Timer", module.displayName(), "Crop Fever Timer display name");
		equals("Crop Fever Timer", module.configName(), "Crop Fever Timer config name");
		equals("crop_fever_timer", module.id(), "Crop Fever Timer HUD ID");
		equals("Farming", module.category().displayName(), "Crop Fever Timer category");
		equals(List.of("Background"), module.booleanSettings().stream().map(setting -> setting.name()).toList(),
			"Crop Fever Timer boolean setting IDs");
		check(module.booleanSettings().get(0).value(), "Crop Fever Timer background defaults on");
		equals(List.of("Text Color", "Background Color"),
			module.colorSettings().stream().map(setting -> setting.name()).toList(),
			"Crop Fever Timer color setting IDs");
		equals("FF55D8FF", module.colorSettings().get(0).hex(), "Crop Fever Timer text color default");
		equals("14101CBE", module.colorSettings().get(1).hex(), "Crop Fever Timer background color default");
		equals(List.of("Text Scale"), module.numberSettings().stream().map(setting -> setting.name()).toList(),
			"Crop Fever Timer number setting IDs");
		near(1.0, module.numberSettings().get(0).value(), "Crop Fever Timer text scale default");
		near(0.75, module.numberSettings().get(0).min(), "Crop Fever Timer text scale minimum");
		near(2.0, module.numberSettings().get(0).max(), "Crop Fever Timer text scale maximum");
		equals("Appearance", module.groups().get(0).name(), "Crop Fever Timer setting group");
		equals(List.of("Background", "Text Color", "Background Color", "Text Scale"),
			module.groups().get(0).settings().stream().map(setting -> setting.name()).toList(),
			"Crop Fever Timer Appearance setting order");
		equals(60_000_000_000L, CropFeverTimerState.DURATION_NANOS, "Crop Fever timer duration is exactly 60 seconds");
		CropFeverTimerState timer = new CropFeverTimerState();
		equals(CropFeverTimerState.Event.STARTED, timer.onChatMessage(
			"§aWOAH! You caught a case of the CROP FEVER for 60 seconds!§r", 5_000_000_000L),
			"Crop Fever start chat ignores formatting codes");
		near(60.0, timer.remainingSeconds(5_000_000_000L), "Crop Fever starts at one minute");
		near(1.0, timer.remainingSeconds(64_000_000_000L), "Crop Fever counts down in real time");
		equals(CropFeverTimerState.Event.ENDED, timer.onChatMessage(
			"GONE! Your CROP FEVER has been cured!", 64_000_000_000L), "Crop Fever end chat");
		near(0.0, timer.remainingSeconds(64_000_000_000L), "Crop Fever end clears timer");

		CropFeverTimerState exactTriggerTimer = new CropFeverTimerState();
		equals(CropFeverTimerState.Event.NONE, exactTriggerTimer.onChatMessage(
			"WOAH! You caught a case of the CROP FEVER for 60 seconds! Bonus", 5_000_000_000L),
			"Crop Fever start trigger rejects trailing text");
		check(!exactTriggerTimer.active(5_000_000_000L), "near-miss start leaves Crop Fever inactive");
		exactTriggerTimer.onChatMessage("WOAH! You caught a case of the CROP FEVER for 60 seconds!", 5_000_000_000L);
		equals(CropFeverTimerState.Event.NONE, exactTriggerTimer.onChatMessage(
			"GONE! Your CROP FEVER has been cured! Bonus", 6_000_000_000L),
			"Crop Fever cure trigger rejects trailing text");
		check(exactTriggerTimer.active(6_000_000_000L), "near-miss cure leaves Crop Fever active");

		CropFeverTimerState restartedTimer = new CropFeverTimerState();
		restartedTimer.onChatMessage("WOAH! You caught a case of the CROP FEVER for 60 seconds!", 5_000_000_000L);
		restartedTimer.onChatMessage("WOAH! You caught a case of the CROP FEVER for 60 seconds!", 7_000_000_000L);
		near(60.0, restartedTimer.remainingSeconds(7_000_000_000L), "a new Crop Fever start refreshes the full duration");
		near(59.0, restartedTimer.remainingSeconds(8_000_000_000L), "refreshed Crop Fever duration counts down monotonically");
		restartedTimer.reset();
		check(!restartedTimer.active(8_000_000_000L), "Crop Fever reset clears an active timer");
		near(0.0, restartedTimer.remainingSeconds(8_000_000_000L), "Crop Fever reset has no remaining time");

		CropFeverTimerState expiredTimer = new CropFeverTimerState();
		expiredTimer.onChatMessage("WOAH! You caught a case of the CROP FEVER for 60 seconds!", 5_000_000_000L);
		near(0.0, expiredTimer.remainingSeconds(65_000_000_000L), "Crop Fever expires at the exact 60-second deadline");
		equals(CropFeverTimerState.Event.NONE, expiredTimer.onChatMessage(
			"GONE! Your CROP FEVER has been cured!", 66_000_000_000L),
			"cure after expiration does not emit an active-timer end event");

		CropFeverTimerState wrappingTimer = new CropFeverTimerState();
		long nearNanoTimeWrap = Long.MAX_VALUE - 10_000_000_000L;
		wrappingTimer.onChatMessage("WOAH! You caught a case of the CROP FEVER for 60 seconds!", nearNanoTimeWrap);
		near(55.0, wrappingTimer.remainingSeconds(nearNanoTimeWrap + 5_000_000_000L),
			"Crop Fever deadline remains valid across System.nanoTime wraparound");
		equals(CropFeverTimerState.Event.ENDED, wrappingTimer.onChatMessage(
			"GONE! Your CROP FEVER has been cured!", nearNanoTimeWrap + 30_000_000_000L),
			"cure is recognized while System.nanoTime is on the wrapped side of the deadline");
	}

	private static void pestChatAndWidgetParsing() {
		check(!PestTrackerModule.INSTANCE.defaultEnabled(), "Pest Tracker defaults off for a fresh config");
		check(PestTrackerModule.INSTANCE.booleanSettings().stream()
			.filter(setting -> setting.name().equals("Pest Spawn Alert"))
			.noneMatch(setting -> setting.value()), "pest spawn alert defaults off when enabled by the user");
		check(PestTrackerModule.INSTANCE.booleanSettings().stream()
			.filter(setting -> setting.name().equals("Pest Vacuum"))
			.anyMatch(setting -> setting.value()), "Pest Vacuum HUD filter defaults on");
		PestSpawnChatParser.Spawn one = PestSpawnChatParser.parseSpawn(
			"GROSS! A Pest has appeared in Plot - East Plot!").orElseThrow();
		equals(1, one.knownAmount(), "single pest amount");
		equals(List.of("East Plot"), one.plotNames(), "single pest plot");
		PestSpawnChatParser.Spawn many = PestSpawnChatParser.parseSpawn(
			"YUCK! 4 Pests have spawned in Plot - West Plot!").orElseThrow();
		equals(4, many.knownAmount(), "multiple pest amount");
		PestSpawnChatParser.Spawn offline = PestSpawnChatParser.parseSpawn(
			"GROSS! While you were offline, Pests spawned in Plots East Plot, West Plot!").orElseThrow();
		equals(List.of("East Plot", "West Plot"), offline.plotNames(), "offline plot list");
		check(PestSpawnChatParser.parseCleanPlot("Plot - East Plot is now clean!").isPresent(), "clean plot parser");
		check(PestSpawnChatParser.isPestKill("You received 1 Pest Kill XP for killing a Beetle!"), "pest kill parser");
		PestCooldownText.Observation cooldown = PestCooldownText.parse(List.of(
			"Pests:", "3 Pests", "Plots: 14", "Cooldown: 1m 15s", "Area: Garden")).orElseThrow();
		equals(PestCooldownText.State.COUNTDOWN, cooldown.state(), "widget cooldown state");
		equals(75, cooldown.remainingSeconds(), "widget cooldown seconds");
		equals(PestCooldownText.State.READY, PestCooldownText.parse(List.of(
			"Pests:", "0 Pests", "Plots: none", "Cooldown: READY", "Area: Garden")).orElseThrow().state(), "widget ready state");
	}

	private static void cooldownReadyIsOnceAndWaitsForBothSources() {
		PestCooldownTimer timer = new PestCooldownTimer();
		long spawn = 1_000_000_000L;
		timer.onSpawn(spawn);
		timer.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.READY, 0), spawn);
		check(!timer.consumeReadyEvent(spawn, false, 0), "initial READY without a post-spawn countdown is ignored");
		timer.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.COUNTDOWN, 1), spawn);
		timer.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.READY, 0), spawn + 1_000_000_000L);
		check(!timer.consumeReadyEvent(spawn + 1_999_000_000L, true, 2), "custom cooldown waits for deadline");
		check(timer.consumeReadyEvent(spawn + 2_000_000_000L, true, 2), "READY plus custom deadline fires");
		check(!timer.consumeReadyEvent(spawn + 2_100_000_000L, true, 2), "ready fires once per cycle");
		timer.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.COUNTDOWN, 1), spawn + 2_200_000_000L);
		timer.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.READY, 0), spawn + 3_200_000_000L);
		check(!timer.consumeReadyEvent(spawn + 3_200_000_000L, false, 0),
			"another widget countdown cannot emit a second ready event for the same spawn");

		PestCooldownTimer warning = new PestCooldownTimer();
		warning.onSpawn(spawn);
		warning.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.COUNTDOWN, 10), spawn);
		check(!warning.consumeWarningEvent(spawn, false, 0, 5), "lead warning waits until the configured window");
		check(warning.consumeWarningEvent(spawn + 5_000_000_000L, false, 0, 5), "five-second lead warning fires");
		check(!warning.consumeWarningEvent(spawn + 5_100_000_000L, false, 0, 5), "lead warning fires once per cycle");
		PestCooldownTimer negativeNanoClock = new PestCooldownTimer();
		negativeNanoClock.observeWidget(new PestCooldownText.Observation(PestCooldownText.State.COUNTDOWN, 2), -8_000_000_000L);
		near(2.0, negativeNanoClock.remainingSeconds(-8_000_000_000L, false, 0),
			"countdown supports negative System.nanoTime values");
	}

	private static void spawnAverageAndToolFilter() {
		PestSpawnAverage average = new PestSpawnAverage();
		long limit = 10_000_000_000L;
		average.onSpawn(1_000_000_000L, limit, true);
		check(!average.onSpawn(13_000_000_000L, limit, true), "long AFK gap is excluded");
		average.onCropAction(20_000_000_000L);
		check(average.onSpawn(21_000_000_000L, limit, true), "recent crop activity accepts sample");
		near(8.0, average.averageSeconds(), "rolling average excludes AFK intervals");

		average.reset();
		long afkLimit = 30_000_000_000L;
		average.onSpawn(0L, afkLimit, true);
		for (long action = 5_000_000_000L; action <= 65_000_000_000L; action += 15_000_000_000L) {
			average.onCropAction(action);
		}
		check(average.onSpawn(75_000_000_000L, afkLimit, true),
			"normal pest interval longer than AFK limit counts when crop activity is continuous");
		near(75.0, average.averageSeconds(), "active pest interval remains in spawn average");
		average.onCropAction(80_000_000_000L);
		average.onCropAction(115_000_000_000L);
		check(!average.onSpawn(130_000_000_000L, afkLimit, true),
			"crop-action gap above the AFK limit excludes the interval");
		average.reset();
		average.onSpawn(1_000_000_000L, afkLimit, true);
		average.onCropAction(40_000_000_000L);
		check(!average.onSpawn(41_000_000_000L, afkLimit, true),
			"a first crop action after an AFK gap does not make the interval active retroactively");

		average.reset();
		average.onSpawn(1_000_000_000L, afkLimit, true);
		average.onCropAction(5_000_000_000L);
		check(!average.onSpawn(10_000_000_000L, afkLimit, true, false), "offline spawn is not an average sample");
		check(!average.onSpawn(20_000_000_000L, afkLimit, true),
			"first active spawn after an offline spawn only resets the average anchor");
		average.onCropAction(25_000_000_000L);
		check(average.onSpawn(30_000_000_000L, afkLimit, true), "subsequent active interval can contribute");

		check(PestToolFilter.shouldShow(true, true, false, false, "Euclid's Wheat Hoe", "Empty"), "farming tool filter");
		check(PestToolFilter.shouldShow(true, false, true, false, "Pest Vacuum", "Empty"), "vacuum filter");
		check(!PestToolFilter.shouldShow(true, true, false, false, "Pest Lasso", "Empty"), "unselected tool group is hidden");
		check(PestToolFilter.shouldShow(false, false, false, false, "Empty", "Empty"), "disabled filter leaves HUD visible");
	}

	private static void infestedPlotWarpSelection() {
		InfestedPlotTracker tracker = new InfestedPlotTracker();
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, List.of("East Plot"), false), 10L);
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(2, List.of("West Plot"), false), 20L);
		equals("West Plot", tracker.newestWarpTarget("East Plot").orElseThrow().name(), "alternate plot prefers newest other plot");
		tracker.observeCleanPlot("East Plot");
		tracker.observeCleanPlot("West Plot");
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, List.of("East Plot"), false), 30L);
		equals("East Plot", tracker.newestWarpTarget("East Plot").orElseThrow().name(), "single current infested plot remains a fallback");
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, List.of("North Plot"), false), 40L);
		check(tracker.contains("East Plot"), "ordinary same-session plot warps retain learned infested plots");
		tracker.onDisconnect();
		check(tracker.snapshot().isEmpty(), "disconnect clears every session-learned infested plot");
		check(tracker.latest().isEmpty(), "disconnect clears the latest infested plot pointer");
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, List.of("South Plot"), false), 50L);
		equals(List.of("South Plot"), tracker.snapshot().stream().map(InfestedPlotTracker.InfestedPlot::name).toList(),
			"a new connection cannot select plots learned in the previous session");
		equals("South Plot", GardenCurrentPlotParser.parse("Area: Garden\nPlot - South Plot\nGuests: 2").orElseThrow(), "current plot scoreboard parse");
	}

	private static void pestSessionResetPreservesLifetime() {
		PestHourlyStats stats = new PestHourlyStats();
		stats.restoreTotal(7, 70_000_000_000L);
		stats.tick(true, 1_000_000_000L);
		stats.tick(true, 3_000_000_000L);
		stats.recordKill(3_000_000_000L);
		stats.resetSession(3_000_000_000L);
		equals(0, stats.sessionKills(), "session reset clears session count");
		equals(8, stats.totalKills(), "session reset preserves total count");
		equals(72_000_000_000L, stats.totalGardenNanos(), "restored total elapsed time survives session reset");
	}

	private static void slayerTransitionsAndRates() {
		equals(SlayerScoreboardParser.State.BOSS_ACTIVE, SlayerScoreboardParser.parse("Slay the boss!"), "Slayer active objective");
		equals(SlayerScoreboardParser.State.BOSS_SLAIN, SlayerScoreboardParser.parse("Boss slain!"), "Slayer slain objective");
		equals(SlayerChatParser.Event.QUEST_STARTED, SlayerChatParser.parse("SLAYER QUEST STARTED!"), "Slayer quest start");
		SlayerTimerState timers = new SlayerTimerState();
		check(timers.onBossSpawn(10_000_000_000L), "Slayer first spawn transition");
		check(!timers.onBossSpawn(11_000_000_000L), "duplicate scoreboard spawn is ignored");
		equals(List.of("Boss spawned  00:10"), SlayerTimeDisplay.SPAWN.lines("00:10", "00:05"),
			"Slayer spawn-only timer display");
		equals(List.of("Average kill time  00:05"), SlayerTimeDisplay.KILL.lines("00:10", "00:05"),
			"Slayer kill-only timer display");
		equals(List.of("Boss spawned  00:10", "Average kill time  00:05"),
			SlayerTimeDisplay.BOTH.lines("00:10", "00:05"), "Slayer both timer display");
		equals(5_000_000_000L, timers.onBossKill(15_000_000_000L), "Slayer boss duration");
		SlayerKillTimeAverage average = new SlayerKillTimeAverage(2);
		check(!average.record(-1L) && average.sampleCount() == 0,
			"negative or untimed Slayer kills are excluded from the average");
		check(average.record(5_000_000_000L) && average.record(15_000_000_000L)
			&& average.averageNanos() == 10_000_000_000L,
			"Slayer average kill time uses all currently retained valid durations");
		average.record(25_000_000_000L);
		check(average.sampleCount() == 2 && average.averageNanos() == 20_000_000_000L,
			"Slayer average retains only the bounded latest sample window");
		average.reset();
		check(average.sampleCount() == 0 && average.averageNanos() == -1L,
			"world/session reset clears the Slayer average sample window");
		SlayerQuestKillTimeAverage currentQuest = new SlayerQuestKillTimeAverage(4);
		check(currentQuest.beginQuest() && !currentQuest.beginQuest(),
			"duplicate Slayer start signals keep one current-quest sample window");
		check(currentQuest.record(5_000_000_000L) && currentQuest.record(15_000_000_000L)
			&& currentQuest.averageNanos() == 10_000_000_000L,
			"the active Slayer quest averages its valid kill durations");
		currentQuest.completeQuest();
		check(!currentQuest.questActive() && currentQuest.averageNanos() == 10_000_000_000L,
			"a completed quest keeps its average visible during the HUD grace period");
		check(currentQuest.beginQuest() && currentQuest.sampleCount() == 0
			&& currentQuest.averageNanos() == -1L && currentQuest.record(9_000_000_000L)
			&& currentQuest.averageNanos() == 9_000_000_000L,
			"a new Slayer quest starts a clean average window");
		currentQuest.failQuest();
		check(!currentQuest.questActive() && currentQuest.sampleCount() == 0
			&& currentQuest.averageNanos() == -1L && !currentQuest.record(3_000_000_000L),
			"a failed or inactive quest clears and rejects kill-time samples");
		equals(SlayerMobClassifier.Kind.MINIBOSS, SlayerMobClassifier.classify("§cVoidling Devotee"), "miniboss classifier");
		equals(SlayerMobClassifier.Kind.BOSS, SlayerMobClassifier.classify("§cRiftstalker Bloodfiend"), "boss classifier wins over suffix");
		SlayerHourlyStats stats = new SlayerHourlyStats();
		stats.tick(true, 1_000_000_000L);
		stats.tick(true, 3_601_000_000_000L);
		stats.recordKill(3_601_000_000_000L);
		near(1.0, stats.sessionPerHour(), "Slayer session bosses/hour");
		stats.resetSession(3_601_000_000_000L);
		equals(1, stats.totalKills(), "Slayer session reset preserves total");
	}

	private static void macroGardenEventsRoundTrip() {
		MacroScript spawned = new MacroScript("spawn", MacroScript.Trigger.PEST_SPAWNED);
		MacroScript ready = new MacroScript("ready", MacroScript.Trigger.PEST_COOLDOWN_READY);
		List<MacroScript> decoded = MacroScriptConfigCodec.decode(MacroScriptConfigCodec.encode(List.of(spawned, ready)));
		equals(MacroScript.Trigger.PEST_SPAWNED, decoded.get(0).trigger(), "spawn event config round trip");
		equals(MacroScript.Trigger.PEST_COOLDOWN_READY, decoded.get(1).trigger(), "ready event config round trip");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void near(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 1.0e-9) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}
}
