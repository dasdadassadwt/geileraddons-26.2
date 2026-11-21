package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.helpers.DungeonBloodCampTrackerChecks;
import geiler.addons.client.dungeon.helpers.DungeonMaskProcTimers;
import geiler.addons.client.dungeon.helpers.DungeonSplitTracker;
import geiler.addons.client.farming.SlayerHourlyStats;
import geiler.addons.client.hud.TitleAlertStackChecks;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import java.util.List;

/** Focused offline checks for HUD row construction, countdowns, and feature state boundaries. */
public final class HudFeatureChecks {
	private HudFeatureChecks() { }

	public static void run() {
		checkRunSplitRows();
		DungeonBloodCampTrackerChecks.run();
		checkInvincibilityCountdowns();
		TitleAlertStackChecks.run();
		checkSlayerVisibilityAndSessionState();
	}

	private static void checkRunSplitRows() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean oldSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		boolean oldTickTime = DungeonRunSplitsModule.showTickTime.value();
		boolean oldRealTime = DungeonRunSplitsModule.showRealTime.value();
		boolean oldPbPace = DungeonRunSplitsModule.showPbPace.value();
		boolean oldCountValue = DungeonRunSplitsModule.showCountValue.value();
		String oldCountSource = DungeonRunSplitsModule.countSource.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.showCurrentSplit.setValue(true);
			DungeonRunSplitsModule.secondTimeDisplay.setValue("LOCAL_PB");
			DungeonRunSplitsModule.showTickTime.setValue(false);
			DungeonRunSplitsModule.showRealTime.setValue(false);
			DungeonRunSplitsModule.showPbPace.setValue(true);
			DungeonRunSplitsModule.showCountValue.setValue(true);
			DungeonRunSplitsModule.countSource.setValue("SERVER_PING_PACKETS");
			require(module.groups().stream().flatMap(group -> group.settings().stream()).noneMatch(setting ->
				setting == DungeonRunSplitsModule.showTickTime || setting == DungeonRunSplitsModule.showRealTime
					|| setting == DungeonRunSplitsModule.showPbPace || setting == DungeonRunSplitsModule.showCountValue
					|| setting == DungeonRunSplitsModule.countSource),
				"legacy completion-summary controls are hidden from split settings");
			List<String> selectorValues = List.of("NONE", "CLIENT_TICK_ESTIMATE", "LOCAL_PB", "TIME_LOST");
			List<String> selectorLabels = List.of("Off", "Srv Ticks", "Local PB", "Time Lost");
			require(DungeonRunSplitsModule.secondTimeDisplay.choices().equals(selectorValues),
				"the second-column selector keeps its saved values and adds the tick estimate");
			for (int i = 0; i < selectorValues.size(); i++) {
				DungeonRunSplitsModule.secondTimeDisplay.setValue(selectorValues.get(i));
				String label = DungeonRunSplitsModule.secondTimeDisplay.displayValue();
				require(label.equals(selectorLabels.get(i)) && label.length() <= 9,
					"selector label " + selectorValues.get(i) + " stays concise enough for its field");
			}
			DungeonRunSplitsModule.secondTimeDisplay.setValue("LOCAL_PB");
			var preview = module.buildRows(true);
			require(preview.size() == 13 && preview.get(0).label().equals("Blood")
				&& preview.get(preview.size() - 1).label().equals("Run"),
				"the Run Splits preview keeps the complete ordered sample");
			require(preview.get(0).primaryValue().equals("13.08s")
				&& preview.get(0).secondaryValue().equals("[12.55s]"),
				"the preview always shows real time first and the local PB second");
			require(preview.get(0).labelColor() == 0xFFFF5555
				&& preview.get(0).primaryColor() == 0xFF55FF55
				&& preview.get(0).secondaryColor() == 0xFFAAAAAA,
				"the preview keeps split, real-time, and PB colors");

			module.publishSplitRows(DungeonFloor.F7, List.of(
				new DungeonSplitTracker.Split("BLOOD", "Blood", 1_000_000_000L, 200, 0, 20, true),
				new DungeonSplitTracker.Split("CAMP", "Camp", 1_211_000_000L, 0, 0, 24, true),
				new DungeonSplitTracker.Split("PORTAL", "Portal", 1_272_000_000L, 1, 0, 24, true),
				new DungeonSplitTracker.Split("MAXOR", "Maxor", 2_012_000_000L, 40, 0, 40, true),
				new DungeonSplitTracker.Split("STORM", "Storm", 2_061_000_000L, 40, 0, 40, true)));

