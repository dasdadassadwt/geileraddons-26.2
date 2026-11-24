package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ModulePreview;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Focused behavior and parity checks for Score Titles' alerts, settings, and Java-facing Odin adapter API. */
public final class ScoreTitlesChecks {
	private ScoreTitlesChecks() { }

	public static void run() {
		checkIdentityAndEntrypoints();
		checkEnableLifecycle();
		checkSettingsAndGroups();
		checkPreviewBehavior();
		checkAlertExpiration();
	}

	private static void checkIdentityAndEntrypoints() {
		DungeonScorerModule module = DungeonScorerModule.INSTANCE;
		check(module == DungeonScorerModule.INSTANCE, "the module singleton remains stable");
		check("Score Titles".equals(module.name()) && "Scorer".equals(module.configName()),
			"the display name and persisted module key remain stable");
		check("Shows a title, and optionally a chat message, when the dungeon score reaches 270 and 300.".equals(module.description())
			&& module.category() == Category.F7 && !module.defaultEnabled(),
			"the description, category, and fresh-install state remain stable");
		check(ModulePreview.class.isAssignableFrom(DungeonScorerModule.class),
			"the settings preview remains part of the module API");

		assertStaticField("INSTANCE", DungeonScorerModule.class);
		assertStaticField("alerts", BooleanSetting.class);
		assertStaticField("alertText", TextSetting.class);
		assertStaticField("durationTicks", NumberSetting.class);
		assertStaticField("playSound", BooleanSetting.class);
		assertStaticField("soundId", TextSetting.class);
		assertStaticField("chat270", BooleanSetting.class);
		assertStaticField("chat300", BooleanSetting.class);
		assertStaticField("paulBonus", ChoiceSetting.class);
		assertPublicMethod("showAlert", Void.TYPE, String.class, Long.TYPE);
		assertPublicMethod("recordSoundOutcome", Void.TYPE, Long.TYPE, String.class, String.class);
		assertPublicMethod("tick", Void.TYPE, Long.TYPE);
		assertPublicMethod("clearAlert", Void.TYPE);
		assertPublicMethod("previewLines", List.class, Font.class, Integer.TYPE);
		assertPublicMethod("drawPreview", Boolean.TYPE, GuiGraphicsExtractor.class, Font.class,
			Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE);
	}

