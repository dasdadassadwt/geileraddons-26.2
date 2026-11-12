package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModulePreview;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.gui.Font;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Direct regression checks for the Dungeon Map module's registered and Java-facing contract. */
public final class DungeonRoomOverlayModuleChecks {
	private DungeonRoomOverlayModuleChecks() { }

	public static void run() {
		registrationAndJavaSurface();
		settingsAndGroups();
		legacyPersistenceMigrations();
	}

	private static void registrationAndJavaSurface() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		equals("Dungeon Map", module.name(), "registered module name");
		equals("dungeon_room_map", module.id(), "stable HUD element ID");
		equals("Dungeon Map", module.displayName(), "HUD display name");
		equals("Dungeon Room Map", module.configName(), "persisted module config ID");
		equals(Category.F7, module.category(), "dungeon module category");
		check(!module.defaultEnabled(), "new profiles keep Dungeon Map disabled by default");
		check(module instanceof HudElement, "the registered module remains a HUD element");
		check(module instanceof ModulePreview, "the registered module keeps its settings preview");
		equals("Map Preview", module.previewGroupName(), "preview settings group name");

		publicMethod("tick");
		publicMethod("onDisconnect");
		publicMethod("onDungeonChatMessage", String.class);
		publicMethod("onActionbar", String.class, boolean.class);
		publicMethod("lastEnteredRoomId");
		publicMethod("restoreLegacySettings", java.util.Map.class, java.util.Map.class, java.util.Map.class);
		publicMethod("width", Font.class);
		publicMethod("height", Font.class);
		publicMethod("previewWidth", Font.class);
		publicMethod("previewHeight", Font.class);