			DungeonRunSplitsModule.secondTimeDisplay.setValue("CLIENT_TICK_ESTIMATE");
			var live = module.buildRows(false);
			var blood = live.stream().filter(row -> row.label().equals("Blood")).findFirst().orElseThrow();
			require(blood.primaryValue().equals("1.00s") && blood.secondaryValue().equals("1.00s"),
				"the primary remains real time while 20 server ticks estimate one second despite 200 client ticks");
			var portalEstimate = live.stream().filter(row -> row.label().equals("Portal")).findFirst().orElseThrow();
			require(portalEstimate.primaryValue().equals("1.27s")
				&& portalEstimate.secondaryValue().equals("1.20s")
				&& portalEstimate.secondaryColor() == 0xFFE5E7EB,
				"the server-tick estimate is displayed as 50 ms multiples and remains secondary to real time");
			var lostExample = new DungeonSplitTracker.Split("RUN", "Run", 1_200_000_000L,
				0L, 0L, 20L, true);
			require(DungeonRunSplitsModule.serverTickEstimateNanos(20L) == 1_000_000_000L
				&& DungeonRunSplitsModule.formatTimeLost(lostExample).equals("+0.20s")
				&& DungeonRunSplitsModule.timeLostNanos(1_249_000_000L, 20L) == 200_000_000L
				&& DungeonRunSplitsModule.timeLostNanos(900_000_000L, 20L) == 0L,
				"1.20 seconds with 20 server ticks shows +0.20s, floors partial ticks, and clamps negative loss");
			require(live.stream().noneMatch(row -> row.primaryValue().contains("client tick")
				|| row.primaryValue().contains("ping") || row.primaryValue().contains("PB pace")),
				"the real-time column contains no raw ticks, ping counts, or PB-pace details");

			DungeonRunSplitsModule.secondTimeDisplay.setValue("TIME_LOST");
			live = module.buildRows(false);
			var subTickLoss = live.stream().filter(row -> row.label().equals("Camp")).findFirst().orElseThrow();
			var wholeTickLoss = live.stream().filter(row -> row.label().equals("Portal")).findFirst().orElseThrow();
			require(subTickLoss.secondaryValue().equals("+0.00s")
				&& wholeTickLoss.secondaryValue().equals("+0.05s")
				&& wholeTickLoss.primaryValue().equals("1.27s")
				&& subTickLoss.secondaryColor() == 0xFFFF5555,
				"time lost is floored to full 50 ms ticks and remains red");