	private static void checkEnableLifecycle() {
		DungeonScorerModule module = DungeonScorerModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(true);
			check(module.isEnabled(), "Score Titles can be enabled through the shared module lifecycle");
			module.setEnabled(false);
			check(!module.isEnabled(), "Score Titles can be disabled through the shared module lifecycle");
		} finally {
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkSettingsAndGroups() {
		DungeonScorerModule module = DungeonScorerModule.INSTANCE;
		List<BooleanSetting> booleans = module.booleanSettings();
		assertNames(booleans, List.of("Score Alerts", "Play Sound", "Chat Message at 270", "Chat Message at 300"),
			"boolean setting IDs");
		check(booleans.get(0) == DungeonScorerModule.alerts && booleans.get(0).rawValue()
			&& booleans.get(1) == DungeonScorerModule.playSound && booleans.get(1).rawValue()
			&& booleans.get(2) == DungeonScorerModule.chat270 && !booleans.get(2).rawValue()
			&& booleans.get(3) == DungeonScorerModule.chat300 && booleans.get(3).rawValue(),
			"boolean defaults: titles and sound on, chat at 270 off, chat at 300 on");

		List<NumberSetting> numbers = module.numberSettings();
		assertNames(numbers, List.of("Duration (Ticks)"), "number setting IDs");
		assertNumber(numbers.get(0), 10f, 300f, 20f, "alert duration");
		check(numbers.get(0) == DungeonScorerModule.durationTicks,
			"the Odin adapter keeps the registered number setting instances");

		List<TextSetting> texts = module.textSettings();
		assertNames(texts, List.of("Alert Text", "Sound ID"), "text setting IDs");
		check(texts.get(0) == DungeonScorerModule.alertText && "Score {score}".equals(texts.get(0).value())
			&& texts.get(0).maxLength() == 80,
			"alert text keeps its placeholder, default, and length limit");
		check(texts.get(1) == DungeonScorerModule.soundId && "block.anvil.land".equals(texts.get(1).value())
			&& texts.get(1).maxLength() == 96,
			"the Odin sound adapter keeps the default event ID and length limit");

		List<ChoiceSetting> choices = module.choiceSettings();
		assertNames(choices, List.of("Paul Score Bonus"), "choice setting IDs");
		ChoiceSetting paul = choices.get(0);
		check(paul == DungeonScorerModule.paulBonus && "UNKNOWN".equals(paul.value())
			&& paul.choices().equals(List.of("UNKNOWN", "NO", "YES")),
			"Paul score input keeps its persisted values and default");
		check("Auto (detect Paul)".equals(paul.displayValue()), "the default Paul label is Auto");

		List<SettingGroup> groups = module.groups();
		List<String> groupNames = new ArrayList<>();
		for (SettingGroup group : groups) groupNames.add(group.name());
		check(groupNames.equals(List.of("Titles at 270 and 300", "Chat Messages", "Score Inputs")),
			"setting group IDs changed: " + groupNames);
		assertGroup(groups.get(0), DungeonScorerModule.alerts,
			List.of("Alert Text", "Duration (Ticks)", "Play Sound", "Sound ID"), "Titles at 270 and 300");
		assertGroup(groups.get(1), null, List.of("Chat Message at 270", "Chat Message at 300"), "Chat Messages");
		assertGroup(groups.get(2), null, List.of("Paul Score Bonus"), "Score Inputs");
	}

	private static void checkPreviewBehavior() {
		DungeonScorerModule module = DungeonScorerModule.INSTANCE;
		TextSetting alertText = DungeonScorerModule.alertText;
		String oldText = alertText.value();
		try {
			check("Score 270".equals(previewNotice(module)),
				"the settings preview keeps its default score alert text");

			alertText.setValue("  Milestone {score}!  ");
			check("Milestone 270!".equals(previewNotice(module)),
				"the preview reflects alert-template changes with trimmed placeholder substitution");

			alertText.setValue("  ");
			check("Score 270".equals(previewNotice(module)),
				"a blank alert template falls back to the default score message");
		} finally {
			alertText.setValue(oldText);
		}
	}

	private static void checkAlertExpiration() {
		DungeonScorerModule module = DungeonScorerModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(false);
			module.showAlert("", 20L);
			check("".equals(privateField(module, "notice"))
				&& Long.valueOf(20L).equals(privateField(module, "noticeUntilTick")),
				"showAlert retains its text and absolute expiry tick");

			module.tick(20L);
			check("".equals(privateField(module, "notice")),
				"an alert remains active through its inclusive expiry tick");
			module.tick(21L);
			check(privateField(module, "notice") == null,
				"the alert expires on the first tick after its deadline");

			module.showAlert(" ", -5L);
			check(Long.valueOf(0L).equals(privateField(module, "noticeUntilTick")),
				"negative alert deadlines are clamped to zero");
			module.clearAlert();
			check(privateField(module, "notice") == null,
				"clearAlert removes the retained alert immediately");
		} finally {
			module.clearAlert();
			module.setEnabled(wasEnabled);
		}
	}

	private static Object privateField(DungeonScorerModule module, String name) {
		try {
			Field field = DungeonScorerModule.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(module);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Score Titles state field is missing: " + name, exception);
		}
	}

	private static String previewNotice(DungeonScorerModule module) {
		try {
			Method method = DungeonScorerModule.class.getDeclaredMethod("previewNotice");
			method.setAccessible(true);
			return (String) method.invoke(module);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Score Titles preview formatting method is missing", exception);
		}
	}

	private static void assertNumber(NumberSetting setting, float min, float max, float value, String label) {
		check(setting.min() == min && setting.max() == max && setting.value() == value && setting.isInteger(),
			label + " keeps its integer bounds and default");
	}

	private static void assertGroup(SettingGroup group, BooleanSetting toggle, List<String> names, String label) {
		assertNames(group.settings(), names, label + " group rows");
		check(group.toggle() == toggle && !group.collapsedByDefault() && group.children().isEmpty(),
			label + " keeps its toggle and expansion behavior");
	}

	private static void assertNames(List<? extends Setting> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : actual) names.add(setting.name());
		check(names.equals(expected), label + " changed: " + names);
	}

	private static void assertStaticField(String name, Class<?> type) {
		try {
			Field field = DungeonScorerModule.class.getField(name);
			check(Modifier.isStatic(field.getModifiers()) && field.getType() == type,
				"Java field " + name + " remains public static with its original type");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Java field is missing: " + name, exception);
		}
	}

	private static void assertPublicMethod(String name, Class<?> result, Class<?>... parameters) {
		try {
			Method method = DungeonScorerModule.class.getMethod(name, parameters);
			check(method.getReturnType() == result, "Java method signature changed: " + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Java method is missing: " + name, exception);
		}
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
