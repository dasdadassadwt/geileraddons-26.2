package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.odin.DungeonMaskProcTimers;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Focused contract checks for the Invincibility Timers module and its Java-facing API. */
public final class InvincibilityTimerModuleChecks {
	private InvincibilityTimerModuleChecks() { }

	public static void run() {
		checkIdentitySettingsAndVisibility();
		checkTimerText();
		checkPublishedHudBehavior();
		checkProcSoundOutcomeClassification();
		checkAutomaticDiagnosticsLifecycle();
	}

	private static void checkAutomaticDiagnosticsLifecycle() {
		String module;
		String dungeonRuntime;
		try {
			module = Files.readString(Path.of("src", "client", "kotlin", "geiler", "addons", "client",
				"module", "impl", "DungeonInvincibilityTimerModule.kt")).replace("\r\n", "\n");
			dungeonRuntime = Files.readString(Path.of("src", "client", "kotlin", "geiler", "addons", "client",
				"module", "impl", "DungeonOdinAssistModule.kt")).replace("\r\n", "\n");
		} catch (IOException error) {
			throw new IllegalStateException("Could not read Invincibility Timers diagnostic source checks", error);
		}
		require(module.contains("override fun onEnable() {\n        diagnostics.enable(")
			&& module.contains("override fun onDisable() {\n        diagnostics.disable()"),
			"automatic diagnostics must follow the module enable/disable lifecycle");
		require(module.contains("if (centered && reading.activeTicks > 0)"),
			"the centered title shows only active invincibility; cooldowns stay on the HUD");
		require(module.contains("Items.PLAYER_HEAD") && module.contains("READY_COLOR")
				&& module.contains("COOLDOWN_HUD_COLOR") && module.contains("ACTIVE_HUD_COLOR"),
			"the HUD draws a head icon per proc with gold active, red cooldown, and green ready text");
		require(dungeonRuntime.contains("DungeonInvincibilityTimerModule.INSTANCE.publishReadings(emptyList())"),
			"enabled timer diagnostics must keep receiving snapshots while outside a dungeon");
	}