			DungeonRunSplitsModule.secondTimeDisplay.setValue("NONE");
			live = module.buildRows(false);
			require(live.stream().allMatch(row -> row.secondaryValue().isEmpty())
				&& live.stream().filter(row -> row.label().equals("Blood")).findFirst().orElseThrow()
					.primaryValue().equals("1.00s"),
				"Off hides only the secondary value and never hides real time");
		} finally {
			module.clearSnapshots();
			DungeonRunSplitsModule.showSplits.setValue(oldSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
			DungeonRunSplitsModule.showTickTime.setValue(oldTickTime);
			DungeonRunSplitsModule.showRealTime.setValue(oldRealTime);
			DungeonRunSplitsModule.showPbPace.setValue(oldPbPace);
			DungeonRunSplitsModule.showCountValue.setValue(oldCountValue);
			DungeonRunSplitsModule.countSource.setValue(oldCountSource);
		}
	}

	private static void checkInvincibilityCountdowns() {
		DungeonMaskProcTimers timers = new DungeonMaskProcTimers();
		require(timers.observeChat("Second Wind Activated! Your Spirit Mask saved your life!", false, null) == null
			&& timers.reading(DungeonMaskProcTimers.Proc.SPIRIT).activeTicks() == 0,
			"invincibility timers ignore proc-like chat outside a dungeon");
		require(timers.observeChat("Second Wind Activated! Your Spirit Mask saved your life!", true, null)
			== DungeonMaskProcTimers.Proc.SPIRIT,
			"Spirit Mask proc starts from its stable server chat signal");
		var spirit = timers.reading(DungeonMaskProcTimers.Proc.SPIRIT);
		require(spirit.activeTicks() == 60 && spirit.cooldownTicks() == 600
			&& DungeonInvincibilityTimerModule.timerText(spirit, true).equals("3.0s active"),
			"Spirit Mask has the expected active and cooldown durations and HUD text");
		timers.tick(false);
		require(timers.reading(DungeonMaskProcTimers.Proc.SPIRIT).activeTicks() == 60,
			"proc countdowns pause when the client is outside the dungeon");
		timers.tick(true);
		for (int tick = 0; tick < 59; tick++) timers.tick(true);
		var cooldown = timers.reading(DungeonMaskProcTimers.Proc.SPIRIT);
		require(cooldown.activeTicks() == 0 && cooldown.cooldownTicks() == 540
			&& DungeonInvincibilityTimerModule.timerText(cooldown, true).equals("27.0s cooldown")
			&& DungeonInvincibilityTimerModule.timerText(cooldown, false) == null,
			"active time transitions to a configurable cooldown display");
		require(timers.observeChat("Your Bonzo's Mask saved your life!", true, 12)
			== DungeonMaskProcTimers.Proc.BONZO
			&& timers.reading(DungeonMaskProcTimers.Proc.BONZO).cooldownTicks() == 240,
			"an observed Bonzo lore override controls its cooldown length");
		require(timers.observeChat("Your Phoenix Pet saved you from certain death!", true, null)
			== DungeonMaskProcTimers.Proc.PHOENIX
			&& timers.reading(DungeonMaskProcTimers.Proc.PHOENIX).activeTicks() == 80,
			"Phoenix proc uses its observed source signal and configured active duration");
	}

	private static void checkSlayerVisibilityAndSessionState() {
		checkSlayerModuleDefaults();
		long lastActive = 1_000_000_000L;
		require(SlayerTrackerModule.shouldDisplayForQuestState(true, 0L, 0L),
			"an active Slayer quest keeps the tracker visible");
		require(SlayerTrackerModule.shouldDisplayForContext(true, false, true, 0L, 0L)
			&& !SlayerTrackerModule.shouldDisplayForContext(true, true, true, 0L, 0L)
			&& !SlayerTrackerModule.shouldDisplayForContext(false, false, true, 0L, 0L),
			"Slayer HUD visibility requires an active feature and hides during dungeons");
		require(SlayerTrackerModule.shouldDisplayForQuestState(false, lastActive,
			lastActive + 29_999_999_999L)
			&& !SlayerTrackerModule.shouldDisplayForQuestState(false, lastActive, lastActive + 30_000_000_000L)
			&& !SlayerTrackerModule.shouldDisplayForQuestState(false, 0L, lastActive),
			"the Slayer HUD hides exactly 30 seconds after an idle quest and stays hidden without session activity");
		SlayerHourlyStats stats = new SlayerHourlyStats();
		stats.tick(true, 1_000_000_000L);
		stats.tick(true, 2_000_000_000L);
		stats.recordKill(3_000_000_000L);
		stats.tick(false, 4_000_000_000L);
		require(stats.sessionKills() == 1 && stats.totalKills() == 1
			&& stats.sessionActiveNanos() == 2_000_000_000L
			&& stats.totalActiveNanos() == 2_000_000_000L,
			"ending an individual Slayer quest preserves session and lifetime counts and elapsed time");
	}

	private static void checkSlayerModuleDefaults() {
		SlayerTrackerModule module = SlayerTrackerModule.INSTANCE;
		require(module.name().equals("Slayer Tracker") && module.displayName().equals("Slayer Tracker")
			&& module.configName().equals("Slayer Tracker")
			&& module.description().equals("Counts Slayer bosses per hour.")
			&& module.id().equals("slayer_tracker") && module.category() == Category.SLAYER
			&& !module.defaultEnabled(),
			"Slayer Tracker keeps its registered identity, category and default-off state and is no longer marked work in progress");

		require(module.booleanSettings().stream().map(BooleanSetting::name).toList()
			.equals(List.of("Show Totals", "Background")),
			"Slayer Tracker keeps only its stats and display toggles; alerts moved to Slayer Alerts");
		require(module.booleanSettings().stream().allMatch(setting -> setting.name().equals("Background") == setting.rawValue()),
			"Slayer Tracker keeps its toggle defaults");
		require(module.choiceSettings().isEmpty() && module.numberSettings().isEmpty() && module.textSettings().isEmpty(),
			"Slayer Tracker has no time display, title or alert text settings any more");
		require(module.actions().size() == 1
			&& module.actions().getFirst().name().equals("Reset Session Stats"),
			"Slayer Tracker keeps the session-reset action");

		List<String> groupNames = module.groups().stream().map(SettingGroup::name).toList();
		require(groupNames.equals(List.of("Stats", "Display")), "Slayer Tracker keeps its two settings sections");
		require(groupSettings(module, "Stats").equals(List.of("Show Totals", "Reset Session Stats"))
			&& groupSettings(module, "Display").equals(List.of("Background")),
			"Slayer Tracker keeps each setting in the same visible section and order");
	}

	private static List<String> groupSettings(SlayerTrackerModule module, String groupName) {
		return module.groups().stream()
			.filter(group -> groupName.equals(group.name()))
			.findFirst().orElseThrow()
			.settings().stream()
			.map(Setting::name)
			.toList();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
