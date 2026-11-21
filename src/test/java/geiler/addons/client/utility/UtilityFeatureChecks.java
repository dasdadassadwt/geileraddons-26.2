package geiler.addons.client.utility;

import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.impl.PerformanceHudModule;
import geiler.addons.client.module.impl.SystemClockHudModule;
import geiler.addons.client.performance.PerformanceHudRules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.time.LocalTime;

/** Dependency-free acceptance checks for utility modules, clock HUD, supply, and lobby-watch rules. */
public final class UtilityFeatureChecks {
	private UtilityFeatureChecks() { }

	public static void main(String[] args) {
		checkDurations();
		checkTimerState();
		checkClockFormatting();
		checkClockHudModuleContract();
		checkClockHudVisibilityLifecycle();
		checkPerformanceHud();
		checkTimerCompletionPresentation();
		checkSupplyPlanning();
		checkLobbyWatch();
		checkLobbyStoreJsonShape();
		checkLobbyStorePersistence();
	}

	private static void checkClockFormatting() {
		LocalTime midnight = LocalTime.of(0, 5, 9);
		LocalTime noon = LocalTime.of(12, 5, 9);
		LocalTime afternoon = LocalTime.of(13, 5, 9);
		equalsString("12-hour", SystemClockFormat.TWELVE_HOUR,
			"12-hour choice alias remains stable for saved settings");
		equalsString("24-hour", SystemClockFormat.TWENTY_FOUR_HOUR,
			"24-hour choice alias remains stable for saved settings");
		check(SystemClockFormat.DEFAULT_SHOW_SECONDS, "clock shows seconds by default");
		check(!SystemClockFormat.DEFAULT_SHOW_BACKGROUND, "clock background is hidden by default");
		equalsLong(0xFFFFFFFFL, Integer.toUnsignedLong(SystemClockFormat.DEFAULT_TEXT_ARGB),
			"clock text remains opaque white by default");
		equalsLong(0x80000000L, Integer.toUnsignedLong(SystemClockFormat.DEFAULT_BACKGROUND_ARGB),
			"optional clock background defaults to translucent black");
		equalsString("00:05:09", SystemClockFormat.format(midnight,
			SystemClockFormat.TWENTY_FOUR_HOUR, true), "default clock format retains 24-hour seconds");
		equalsString("00:05", SystemClockFormat.format(midnight,
			SystemClockFormat.TWENTY_FOUR_HOUR, false), "24-hour format can hide seconds");
		equalsString("13:05:09", SystemClockFormat.format(afternoon,
			SystemClockFormat.TWENTY_FOUR_HOUR, true), "24-hour afternoon format");
		equalsString("12:05 AM", SystemClockFormat.format(midnight,
			SystemClockFormat.TWELVE_HOUR, false), "12-hour midnight format");
		equalsString("12:05 PM", SystemClockFormat.format(noon,
			SystemClockFormat.TWELVE_HOUR, false), "12-hour noon format without seconds");
		equalsString("12:05:09 PM", SystemClockFormat.format(noon,
			SystemClockFormat.TWELVE_HOUR, true), "12-hour noon format with seconds");
		equalsString("1:05:09 PM", SystemClockFormat.format(afternoon,
			SystemClockFormat.TWELVE_HOUR, true), "12-hour afternoon format with seconds");
		equalsString("13:05:09", SystemClockFormat.format(afternoon, null, true),
			"null format continues to fall back to 24-hour time");
		equalsString("13:05:09", SystemClockFormat.format(afternoon, "unknown", true),
			"unknown format continues to fall back to 24-hour time");
		boolean rejectedNullTime = false;
		try {
			SystemClockFormat.format(null, SystemClockFormat.TWENTY_FOUR_HOUR, true);
		} catch (IllegalArgumentException expected) {
			rejectedNullTime = true;
		}
		check(rejectedNullTime, "null clock time retains the required-time argument error");
	}

