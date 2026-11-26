package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.helpers.DungeonMaskProcTimers;
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
		checkHudRowState();
		checkCenteredTitle();
		checkPublishedHudBehavior();
		checkProcSoundOutcomeClassification();
		checkPartyMessageAndAlwaysShow();
		checkAutomaticDiagnosticsLifecycle();
		checkPoopPop();
	}

	/** The poop cutscene is a default-on Invincibility Timers setting that plays only for an enabled proc. */
	private static void checkPoopPop() {
		DungeonInvincibilityTimerModule module = DungeonInvincibilityTimerModule.INSTANCE;
		BooleanSetting poop = (BooleanSetting) findSetting(module, "Poop Pop");
		NumberSetting volume = (NumberSetting) findSetting(module, "Poop Volume");
		boolean original = poop.rawValue();
		try {
			poop.reset();
			require(poop.value() && volume.min() == 0f && volume.max() == 1f && volume.value() == 0.8f,
				"Poop Pop defaults on with a 0-1 volume that defaults to 0.8");
			require(module.isSettingVisible(volume), "Poop Volume shows while Poop Pop is on");
			poop.setValue(false);
			require(!module.isSettingVisible(volume), "Poop Volume hides while Poop Pop is off");
		} finally {
			poop.setValue(original);
		}
		require(PoopPop.shouldPlay(true, true) && !PoopPop.shouldPlay(false, true) && !PoopPop.shouldPlay(true, false),
			"the cutscene plays only when Poop Pop is on and the proc itself is tracked");
		require(PoopPop.soundNames().equals(List.of("poop.pop", "poop.toot")) && PoopPop.ITEM_MODEL_PATH.equals("poop"),
			"the cutscene uses the poop item model and the pop and toot sound events");
		Path assets = Path.of("src", "client", "resources", "assets", "geileraddons");
		for (String file : List.of("items/poop.json", "models/item/poop.json", "textures/item/poop.png", "sounds.json",
			"sounds/poop/squish.ogg", "sounds/poop/toot.ogg", "sounds/poop/rumble.ogg")) {
			require(Files.isRegularFile(assets.resolve(file)), "missing poop asset " + file);
		}
	}

	private static void checkAutomaticDiagnosticsLifecycle() {
		String module;
		String dungeonRuntime;
		try {
			module = Files.readString(Path.of("src", "client", "kotlin", "geiler", "addons", "client",
				"module", "impl", "DungeonInvincibilityTimerModule.kt")).replace("\r\n", "\n");
			dungeonRuntime = Files.readString(Path.of("src", "client", "kotlin", "geiler", "addons", "client",
				"module", "impl", "DungeonAssistModule.kt")).replace("\r\n", "\n");
		} catch (IOException error) {
			throw new IllegalStateException("Could not read Invincibility Timers diagnostic source checks", error);
		}
		require(module.contains("override fun onEnable() {\n        diagnostics.enable(")
			&& module.contains("override fun onDisable() {\n        diagnostics.disable()"),
			"automatic diagnostics must follow the module enable/disable lifecycle");
		require(module.contains("if (centered && title != null)")
				&& module.contains("TitleAlertStack.submitCountdown("),
			"the centered title shows only active invincibility, re-submitted every tick; cooldowns stay on the HUD");
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
			require(List.of("Bonzo Mask", "Spirit Mask", "Phoenix", "Play Proc Sound", "Show Cooldowns", "Show On Item",
					"Always Show In Dungeon", "Party Announce", "Poop Pop").equals(
				module.booleanSettings().stream().map(Setting::name).toList()),
				"Java-visible boolean settings keep their IDs and declaration order");
			require(List.of("Display").equals(module.choiceSettings().stream().map(Setting::name).toList())
				&& List.of("Title Refresh (ticks)", "Sound Volume", "Sound Pitch", "Poop Volume").equals(
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
			BooleanSetting alwaysShow = (BooleanSetting) findSetting(module, "Always Show In Dungeon");
			BooleanSetting partyAnnounce = (BooleanSetting) findSetting(module, "Party Announce");
			require(!alwaysShow.rawValue() && !partyAnnounce.rawValue(),
				"Always Show In Dungeon and Party Announce default off");
			require(groups.size() == 6 && "Item Overlay".equals(groups.get(3).name())
				&& "Party Chat".equals(groups.get(4).name())
				&& "Poop Pop".equals(groups.get(5).name())
				&& groups.get(4).settings().contains(partyAnnounce)
				&& List.of(tracked.name(), presentation.name(), sound.name())
					.equals(List.of("Tracked Procs", "Display", "Sound"))
				&& tracked.toggle() == DungeonInvincibilityTimerModule.bonzo
				&& tracked.settings().equals(List.of(DungeonInvincibilityTimerModule.spirit,
					DungeonInvincibilityTimerModule.phoenix))
				&& presentation.settings().equals(List.of(display, showCooldowns, titleDuration, alwaysShow))
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

	private static void checkHudRowState() {
		DungeonMaskProcTimers.Proc bonzo = DungeonMaskProcTimers.Proc.BONZO;
		DungeonInvincibilityTimerModule.RowState active = DungeonInvincibilityTimerModule.rowState(
			new DungeonMaskProcTimers.Reading(bonzo, 40, 3600), true);
		require(active == DungeonInvincibilityTimerModule.RowState.ACTIVE, "an active proc shows its active countdown");
		for (boolean showCooldowns : new boolean[] {true, false}) {
			DungeonInvincibilityTimerModule.RowState onCooldown = DungeonInvincibilityTimerModule.rowState(
				new DungeonMaskProcTimers.Reading(bonzo, 0, 2000), showCooldowns);
			require(onCooldown != DungeonInvincibilityTimerModule.RowState.READY,
				"a proc on cooldown is never reported ready, whatever Show Cooldowns says");
			require(onCooldown == (showCooldowns
					? DungeonInvincibilityTimerModule.RowState.COOLDOWN : DungeonInvincibilityTimerModule.RowState.HIDDEN),
				"a cooldown row follows Show Cooldowns by showing or hiding, never by turning into a ready check");
		}
		require(DungeonInvincibilityTimerModule.rowState(new DungeonMaskProcTimers.Reading(bonzo, 0, 0), true)
				== DungeonInvincibilityTimerModule.RowState.READY
			&& DungeonInvincibilityTimerModule.rowState(null, true) == DungeonInvincibilityTimerModule.RowState.READY,
			"only a proc with no active time and no cooldown is ready");
	}

	private static void checkCenteredTitle() {
		DungeonMaskProcTimers.Reading phoenix = new DungeonMaskProcTimers.Reading(
			DungeonMaskProcTimers.Proc.PHOENIX, 80, 1200);
		DungeonInvincibilityTimerModule.CenteredTitle one = DungeonInvincibilityTimerModule.centeredTitle(List.of(phoenix));
		require(one != null && one.getTitle().equals("Phoenix Pet") && one.getSubtitle().equals("4.0s"),
			"an active Phoenix proc titles the pet and counts the remaining time down");
		DungeonInvincibilityTimerModule.CenteredTitle later = DungeonInvincibilityTimerModule.centeredTitle(List.of(
			new DungeonMaskProcTimers.Reading(DungeonMaskProcTimers.Proc.PHOENIX, 30, 1200)));
		require(later != null && later.getSubtitle().equals("1.5s"), "the subtitle tracks the live remaining ticks");
		require(DungeonInvincibilityTimerModule.centeredTitle(List.of(
				new DungeonMaskProcTimers.Reading(DungeonMaskProcTimers.Proc.BONZO, 0, 3000))) == null
			&& DungeonInvincibilityTimerModule.centeredTitle(List.of()) == null,
			"cooldown-only or empty readings produce no centered title");
		DungeonInvincibilityTimerModule.CenteredTitle both = DungeonInvincibilityTimerModule.centeredTitle(List.of(
			new DungeonMaskProcTimers.Reading(DungeonMaskProcTimers.Proc.BONZO, 60, 3600), phoenix));
		require(both != null && both.getTitle().equals("Bonzo Mask") && both.getSubtitle().equals("3.0s · Phoenix Pet 4.0s"),
			"the proc ending first leads and the others follow in the subtitle");
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

	private static void checkPartyMessageAndAlwaysShow() {
		require(DungeonInvincibilityTimerModule.partyMessage(DungeonMaskProcTimers.Proc.BONZO).equals("Bonzo proc")
				&& DungeonInvincibilityTimerModule.partyMessage(DungeonMaskProcTimers.Proc.SPIRIT).equals("Spirit proc")
				&& DungeonInvincibilityTimerModule.partyMessage(DungeonMaskProcTimers.Proc.PHOENIX).equals("Phoenix proc"),
			"party announce names the proc and says proc");
		require(DungeonInvincibilityTimerModule.shouldShowHud(true, false, true, true)
				&& !DungeonInvincibilityTimerModule.shouldShowHud(true, false, true, false)
				&& !DungeonInvincibilityTimerModule.shouldShowHud(true, false, false, true)
				&& !DungeonInvincibilityTimerModule.shouldShowHud(false, false, true, true)
				&& DungeonInvincibilityTimerModule.shouldShowHud(true, true, false, false)
				&& !DungeonInvincibilityTimerModule.shouldShowHud(false, true, true, true),
			"always show keeps the HUD up only in a dungeon with a HUD display; live lines still show");
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