		Method leapPlacement = declaredMethod("leapHeldNamePlacement", float.class, float.class, float.class,
			float.class, float.class, int.class, int.class);
		check(Modifier.isStatic(leapPlacement.getModifiers()) && leapPlacement.getReturnType() == float[].class,
			"Leap-held label placement remains a Java static helper");
		Method labelWidth = declaredMethod("roomLabelMaximumWidth", int.class, int.class, int.class, int.class,
			float.class);
		check(Modifier.isStatic(labelWidth.getModifiers()) && labelWidth.getReturnType() == int.class,
			"room label width remains a Java static helper");
		Method roomLabel = declaredMethod("roomLabel", DungeonRoomOverlayGeometry.Cell.class,
			DungeonRoomOverlayGeometry.Room.class);
		check(roomLabel.getReturnType() == String.class,
			"existing Java checks can still reflect the room label policy");
	}

	private static void settingsAndGroups() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		List<BooleanSetting> booleans = module.booleanSettings();
		List<NumberSetting> numbers = module.numberSettings();
		List<ChoiceSetting> choices = module.choiceSettings();
		List<ColorSetting> colors = module.colorSettings();
		BooleanSetting legacyTextShadow = booleans.stream()
			.filter(setting -> setting.name().equals("Text Shadow")).findFirst().orElseThrow();
		ChoiceSetting textShadowMode = choices.stream()
			.filter(setting -> setting.name().equals("Text Shadow")).findFirst().orElseThrow();
		check(!module.isSettingVisible(legacyTextShadow),
			"the legacy shadow toggle remains persisted but hidden from the settings panel");
		check(module.isSettingVisible(textShadowMode), "the replacement text shadow mode remains visible");

		equals(32, booleans.size(), "registered boolean setting count");
		equals(13, numbers.size(), "registered number setting count");
		equals(5, choices.size(), "registered choice setting count");
		equals(27, colors.size(), "registered color setting count");
		equals(77, booleans.size() + numbers.size() + choices.size() + colors.size(),
			"all persisted setting IDs remain registered");

		List<Boolean> oldBooleanValues = booleans.stream().map(BooleanSetting::rawValue).toList();
		List<Float> oldNumberValues = numbers.stream().map(NumberSetting::value).toList();
		List<String> oldChoiceValues = choices.stream().map(ChoiceSetting::value).toList();
		List<String> oldColorValues = colors.stream().map(ColorSetting::hex).toList();
		try {
			for (BooleanSetting setting : booleans) setting.reset();
			for (NumberSetting setting : numbers) setting.reset();
			for (ChoiceSetting setting : choices) setting.reset();
			for (ColorSetting setting : colors) setting.reset();

			equals(List.of("Show Map", "Show Preview", "Show Room Exits", "Reveal Unopened Rooms", "Show In Boss",
				"Show Players", "Show Player Names", "Show Player Class", "Color Players By Class",
				"Color Player Markers By Class", "Replace Player Name With Class", "Show Player Heads",
				"Show Own Player Head", "Use Marker For Self", "Player Names Only While Holding Leap", "Show Outline",
				"Rotate Map Around Player", "Normal Door Dynamic Color", "Use MC Font Renderer", "Text Shadow",
				"Show Checkmarks", "Hide Checkmark With Name", "Show Puzzle Name", "Show Room Secrets",
				"Skip Secret Count When Exactly One", "Skip Common Room Names", "Skip Yellow Room Name",
				"Show Fairy Checkmark", "Checkmark Only When Green", "Checkmark If Secret Count Is Zero",
				"Color Room Text By Checkmark", "Disable Predictions"),
				booleans.stream().map(Setting::name).toList(), "boolean setting IDs and order");
		boolean[] booleanDefaults = {
			true, true, true, true, false, true, true, false, false, true, false, true, false, true, true, true,
			false, true, true, false, false, false, true, true, false, false, false, false, false, false, true, false,
		};
		for (int i = 0; i < booleans.size(); i++)
			check(booleans.get(i).value() == booleanDefaults[i], "default for " + booleans.get(i).name());

		String[][] numberDefaults = {
			{"Map Scale", "0.5", "5.0", "3.6", "false"},
			{"Room Text Scale", "0.1", "2.0", "0.45", "false"},
			{"Room Size", "8.0", "24.0", "16.0", "true"},
			{"Room Gap", "0.0", "8.0", "4.0", "true"},
			{"Door Size", "0.1", "1.0", "1.0", "false"},
			{"Room Icon Size", "0.0", "2.0", "1.0", "false"},
			{"Map Padding", "0.0", "24.0", "12.0", "true"},
			{"Outline Width", "0.0", "20.0", "1.0", "true"},
			{"Player Head Size", "4.0", "16.0", "8.0", "true"},
			{"Player Head Scale", "0.0", "10.0", "1.0", "false"},
			{"Player Marker Scale", "0.0", "10.0", "1.0", "false"},
			{"Player Names Scale", "0.1", "10.0", "0.75", "false"},
			{"Unopened Darken Factor", "0.0", "1.0", "0.7", "false"},
		};
		for (int i = 0; i < numbers.size(); i++) {
			NumberSetting setting = numbers.get(i);
			String[] expected = numberDefaults[i];
			equals(expected[0], setting.name(), "number setting order at " + i);
			near(Float.parseFloat(expected[1]), setting.min(), "minimum for " + setting.name());
			near(Float.parseFloat(expected[2]), setting.max(), "maximum for " + setting.name());
			near(Float.parseFloat(expected[3]), setting.value(), "default for " + setting.name());
			check(Boolean.parseBoolean(expected[4]) == setting.isInteger(), "integer behavior for " + setting.name());
		}

		String[][] choiceDefaults = {
			{"Room Text", "Both", "Both|Room Name|Room Secrets|Off"},
			{"Room Text Alignment", "CenterL", "Top Left|Top Right|Bottom Left|Bottom Right|Center|CenterL"},
			{"Room Icon Alignment", "Center", "Top Left|Top Right|Bottom Left|Bottom Right|Center|CenterL"},
			{"Text Shadow", "None", "None|Drop|Outline"},
			{"Icon Style", "IllegalMap", "IllegalMap|NEU|Vanilla New|Vanilla Old"},
		};
		for (int i = 0; i < choices.size(); i++) {
			ChoiceSetting setting = choices.get(i);
			String[] expected = choiceDefaults[i];
			equals(expected[0], setting.name(), "choice setting order at " + i);
			equals(expected[1], setting.value(), "default for " + setting.name());
			equals(Arrays.asList(expected[2].split("\\|")), setting.choices(), "choices for " + setting.name());
		}
		check("Item Icon".equals(choices.get(4).displayValue()), "icon style keeps its player-facing label");

		String[][] colorDefaults = {
			{"MapBackground", "F2F2F249"}, {"MapOutline", "E3E1E1BA"}, {"TextColor", "646464"},
			{"PlayerNameColor", "464646"}, {"CurrentRoom", "FFFF55"}, {"PlayerMarker", "44D6FF"},
			{"LocalPlayerMarker", "55FF55"}, {"RoomColor", "6B3A11"}, {"PuzzleColor", "750085"},
			{"TrapColor", "D87F33"}, {"MinibossColor", "6B3A11"}, {"EntranceColor", "148500"},
			{"FairyColor", "F4138B"}, {"BloodRoomColor", "FF0000"}, {"UnknownRoomColor", "282828"},
			{"RareRoomColor", "6B3A11"}, {"ChampionRoomColor", "FEDF00"}, {"PendingRoomColor", "37415A"},
			{"NormalDoorColor", "6B3A11"}, {"GlassDoorColor", "5FE4DC"}, {"StartDoorColor", "64E085"},
			{"WitherDoorColor", "000000"}, {"BloodDoorColor", "FF5555"}, {"FairyDoorColor", "F4138B"},
			{"UnknownDoorColor", "1C1C1C"}, {"UnopenedRoomColor", "484C5B"}, {"UnopenedTextColor", "E1E6F0"},
		};
		for (int i = 0; i < colors.size(); i++) {
			equals(colorDefaults[i][0], colors.get(i).name(), "color setting order at " + i);
			equals(colorDefaults[i][1], colors.get(i).hex(), "default for " + colors.get(i).name());
		}
	} finally {
		for (int i = 0; i < booleans.size(); i++) booleans.get(i).setValue(oldBooleanValues.get(i));
		for (int i = 0; i < numbers.size(); i++) numbers.get(i).setValue(oldNumberValues.get(i));
		for (int i = 0; i < choices.size(); i++) choices.get(i).setValue(oldChoiceValues.get(i));
		for (int i = 0; i < colors.size(); i++)
			check(colors.get(i).setHex(oldColorValues.get(i)), "restore color setting " + colors.get(i).name());
	}

		equals(List.of("Overlay", "Map Preview", "Map Style", "Appearance"),
			module.groups().stream().map(SettingGroup::name).toList(), "top-level settings groups");
		SettingGroup overlay = module.groups().get(0);
		equals("Show Map", overlay.toggle().name(), "overlay section toggle");
		equals(List.of("Show Room Exits", "Reveal Unopened Rooms", "Show In Boss", "Rotate Map Around Player"),
			overlay.settings().stream().map(Setting::name).toList(), "overlay section settings");
		SettingGroup preview = module.groups().get(1);
		equals("Show Preview", preview.toggle().name(), "preview section toggle");
		SettingGroup mapStyle = module.groups().get(2);
		equals(List.of("Copy Style to Clipboard", "Import Style from Clipboard", "Reset All Map Settings"),
			mapStyle.settings().stream().map(Setting::name).toList(), "style actions group");
		SettingGroup appearance = module.groups().get(3);
		equals(List.of("Layout", "Players", "Room Text and Icons", "Colors"),
			appearance.children().stream().map(SettingGroup::name).toList(), "appearance settings groups");
		SettingGroup colorsGroup = appearance.children().get(3);
		equals(List.of("Map", "Rooms", "Doors", "Unopened Rooms"),
			colorsGroup.children().stream().map(SettingGroup::name).toList(), "color settings groups");
		check(module.actions().isEmpty(), "map style actions remain inside the Map Style group");
	}

	private static void legacyPersistenceMigrations() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		ChoiceSetting roomTextAlignment = module.choiceSettings().stream()
			.filter(setting -> setting.name().equals("Room Text Alignment")).findFirst().orElseThrow();
		ChoiceSetting roomIconAlignment = module.choiceSettings().stream()
			.filter(setting -> setting.name().equals("Room Icon Alignment")).findFirst().orElseThrow();
		BooleanSetting markerClassColors = module.booleanSettings().stream()
			.filter(setting -> setting.name().equals("Color Player Markers By Class")).findFirst().orElseThrow();
		NumberSetting playerHeadScale = module.numberSettings().stream()
			.filter(setting -> setting.name().equals("Player Head Scale")).findFirst().orElseThrow();
		String oldTextAlignment = roomTextAlignment.value();
		String oldIconAlignment = roomIconAlignment.value();
		boolean oldMarkerClassColors = markerClassColors.value();
		float oldPlayerHeadScale = playerHeadScale.value();
		String prefix = module.configName() + ".";
		String oldClassColorKey = prefix + "Color Players By Class";
		String markerClassColorKey = prefix + "Color Player Markers By Class";
		String oldMarkerScaleKey = prefix + "Player Marker Scale";
		String playerHeadScaleKey = prefix + "Player Head Scale";
		String textAlignmentKey = prefix + "Room Text Alignment";
		String iconAlignmentKey = prefix + "Room Icon Alignment";
		try {
			check(module.restoreLegacySettings(Map.of(oldClassColorKey, false),
				Map.of(oldMarkerScaleKey, 2.25f), Map.of(textAlignmentKey, "Right", iconAlignmentKey, "Left")),
				"legacy map alignment, head-scale, and marker-color settings are reported as migrated");
			equals("Top Right", roomTextAlignment.value(), "legacy text alignment maps to its current choice");
			equals("Top Left", roomIconAlignment.value(), "legacy icon alignment maps to its current choice");
			near(2.25f, playerHeadScale.value(), "legacy player marker scale restores the head scale");
			check(!markerClassColors.value(), "legacy player class coloring restores marker colors");

			roomTextAlignment.setValue("Bottom Right");
			roomIconAlignment.setValue("Top Right");
			playerHeadScale.setValue(0.75f);
			markerClassColors.setValue(true);
			check(!module.restoreLegacySettings(
				Map.of(oldClassColorKey, false, markerClassColorKey, true),
				Map.of(oldMarkerScaleKey, 2.25f, playerHeadScaleKey, 0.75f),
				Map.of(textAlignmentKey, "Bottom Right", iconAlignmentKey, "Top Right")),
				"valid current map settings suppress legacy aliases");
			equals("Bottom Right", roomTextAlignment.value(), "current text alignment wins over migrated data");
			equals("Top Right", roomIconAlignment.value(), "current icon alignment wins over migrated data");
			near(0.75f, playerHeadScale.value(), "current head scale wins over marker scale alias");
			check(markerClassColors.value(), "current marker class-color setting wins over its legacy key");
		} finally {
			roomTextAlignment.setValue(oldTextAlignment);
			roomIconAlignment.setValue(oldIconAlignment);
			markerClassColors.setValue(oldMarkerClassColors);
			playerHeadScale.setValue(oldPlayerHeadScale);
		}
	}

	private static Method publicMethod(String name, Class<?>... parameterTypes) {
		try {
			return DungeonRoomOverlayModule.class.getMethod(name, parameterTypes);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Missing public Java API: " + name, error);
		}
	}

	private static Method declaredMethod(String name, Class<?>... parameterTypes) {
		try {
			return DungeonRoomOverlayModule.class.getDeclaredMethod(name, parameterTypes);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Missing reflected Java API: " + name, error);
		}
	}

	private static void near(float expected, float actual, String message) {
		if (Float.compare(expected, actual) != 0)
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!expected.equals(actual))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