	private static void checkClockHudModuleContract() {
		SystemClockHudModule module = SystemClockHudModule.INSTANCE;
		check(module.name().equals("System Clock") && module.configName().equals("System Clock")
			&& module.displayName().equals("System Clock") && module.id().equals("system_clock")
			&& module.description().equals("Shows the current local system time.")
			&& module.category() == Category.MISCELLANEOUS && !module.defaultEnabled(),
			"System Clock keeps its persisted identity, category, and disabled fresh-install default");
		check(module.groups().size() == 1 && module.groups().getFirst().name() == null
			&& module.groups().getFirst().settings().stream().map(Setting::name).toList().equals(List.of(
				"Time Format", "Show Seconds", "Text Color", "Show Background", "Background Color"))
			&& module.actions().isEmpty(),
			"System Clock keeps the original five settings in order without adding a settings section or action");

		check(module.choiceSettings().size() == 1, "System Clock keeps one time-format choice");
		ChoiceSetting format = module.choiceSettings().getFirst();
		check(format.name().equals("Time Format")
			&& format.value().equals(SystemClockFormat.TWENTY_FOUR_HOUR)
			&& format.choices().equals(List.of(SystemClockFormat.TWENTY_FOUR_HOUR,
				SystemClockFormat.TWELVE_HOUR)),
			"System Clock keeps its 24-hour default and saved format choices");
		check(module.booleanSettings().stream().map(BooleanSetting::name).toList().equals(
			List.of("Show Seconds", "Show Background"))
			&& module.booleanSettings().get(0).rawValue() == SystemClockFormat.DEFAULT_SHOW_SECONDS
			&& module.booleanSettings().get(1).rawValue() == SystemClockFormat.DEFAULT_SHOW_BACKGROUND,
			"System Clock keeps its seconds and background setting names and defaults");
		check(module.colorSettings().stream().map(ColorSetting::name).toList().equals(
			List.of("Text Color", "Background Color"))
			&& Integer.toUnsignedLong(module.colorSettings().get(0).argb())
				== Integer.toUnsignedLong(SystemClockFormat.DEFAULT_TEXT_ARGB)
			&& Integer.toUnsignedLong(module.colorSettings().get(1).argb())
				== Integer.toUnsignedLong(SystemClockFormat.DEFAULT_BACKGROUND_ARGB),
			"System Clock keeps its opaque-white text and translucent-black background defaults");
		check(module.previewMode().equals("live") && module.renderOnHud(),
			"System Clock remains a live-preview HUD element");
	}