	private static void checkIdentitySettingsAndVisibility() {
		DungeonInvincibilityTimerModule module = DungeonInvincibilityTimerModule.INSTANCE;
		BooleanSetting[] trackedToggles = {
			DungeonInvincibilityTimerModule.bonzo,
			DungeonInvincibilityTimerModule.spirit,
			DungeonInvincibilityTimerModule.phoenix,
			DungeonInvincibilityTimerModule.playProcSound,
		};
		boolean[] originalToggles = new boolean[trackedToggles.length];
		for (int index = 0; index < trackedToggles.length; index++) {
			originalToggles[index] = trackedToggles[index].rawValue();
		}

		ChoiceSetting display = (ChoiceSetting) findSetting(module, "Display");
		BooleanSetting showCooldowns = (BooleanSetting) findSetting(module, "Show Cooldowns");
		NumberSetting titleDuration = (NumberSetting) findSetting(module, "Title Refresh (ticks)");
		NumberSetting soundVolume = (NumberSetting) findSetting(module, "Sound Volume");
		NumberSetting soundPitch = (NumberSetting) findSetting(module, "Sound Pitch");
		TextSetting soundId = (TextSetting) findSetting(module, "Sound ID");
		String originalDisplay = display.value();
		boolean originalCooldowns = showCooldowns.rawValue();
		float originalDuration = titleDuration.value();
		float originalVolume = soundVolume.value();
		float originalPitch = soundPitch.value();
		String originalSoundId = soundId.value();

		try {
			for (BooleanSetting setting : trackedToggles) setting.reset();
			display.reset();
			showCooldowns.reset();
			titleDuration.reset();
			soundVolume.reset();
			soundPitch.reset();
			soundId.reset();

			require(module.name().equals("Invincibility Timers")
				&& module.configName().equals("Invincibility Timer")
				&& module.category() == Category.F7
				&& module.id().equals("dungeon_invincibility_timer")
				&& module.displayName().equals("Invincibility Timers")
				&& !module.defaultEnabled(), "module and HUD retain their names, category, ID, and off default");
			require(List.of("Bonzo Mask", "Spirit Mask", "Phoenix", "Play Proc Sound", "Show Cooldowns", "Show On Item").equals(
				module.booleanSettings().stream().map(Setting::name).toList()),
				"Java-visible boolean settings keep their IDs and declaration order");
			require(List.of("Display").equals(module.choiceSettings().stream().map(Setting::name).toList())
				&& List.of("Title Refresh (ticks)", "Sound Volume", "Sound Pitch").equals(
					module.numberSettings().stream().map(Setting::name).toList())
				&& List.of("Sound ID").equals(module.textSettings().stream().map(Setting::name).toList()),
				"display, timing, and sound setting IDs remain stable");
			require(trackedToggles[0].value() && trackedToggles[1].value()
				&& trackedToggles[2].value() && trackedToggles[3].value()
				&& showCooldowns.value(), "proc, sound, and cooldown settings default on");
			require(display.value().equals("BOTH")
				&& display.choices().equals(List.of("CENTERED", "HUD", "BOTH"))
				&& display.displayValue().equals("Both"), "display values and default label remain stable");
			require(titleDuration.value() == 4f && titleDuration.min() == 1f && titleDuration.max() == 40f
				&& titleDuration.isInteger(), "centered title refresh defaults to four ticks in the 1–40 integer range");
			require(soundId.value().equals("block.note_block.pling") && soundId.maxLength() == 96
				&& soundVolume.value() == 0.8f && soundVolume.min() == 0f && soundVolume.max() == 1f
				&& soundPitch.value() == 1f && soundPitch.min() == 0.5f && soundPitch.max() == 2f,
				"sound defaults, bounds, and text limit remain stable");

			List<SettingGroup> groups = module.groups();
			SettingGroup tracked = groups.get(0);
			SettingGroup presentation = groups.get(1);
			SettingGroup sound = groups.get(2);
			require(groups.size() == 4 && "Item Overlay".equals(groups.get(3).name())
				&& List.of(tracked.name(), presentation.name(), sound.name())
					.equals(List.of("Tracked Procs", "Display", "Sound"))
				&& tracked.toggle() == DungeonInvincibilityTimerModule.bonzo
				&& tracked.settings().equals(List.of(DungeonInvincibilityTimerModule.spirit,
					DungeonInvincibilityTimerModule.phoenix))
				&& presentation.settings().equals(List.of(display, showCooldowns, titleDuration))
				&& sound.toggle() == DungeonInvincibilityTimerModule.playProcSound
				&& sound.settings().equals(List.of(soundId, soundVolume, soundPitch)),
				"settings remain in their original groups and switched sections");

			display.setValue("CENTERED");
			require(module.isSettingVisible(titleDuration), "title refresh is visible in centered mode");
			display.setValue("BOTH");
			require(module.isSettingVisible(titleDuration), "title refresh is visible in both mode");
			display.setValue("HUD");
			require(!module.isSettingVisible(titleDuration), "title refresh is hidden in HUD-only mode");
			for (Setting setting : sound.settings()) {
				require(module.isSettingVisible(setting), "sound setting is visible while proc sounds are enabled");
			}
			DungeonInvincibilityTimerModule.playProcSound.setValue(false);
			for (Setting setting : sound.settings()) {
				require(!module.isSettingVisible(setting), "sound setting is hidden while proc sounds are disabled");
			}
		} finally {
			for (int index = 0; index < trackedToggles.length; index++) {
				trackedToggles[index].setValue(originalToggles[index]);
			}
			display.setValue(originalDisplay);
			showCooldowns.setValue(originalCooldowns);
			titleDuration.setValue(originalDuration);
			soundVolume.setValue(originalVolume);
			soundPitch.setValue(originalPitch);
			soundId.setValue(originalSoundId);
		}
	}

	private static void checkTimerText() {
		DungeonMaskProcTimers.Reading active = new DungeonMaskProcTimers.Reading(
			DungeonMaskProcTimers.Proc.SPIRIT, 60, 600);
		require(DungeonInvincibilityTimerModule.timerText(active, true).equals("3.0s active"),
			"active time takes priority and remains formatted in seconds");

		DungeonMaskProcTimers.Reading cooldown = new DungeonMaskProcTimers.Reading(
			DungeonMaskProcTimers.Proc.SPIRIT, 0, 540);
		require(DungeonInvincibilityTimerModule.timerText(cooldown, true).equals("27.0s cooldown")
			&& DungeonInvincibilityTimerModule.timerText(cooldown, false) == null,
			"cooldown text respects the show-cooldowns setting");

		DungeonMaskProcTimers.Reading idle = new DungeonMaskProcTimers.Reading(
			DungeonMaskProcTimers.Proc.PHOENIX, 0, 0);
		require(DungeonInvincibilityTimerModule.timerText(idle, true) == null,
			"idle procs have no active or cooldown output");
	}

