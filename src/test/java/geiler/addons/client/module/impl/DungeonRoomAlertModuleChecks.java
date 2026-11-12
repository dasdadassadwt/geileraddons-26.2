package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.util.List;
import java.util.Objects;

/** Direct checks for Room Alerts identity, persisted setting keys, defaults, and config compatibility. */
public final class DungeonRoomAlertModuleChecks {
	private DungeonRoomAlertModuleChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		var module = DungeonRoomAlertModule.INSTANCE;
		check("Room Alerts".equals(module.name()), "visible module name remains stable");
		check("Dungeon Room Alerts".equals(module.configName()),
			"the original module config prefix is retained after the visible name change");
		check(Category.F7 == module.category(), "room alerts remain in the dungeon category");
		check(!module.defaultEnabled() && !module.isActive(), "room alerts remain opt-in on a fresh install");

		List<String> booleanIds = module.booleanSettings().stream().map(BooleanSetting::name).toList();
		equals(List.of(
			"Room Cleared Alert", "Show Title", "Play Sound",
			"Secrets Complete Alert", "Secrets Show Title", "Secrets Play Sound"
		), booleanIds, "boolean setting IDs and order remain compatible");
		List<String> everyId = new java.util.ArrayList<>(booleanIds);
		module.textSettings().forEach(s -> everyId.add(s.name()));
		module.numberSettings().forEach(s -> everyId.add(s.name()));
		module.colorSettings().forEach(s -> everyId.add(s.name()));
		check(everyId.size() == new java.util.HashSet<>(everyId).size(),
			"every setting persists under its own key, so the two alert groups never share a value");
		check(module.booleanSettings().stream().allMatch(BooleanSetting::value),
			"both alert groups and their title and sound options default on");

		List<String> textIds = module.textSettings().stream().map(TextSetting::name).toList();
		equals(List.of("Title Text", "Room Cleared Sound ID", "Secrets Title Text", "Secrets Complete Sound ID"),
			textIds, "text setting IDs remain compatible");
		List<TextSetting> texts = module.textSettings();
		equals(List.of("Room Cleared", "entity.player.levelup", "Secrets Cleared", "block.note_block.pling"),
			texts.stream().map(TextSetting::value).toList(), "title and sound defaults remain distinct");
		equals(List.of(64, 96, 64, 96), texts.stream().map(TextSetting::maxLength).toList(),
			"title and sound text bounds remain unchanged");

		List<String> colorIds = module.colorSettings().stream().map(ColorSetting::name).toList();
		equals(List.of("Room Cleared Title Color", "Secrets Complete Title Color"),
			colorIds, "independent title color keys remain compatible");
		List<ColorSetting> colors = module.colorSettings();
		equals(0xFF00DCFF, colors.get(0).argb(), "room-clear title color default remains cyan");
		equals(0xFF55FF64, colors.get(1).argb(), "secrets-complete title color default remains green");

		List<String> numberIds = module.numberSettings().stream().map(NumberSetting::name).toList();
		equals(List.of("Sound Volume", "Sound Pitch", "Secrets Sound Volume", "Secrets Sound Pitch", "Title Duration (ticks)"),
			numberIds, "numeric setting IDs and order remain compatible");
		List<NumberSetting> numbers = module.numberSettings();
		equals(List.of(0.8f, 1.0f, 0.8f, 1.0f, 60.0f),
			numbers.stream().map(NumberSetting::value).toList(), "volume, pitch, and duration defaults remain unchanged");
		equals(List.of(0.0f, 0.1f, 0.0f, 0.1f, 1.0f),
			numbers.stream().map(NumberSetting::min).toList(), "numeric minimums remain unchanged");
		equals(List.of(1.0f, 2.0f, 1.0f, 2.0f, 200.0f),
			numbers.stream().map(NumberSetting::max).toList(), "numeric maximums remain unchanged");
		check(numbers.get(4).isInteger(), "title duration remains an integer setting");

		List<SettingGroup> groups = module.groups();
		equals(List.of("Room Cleared", "Secrets Cleared", "Display"),
			groups.stream().map(SettingGroup::name).toList(), "settings group names and order remain unchanged");
		equals("Room Cleared Alert", groups.get(0).toggle().name(), "room-clear group keeps its master toggle");
		equals("Secrets Complete Alert", groups.get(1).toggle().name(),
			"secrets-complete group keeps its master toggle");
		check(groups.get(2).toggle() == null, "display settings remain outside the alert toggles");
		equals(List.of("Title Duration (ticks)"),
			groups.get(2).settings().stream().map(setting -> setting.name()).toList(),
			"shared title duration remains in Display");

		String legacySharedSoundKey = module.configName() + ".Sound ID";
		String roomClearSoundKey = module.configName() + "." + texts.get(1).name();
		String secretsSoundKey = module.configName() + "." + texts.get(3).name();
		equals("Dungeon Room Alerts.Sound ID", legacySharedSoundKey,
			"the former shared sound key remains addressable for migration");
		equals("Dungeon Room Alerts.Room Cleared Sound ID", roomClearSoundKey,
			"ModConfig's legacy sound migration target remains the room-clear sound key");
		equals("Dungeon Room Alerts.Secrets Complete Sound ID", secretsSoundKey,
			"secrets sound remains independently persisted");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