	private static void checkClockHudVisibilityLifecycle() {
		SystemClockHudModule module = SystemClockHudModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(false);
			check(!module.visible(), "the System Clock HUD stays hidden while its module is disabled");
			module.setEnabled(true);
			check(module.visible(), "enabling System Clock makes its live clock visible on the HUD");
			module.setEnabled(false);
			check(!module.visible(), "disabling System Clock immediately hides its live clock again");
		} finally {
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkPerformanceHud() {
		check(PerformanceHudRules.lines(true, true, true, 42, 19.84, 144).equals(
			List.of("Ping: 42ms", "TPS: 19.8", "FPS: 144")), "performance HUD formats all three stats");
		check(PerformanceHudRules.lines(true, true, true, null, null, 60).equals(
			List.of("Ping: --", "TPS: --", "FPS: 60")), "performance HUD shows -- for unavailable ping and TPS");
		check(PerformanceHudRules.lines(false, true, false, 5, 20.0, 60).equals(List.of("TPS: 20.0")),
			"performance HUD lists only enabled stats");
		check(PerformanceHudRules.lines(false, false, false, 5, 20.0, 60).isEmpty(),
			"performance HUD with every stat off has no lines");
		check(PerformanceHudRules.lines(true, false, false, -3, null, 0).equals(List.of("Ping: 0ms")),
			"performance HUD clamps negative ping to zero");

		PerformanceHudModule module = PerformanceHudModule.INSTANCE;
		check(module.name().equals("Performance HUD") && module.id().equals("performance_hud")
			&& module.category() == Category.MISCELLANEOUS && !module.defaultEnabled(),
			"Performance HUD keeps its identity, category, and disabled fresh-install default");
		check(module.groups().getFirst().settings().stream().map(Setting::name).toList().equals(List.of(
			"Show Ping", "Show TPS", "Show FPS", "Style", "Text Color")),
			"Performance HUD keeps its settings in order");
		check(module.booleanSettings().stream().allMatch(BooleanSetting::rawValue),
			"Performance HUD shows every stat by default");
		check(module.choiceSettings().getFirst().value().equals("Click GUI")
			&& module.choiceSettings().getFirst().choices().equals(List.of("Click GUI", "Text")),
			"Performance HUD defaults to the Click GUI style with a Text alternative");
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(false);
			check(!module.visible(), "the Performance HUD stays hidden while its module is disabled");
			module.setEnabled(true);
			check(module.visible(), "enabling Performance HUD makes it visible on the HUD");
		} finally {
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkTimerCompletionPresentation() {
		TimerCompletionPresentation defaults = TimerCompletionPresentation.defaults();
		check(defaults.showChat(), "chat completion output remains on by default");
		check(!defaults.showTitle(), "title completion output defaults off to preserve prior behavior");
		check(defaults.playSound(), "sound completion output remains on by default");
		equalsString("Countdown complete.", defaults.message(), "default completion text is preserved");
		equalsLong(40, defaults.titleDurationTicks(), "default title duration is two seconds");

		TimerCompletionPresentation custom = TimerCompletionPresentation.resolve(true, false, true, false,
			"  Finished!  ", 95);
		check(!custom.showChat() && custom.showTitle() && !custom.playSound(),
			"completion outputs can be independently selected");
		equalsString("Finished!", custom.message(), "completion text is trimmed");
		equalsLong(95, custom.titleDurationTicks(), "configured title duration is retained");
		TimerCompletionPresentation disabled = TimerCompletionPresentation.resolve(false, true, true, true,
			"", 0);
		check(!disabled.showChat() && !disabled.showTitle() && !disabled.playSound(),
			"master completion alert disables every output");
		equalsString("Countdown complete.", disabled.message(), "empty completion text falls back safely");
		equalsLong(TimerCompletionPresentation.MIN_TITLE_DURATION_TICKS, disabled.titleDurationTicks(),
			"title duration is clamped to a positive tick count");
	}

	private static void checkDurations() {
		equalsLong(5_410, parsed("1h30m10s"), "compact h/m/s duration");
		equalsLong(5_410, parsed("1h 30m 10s"), "spaced h/m/s duration");
		equalsLong(90, parsed("90s"), "seconds-only duration");
		equalsLong(0, parsed("0s"), "zero duration");
		check(TimerDurationParser.parseSeconds("1h1h").isEmpty(), "duplicate units are rejected");
		check(TimerDurationParser.parseSeconds("1m junk").isEmpty(), "trailing text is rejected");
		check(TimerDurationParser.parseSeconds("1d").isEmpty(), "unsupported units are rejected");
		equalsString("0s", TimerDurationParser.formatSeconds(0), "zero format");
		equalsString("1m 5s", TimerDurationParser.formatSeconds(65), "normalized minute format");
		equalsString("1h 1m 1s", TimerDurationParser.formatSeconds(3_661), "normalized hour format");
	}

	private static void checkTimerState() {
		CountdownTimerState timer = new CountdownTimerState();
		long start = 1_000_000_000L;
		timer.setSeconds(10, start);
		equalsLong(7_000_000_000L, timer.remainingNanos(start + 3_000_000_000L), "real elapsed time");
		check(timer.pause(start + 3_000_000_000L), "running timer pauses");
		check(!timer.isRunning(), "paused state is retained");
		check(timer.resume(start + 9_000_000_000L), "paused timer resumes");
		equalsLong(5, timer.remainingSeconds(start + 11_000_000_000L), "resume preserves remaining duration");
		check(timer.tick(start + 16_000_000_000L), "completion is reported at zero");
		check(!timer.tick(start + 17_000_000_000L), "completion is reported once");
		check(timer.reset(), "configured timer resets");
		check(!timer.isRunning(), "reset leaves timer paused");
		equalsLong(10, timer.remainingSeconds(start + 17_000_000_000L), "reset restores original duration");
	}

	private static void checkSupplyPlanning() {
		List<CarriedSupplyPlanner.StackIdentity> stacks = List.of(
			new CarriedSupplyPlanner.StackIdentity("ENDER_PEARL", 8),
			new CarriedSupplyPlanner.StackIdentity("ENDER_PEARL", 20),
			new CarriedSupplyPlanner.StackIdentity("SUPERBOOM_TNT", 3),
			new CarriedSupplyPlanner.StackIdentity("SUPERBOOM_TNT", 2),
			new CarriedSupplyPlanner.StackIdentity("", 64));
		equalsLong(8, CarriedSupplyPlanner.count(stacks, CarriedSupplyPlanner.Supply.ENDER_PEARL),
			"first pearl stack with the exact SkyBlock ID is used");
		equalsLong(3, CarriedSupplyPlanner.count(stacks, CarriedSupplyPlanner.Supply.SUPERBOOM_TNT),
			"first Superboom stack with the exact SkyBlock ID is used");
		equalsLong(0, CarriedSupplyPlanner.count(List.of(
			new CarriedSupplyPlanner.StackIdentity("OTHER_ITEM", 20)), CarriedSupplyPlanner.Supply.ENDER_PEARL),
			"no matching stack means zero carried items");
		equalsString("gfs ender_pearl 16", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.ENDER_PEARL, 0).orElse(""), "no pearl stack requests the default target");
		equalsString("gfs superboom_tnt 64", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 0).orElse(""), "no Superboom stack requests the default target");
		check(!CarriedSupplyPlanner.matches(new CarriedSupplyPlanner.StackIdentity(
			"OTHER_ITEM", 1), CarriedSupplyPlanner.Supply.SUPERBOOM_TNT),
			"a different SkyBlock ID is rejected");
		check(!CarriedSupplyPlanner.matches(new CarriedSupplyPlanner.StackIdentity(
			"", 1), CarriedSupplyPlanner.Supply.SUPERBOOM_TNT),
			"missing item IDs and display-name lookalikes are rejected");
		equalsLong(1, CarriedSupplyPlanner.deficit(CarriedSupplyPlanner.Supply.ENDER_PEARL, 15),
			"pearl withdrawal fills only to the 16-item target");
		equalsLong(32, CarriedSupplyPlanner.deficit(CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 32),
			"Superboom withdrawal fills only to the 64-item target");
		equalsString("gfs ender_pearl 1", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.ENDER_PEARL, 15).orElse(""), "pearl withdrawal command");
		equalsString("gfs superboom_tnt 59", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 5).orElse(""), "Superboom withdrawal command");
		equalsString("gfs superboom_tnt 32", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 32).orElse(""), "Superboom deficit command");
		equalsString("gfs ender_pearl 8", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.ENDER_PEARL,
			CarriedSupplyPlanner.count(stacks, CarriedSupplyPlanner.Supply.ENDER_PEARL)).orElse(""),
			"pearl refill uses the first matching stack and ignores later stacks");
		equalsString("gfs superboom_tnt 61", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.SUPERBOOM_TNT,
			CarriedSupplyPlanner.count(stacks, CarriedSupplyPlanner.Supply.SUPERBOOM_TNT)).orElse(""),
			"Superboom refill uses the first matching stack and ignores later stacks");
		equalsString("gfs ender_pearl 5", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.ENDER_PEARL, 3, 8).orElse(""), "custom pearl target command");
		equalsString("gfs superboom_tnt 7", CarriedSupplyPlanner.withdrawalCommand(
			CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 3, 10).orElse(""), "custom Superboom target command");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.ENDER_PEARL, 16).isEmpty(),
			"no pearl withdrawal is sent at target count");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.ENDER_PEARL, 64).isEmpty(),
			"no pearl withdrawal is sent above target count");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.ENDER_PEARL, 100).isEmpty(),
			"no pearl withdrawal is sent above target count");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 64).isEmpty(),
			"no Superboom withdrawal is sent at target count");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.ENDER_PEARL, 12, 8).isEmpty(),
			"no pearl withdrawal is sent above a custom target");
		check(CarriedSupplyPlanner.withdrawalCommand(CarriedSupplyPlanner.Supply.SUPERBOOM_TNT, 5, 0).isEmpty(),
			"non-positive custom targets are rejected");
	}

	private static void checkLobbyWatch() {
		LobbyTargetWatchState watch = new LobbyTargetWatchState();
		List<String> targets = LobbyTargetWatchState.parseTargets("Alice, alice\nBob\nnot a name");
		equalsLong(2, targets.size(), "target parsing trims, validates, and deduplicates");
		equalsLong(0, watch.update(targets, List.of("Alison" )).size(), "substring is not a match");
		equalsString("Alice", first(watch.update(targets, List.of("ALICE"))), "case-insensitive confirmed-profile match");
		equalsLong(0, watch.update(targets, List.of("alice")).size(), "continuous presence is not repeated");
		equalsLong(0, watch.update(targets, List.of()).size(), "absence clears presence");
		equalsString("Alice", first(watch.update(targets, List.of("Alice"))), "re-entry triggers once again");
		watch.reset();
		equalsLong(0, watch.presentTargets().size(), "world reset clears observed targets");

		var listedAlice = new LobbyTargetPresence.Candidate(
			java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "ALICE",
			true, true, false, true, false);
		var unlistedLoadedAlice = new LobbyTargetPresence.Candidate(
			java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alice",
			false, false, true, true, false);
		var unlistedRosterAlice = new LobbyTargetPresence.Candidate(
			java.util.UUID.fromString("00000000-0000-0000-0000-000000000003"), "Alice",
			true, false, false, true, false);
		var localAlice = new LobbyTargetPresence.Candidate(
			java.util.UUID.fromString("00000000-0000-0000-0000-000000000004"), "Alice",
			true, true, true, true, true);
		var unverifiedNpc = new LobbyTargetPresence.Candidate(
			java.util.UUID.fromString("00000000-0000-0000-0000-000000000005"), "Alice",
			false, false, true, false, false);
		equalsLong(1, LobbyTargetPresence.confirmedNames(List.of(listedAlice)).size(),
			"a listed player with a verified profile is confirmed");
		equalsLong(1, LobbyTargetPresence.confirmedNames(List.of(unlistedLoadedAlice)).size(),
			"an unlisted loaded player is confirmed when its UUID resolves to connection PlayerInfo");
		equalsLong(1, LobbyTargetPresence.confirmedNames(List.of(unlistedRosterAlice)).size(),
			"the full online player-info map includes players whose tab-list flag is off");
		equalsLong(0, LobbyTargetPresence.confirmedNames(List.of(localAlice)).size(),
			"the local player never matches a watched target");
		equalsLong(0, LobbyTargetPresence.confirmedNames(List.of(unverifiedNpc)).size(),
			"a loaded NPC-like player without a verified connection profile is ignored");
		watch.reset();
		equalsString("Alice", first(watch.update(targets,
			LobbyTargetPresence.confirmedNames(List.of(unlistedLoadedAlice)))),
			"unlisted but verified loaded players trigger the same case-insensitive watch");
		equalsLong(0, watch.update(targets, LobbyTargetPresence.confirmedNames(
			List.of(unlistedRosterAlice, unlistedLoadedAlice))).size(),
			"changing tab-list state while connection/entity presence remains does not retrigger");
	}

	private static void checkLobbyStoreJsonShape() {
		String savedTargets = """
			{"legacyTextImported":true,"targets":[
			 {"playerName":"Alice","enabled":true,"title":"Welcome"},
			 {"playerName":"Bob","enabled":false,"title":""}]}
			""";
		LobbySniperStore.Data data = ClientJsonFile.GSON.fromJson(savedTargets, LobbySniperStore.Data.class);
		check(data != null, "saved Lobby Sniper JSON loads into its persisted DTO");
		check(Boolean.TRUE.equals(data.legacyTextImported), "saved legacy import flag is retained");
		check(data.targets != null, "saved target array is retained");
		equalsLong(2, data.targets.size(), "saved target count is retained");
		LobbySniperStore.EntryData alice = data.targets.get(0);
		LobbySniperStore.EntryData bob = data.targets.get(1);
		equalsString("Alice", alice.playerName, "saved playerName field is retained");
		check(Boolean.TRUE.equals(alice.enabled), "saved enabled=true field is retained");
		equalsString("Welcome", alice.title, "saved custom title field is retained");
		equalsString("Bob", bob.playerName, "second saved playerName field is retained");
		check(Boolean.FALSE.equals(bob.enabled), "saved enabled=false field is retained");
		equalsString("", bob.title, "empty custom title is retained");

		String encoded = ClientJsonFile.GSON.toJson(data);
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		check(root.keySet().equals(java.util.Set.of("legacyTextImported", "targets")),
			"persisted JSON retains the legacyTextImported and targets keys");
		JsonObject encodedAlice = root.getAsJsonArray("targets").get(0).getAsJsonObject();
		check(encodedAlice.keySet().equals(java.util.Set.of("playerName", "enabled", "title")),
			"persisted entries retain the playerName, enabled, and title keys");
		LobbySniperStore.Data roundTripped = ClientJsonFile.GSON.fromJson(encoded, LobbySniperStore.Data.class);
		check(Boolean.TRUE.equals(roundTripped.legacyTextImported), "legacy import flag survives JSON round-trip");
		equalsLong(2, roundTripped.targets.size(), "saved target count survives JSON round-trip");
		equalsString("Alice", roundTripped.targets.get(0).playerName, "playerName survives JSON round-trip");
		check(Boolean.TRUE.equals(roundTripped.targets.get(0).enabled), "enabled survives JSON round-trip");
		equalsString("Welcome", roundTripped.targets.get(0).title, "title survives JSON round-trip");
		check(Boolean.FALSE.equals(roundTripped.targets.get(1).enabled), "false enabled value survives JSON round-trip");
		equalsString("", roundTripped.targets.get(1).title, "empty title survives JSON round-trip");

		String olderSchema = "{\"targets\":[{\"playerName\":\"OldPlayer\"}]}";
		LobbySniperStore.Data defaults = ClientJsonFile.GSON.fromJson(olderSchema, LobbySniperStore.Data.class);
		check(defaults.legacyTextImported == null, "missing legacy import flag keeps its nullable default");
		check(defaults.targets != null, "older target schema loads its target array");
		equalsString("OldPlayer", defaults.targets.get(0).playerName, "older target playerName loads");
		check(defaults.targets.get(0).enabled == null, "missing enabled keeps its nullable default");
		check(defaults.targets.get(0).title == null, "missing title keeps its nullable default");
		String olderRoundTripJson = ClientJsonFile.GSON.toJson(defaults);
		JsonObject olderRoot = JsonParser.parseString(olderRoundTripJson).getAsJsonObject();
		check(olderRoot.keySet().equals(java.util.Set.of("targets")),
			"older JSON round-trip does not invent a legacy import flag");
		JsonObject olderEntry = olderRoot.getAsJsonArray("targets").get(0).getAsJsonObject();
		check(olderEntry.keySet().equals(java.util.Set.of("playerName")),
			"older JSON round-trip preserves omitted enabled and title fields");
		LobbySniperStore.Data olderRoundTripped =
			ClientJsonFile.GSON.fromJson(olderRoundTripJson, LobbySniperStore.Data.class);
		check(olderRoundTripped.legacyTextImported == null, "missing migration flag remains null after round-trip");
		check(olderRoundTripped.targets.get(0).enabled == null, "missing enabled remains null after round-trip");
		check(olderRoundTripped.targets.get(0).title == null, "missing title remains null after round-trip");

		LobbySniperStore.Data emptyDefaults = ClientJsonFile.GSON.fromJson("{}", LobbySniperStore.Data.class);
		check(emptyDefaults.targets != null, "missing targets array uses its empty-list default");
		equalsLong(0, emptyDefaults.targets.size(), "missing targets array defaults to empty");
		String emptyJson = ClientJsonFile.GSON.toJson(emptyDefaults);
		JsonObject emptyRoot = JsonParser.parseString(emptyJson).getAsJsonObject();
		check(emptyRoot.keySet().equals(java.util.Set.of("targets")),
			"empty saved JSON keeps targets and omits the absent legacy import flag");
		equalsLong(0, emptyRoot.getAsJsonArray("targets").size(), "empty saved JSON contains an empty targets array");
		LobbySniperStore.Data emptyRoundTrip =
			ClientJsonFile.GSON.fromJson(emptyJson, LobbySniperStore.Data.class);
		check(emptyRoundTrip.targets != null, "empty target array survives JSON round-trip");
		equalsLong(0, emptyRoundTrip.targets.size(), "empty target array remains empty after round-trip");
	}

	private static void checkLobbyStorePersistence() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-lobby-sniper-store-");
			Path file = root.resolve("lobby-sniper-targets.json");
			LobbySniperStore.Data source = new LobbySniperStore.Data();
			LobbySniperStore.EntryData alice = new LobbySniperStore.EntryData();
			alice.playerName = "Alice";
			alice.enabled = false;
			alice.title = "Arrival";
			source.targets.add(alice);
			LobbySniperStore.EntryData duplicate = new LobbySniperStore.EntryData();
			duplicate.playerName = " alice ";
			duplicate.enabled = true;
			duplicate.title = "Duplicate";
			source.targets.add(duplicate);
			LobbySniperStore.EntryData invalidName = new LobbySniperStore.EntryData();
			invalidName.playerName = "Bad Name";
			invalidName.enabled = true;
			invalidName.title = "Ignored";
			source.targets.add(invalidName);
			LobbySniperStore.EntryData invalidTitle = new LobbySniperStore.EntryData();
			invalidTitle.playerName = "LongTitle";
			invalidTitle.enabled = true;
			invalidTitle.title = "x".repeat(LobbySniperStore.MAX_TITLE_LENGTH + 1);
			source.targets.add(invalidTitle);
			Files.writeString(file, ClientJsonFile.GSON.toJson(source));

			LobbySniperStoreLogic store = LobbySniperStoreLogic.forPathChecks(file);
			check(store.entries().size() == 1 && store.find("ALICE") != null
				&& !store.find("alice").enabled() && store.find("Alice").title().equals("Arrival"),
				"lobby target load keeps the first case-insensitive entry and rejects invalid names and titles");
			store.importLegacyTargets("Alice,Bob\nCarol");
			check(store.entries().size() == 3 && store.find("Alice").title().equals("Arrival")
				&& store.find("Bob").enabled() && store.find("Carol") != null,
				"legacy target import adds missing names once without replacing saved editor entries");
			check(store.put("Dana", true, "  Line 1\nLine 2  ")
				&& store.find("Dana").title().equals("Line 1 Line 2"),
				"target edits sanitize display titles before persistence");
			check(!store.put("bad name", true, "Rejected")
				&& !store.put("Eve", true, "x".repeat(LobbySniperStore.MAX_TITLE_LENGTH + 1)),
				"invalid player names and overlong custom titles cannot enter the saved target list");

			ClientJsonFile.flush();
			LobbySniperStoreLogic reloaded = LobbySniperStoreLogic.forPathChecks(file);
			check(reloaded.entries().size() == 4 && reloaded.find("ALICE") != null
				&& !reloaded.find("alice").enabled() && reloaded.find("Dana").title().equals("Line 1 Line 2"),
				"lobby target state, enabled choices, custom titles, and case-insensitive keys survive disk reload");
			LobbySniperStore.Data persisted = ClientJsonFile.GSON.fromJson(
				Files.readString(file), LobbySniperStore.Data.class);
			check(Boolean.TRUE.equals(persisted.legacyTextImported),
				"the one-time legacy target migration marker survives disk reload");
			check(reloaded.remove("Bob"), "a saved target can be removed by case-insensitive name");
			ClientJsonFile.flush();
			check(LobbySniperStoreLogic.forPathChecks(file).find("Bob") == null,
				"target removal is persisted for the next store load");
		} catch (Exception failure) {
			throw new AssertionError("lobby target path persistence check failed", failure);
		} finally {
			if (root != null) {
				try {
					try (var files = Files.list(root)) {
						for (Path file : files.toList()) Files.deleteIfExists(file);
					}
					Files.deleteIfExists(root);
				} catch (Exception cleanupFailure) {
					throw new AssertionError("Could not clean isolated Lobby Sniper files", cleanupFailure);
				}
			}
		}
	}

	private static long parsed(String text) {
		OptionalLong value = TimerDurationParser.parseSeconds(text);
		if (value.isEmpty()) throw new AssertionError("duration did not parse: " + text);
		return value.getAsLong();
	}

	private static String first(List<String> values) {
		return values.isEmpty() ? "" : values.getFirst();
	}

	private static void check(boolean condition, String name) {
		if (!condition) throw new AssertionError(name);
	}

	private static void equalsLong(long expected, long actual, String name) {
		if (expected != actual) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
	}

	private static void equalsString(String expected, String actual, String name) {
		if (!expected.equals(actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
	}
}