	private static void checkPublishedHudBehavior() {
		DungeonInvincibilityTimerModule module = DungeonInvincibilityTimerModule.INSTANCE;
		ChoiceSetting display = (ChoiceSetting) findSetting(module, "Display");
		BooleanSetting showCooldowns = (BooleanSetting) findSetting(module, "Show Cooldowns");
		boolean wasEnabled = module.isEnabled();
		String originalDisplay = display.value();
		boolean originalBonzo = DungeonInvincibilityTimerModule.bonzo.rawValue();
		boolean originalSpirit = DungeonInvincibilityTimerModule.spirit.rawValue();
		boolean originalPhoenix = DungeonInvincibilityTimerModule.phoenix.rawValue();
		boolean originalCooldowns = showCooldowns.rawValue();
		try {
			module.setEnabled(false);
			module.clearSnapshots();
			display.setValue("HUD");
			showCooldowns.setValue(true);
			DungeonInvincibilityTimerModule.bonzo.setValue(true);
			DungeonInvincibilityTimerModule.spirit.setValue(true);
			DungeonInvincibilityTimerModule.phoenix.setValue(true);

			module.publishReadings(List.of(new DungeonMaskProcTimers.Reading(
				DungeonMaskProcTimers.Proc.BONZO, 60, 3600)));
			module.setEnabled(true);
			module.tick();
			require(!module.visible(), "enabling the module clears readings published before its lifecycle start");

			ArrayList<DungeonMaskProcTimers.Reading> readings = new ArrayList<>(List.of(
				new DungeonMaskProcTimers.Reading(DungeonMaskProcTimers.Proc.BONZO, 60, 3600),
				new DungeonMaskProcTimers.Reading(DungeonMaskProcTimers.Proc.SPIRIT, 0, 540)));
			module.publishReadings(readings);
			readings.clear();
			module.tick();
			require(module.visible() && module.previewMode().equals("live"),
				"published active and cooldown readings are copied and become live HUD content");

			DungeonInvincibilityTimerModule.bonzo.setValue(false);
			showCooldowns.setValue(false);
			module.tick();
			require(!module.visible() && module.previewMode().equals("sample"),
				"disabled proc settings and hidden cooldowns remove all live HUD content");

			showCooldowns.setValue(true);
			module.tick();
			require(module.visible() && module.previewMode().equals("live"),
				"a tracked cooldown returns to live HUD content when cooldown display is restored");

			module.setEnabled(false);
			require(!module.visible() && module.previewMode().equals("sample"),
				"disabling the module clears its live HUD snapshot");
		} finally {
			module.setEnabled(false);
			module.clearSnapshots();
			module.publishReadings(List.of());
			display.setValue(originalDisplay);
			showCooldowns.setValue(originalCooldowns);
			DungeonInvincibilityTimerModule.bonzo.setValue(originalBonzo);
			DungeonInvincibilityTimerModule.spirit.setValue(originalSpirit);
			DungeonInvincibilityTimerModule.phoenix.setValue(originalPhoenix);
			module.setEnabled(wasEnabled);
			if (wasEnabled) module.publishReadings(List.of());
		}
	}

	private static void checkProcSoundOutcomeClassification() {
		require(DungeonInvincibilityTimerModule.classifyProcSoundOutcome(false, true, true, true)
				.equals("disabled")
			&& DungeonInvincibilityTimerModule.classifyProcSoundOutcome(true, false, true, true)
				.equals("disabled")
			&& DungeonInvincibilityTimerModule.classifyProcSoundOutcome(true, true, false, false)
				.equals("invalid_sound")
			&& DungeonInvincibilityTimerModule.classifyProcSoundOutcome(true, true, true, false)
				.equals("unregistered_sound")
			&& DungeonInvincibilityTimerModule.classifyProcSoundOutcome(true, true, true, true)
				.equals("played"),
			"proc sound outcomes distinguish both disabled gates, invalid and unregistered IDs, and playback");
	}

	private static Setting findSetting(DungeonInvincibilityTimerModule module, String name) {
		List<Setting> settings = new ArrayList<>();
		settings.addAll(module.booleanSettings());
		settings.addAll(module.choiceSettings());
		settings.addAll(module.numberSettings());
		settings.addAll(module.textSettings());
		return settings.stream().filter(setting -> setting.name().equals(name)).findFirst()
			.orElseThrow(() -> new IllegalStateException("Missing Invincibility Timers setting: " + name));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("InvincibilityTimerModuleChecks: " + message);
	}
}
