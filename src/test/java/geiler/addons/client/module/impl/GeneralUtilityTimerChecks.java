package geiler.addons.client.module.impl;

import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.utility.CountdownTimerState;
import geiler.addons.client.utility.TimerCompletionPresentation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Focused parity checks for General settings and the utility timer's Java-facing API. */
public final class GeneralUtilityTimerChecks {
	private GeneralUtilityTimerChecks() { }

	public static void run() {
		checkGeneralIdentityAndSettings();
		checkTimerJavaApiAndHud();
		checkTimerTransitionsAndCompletionRouting();
	}

	private static void checkGeneralIdentityAndSettings() {
		GeneralModule module = GeneralModule.INSTANCE;
		check(module == GeneralModule.INSTANCE, "General remains a stable singleton");
		check("General".equals(module.name()) && "General".equals(module.configName())
			&& module.category() == Category.MISCELLANEOUS && !module.defaultEnabled(),
			"General keeps its module ID, category, and default enabled state");
		check(!module.showsToggleControl() && !module.showsKeybindControl(),
			"General keeps its hidden toggle and keybind controls");
		assertStaticField(GeneralModule.class, "INSTANCE", GeneralModule.class);
		assertPublicMethod(GeneralModule.class, "islandDetectionApi", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "chatTriggersInTextScreens", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "checkForUpdates", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "availabilityNotices", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "showTimerHud", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "timerCompletionAlert", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "timerCompletionChat", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "timerCompletionTitle", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "timerCompletionSound", BooleanSetting.class);
		assertPublicMethod(GeneralModule.class, "timerCompletionText", TextSetting.class);
		assertPublicMethod(GeneralModule.class, "timerTitleDurationTicks", NumberSetting.class);

		assertNames(module.booleanSettings(), List.of(
			"Chat Triggers In Text Screens", "Check for Updates", "Auto Update", "Island Detection API",
			"Show Availability Notices", "Supply Command Messages", "Show Timer HUD", "Completion Alert", "Chat Alert",
			"Title Alert", "Sound Alert", "Custom Window Icon"), "boolean setting IDs");
		check(!module.chatTriggersInTextScreens().value() && module.checkForUpdates().value()
			&& module.islandDetectionApi().value() && module.availabilityNotices().value()
			&& module.showTimerHud().value() && module.timerCompletionAlert().value()
			&& module.timerCompletionChat().value() && !module.timerCompletionTitle().value()
			&& module.timerCompletionSound().value(), "General keeps all boolean defaults");

		assertNames(module.textSettings(), List.of("Completion Text"), "text setting IDs");
		TextSetting completionText = module.timerCompletionText();
		check(TimerCompletionPresentation.DEFAULT_MESSAGE.equals(completionText.value())
			&& completionText.maxLength() == 128, "completion text keeps its default and 128-character limit");
		assertNames(module.numberSettings(), List.of("Title Duration (ticks)"), "number setting IDs");
		NumberSetting titleDuration = module.timerTitleDurationTicks();
		check(titleDuration.min() == 1f && titleDuration.max() == 600f
			&& titleDuration.value() == TimerCompletionPresentation.DEFAULT_TITLE_DURATION_TICKS
			&& titleDuration.isInteger(), "title duration keeps its bounds, integer control, and default");

		List<String> groupNames = new ArrayList<>();
		for (SettingGroup group : module.groups()) groupNames.add(group.name());
		check(groupNames.equals(List.of("Macros", "Island Detection", "Updates", "Click GUI", "Timer Settings", "Window", "Community")),
			"General keeps its ordered setting groups: " + groupNames);
		SettingGroup timerSettings = module.groups().get(4);
		assertNames(timerSettings.settings(), List.of("Show Timer HUD", "Completion Alert", "Chat Alert",
			"Title Alert", "Sound Alert", "Completion Text", "Title Duration (ticks)"),
			"Timer Settings rows");
		check(timerSettings.collapsedByDefault() && timerSettings.toggle() == null
			&& timerSettings.children().isEmpty(), "Timer Settings remains folded without a group toggle");
	}

	private static void checkTimerJavaApiAndHud() {
		check(HudElement.class.isAssignableFrom(UtilityTimerModule.class),
			"Utility Timer remains a HUD element and does not register as a module");
		assertStaticField(UtilityTimerModule.class, "INSTANCE", UtilityTimerModule.class);
		assertPublicMethod(UtilityTimerModule.class, "state", CountdownTimerState.class);
		assertPublicMethod(UtilityTimerModule.class, "setTimer", Void.TYPE, Long.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "pauseTimer", Boolean.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "resumeTimer", Boolean.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "resetTimer", Boolean.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "tick", Void.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "id", String.class);
		assertPublicMethod(UtilityTimerModule.class, "displayName", String.class);
		assertPublicMethod(UtilityTimerModule.class, "width", Integer.TYPE, Font.class);
		assertPublicMethod(UtilityTimerModule.class, "previewWidth", Integer.TYPE, Font.class);
		assertPublicMethod(UtilityTimerModule.class, "height", Integer.TYPE, Font.class);
		assertPublicMethod(UtilityTimerModule.class, "visible", Boolean.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "previewMode", String.class);
		assertPublicMethod(UtilityTimerModule.class, "render", Void.TYPE,
			GuiGraphicsExtractor.class, Font.class, Integer.TYPE, Integer.TYPE);
		assertPublicMethod(UtilityTimerModule.class, "renderPreview", Void.TYPE,
			GuiGraphicsExtractor.class, Font.class, Integer.TYPE, Integer.TYPE);

		UtilityTimerModule module = UtilityTimerModule.INSTANCE;
		check("utility_timer".equals(module.id()) && "Timer".equals(module.displayName()),
			"the timer keeps its HUD identity");
		check(module.state() == module.state(), "the state() interop accessor returns the live timer state");
		check(module.previewMode().equals("sample"), "an unconfigured timer uses the fixed editor sample");
	}

	private static void checkTimerTransitionsAndCompletionRouting() {
		CountdownTimerState timer = new CountdownTimerState();
		check("No timer set".equals(timer.status(0L)), "an unconfigured timer keeps its status message");
		timer.setSeconds(2L, 0L);
		check("Timer 2s (running)".equals(timer.status(0L)), "a new countdown reports its formatted running status");
		check(timer.remainingSeconds(999_999_999L) == 2L && timer.remainingSeconds(1_000_000_000L) == 1L,
			"countdown display rounds any remaining fraction up to the next second");
		check(timer.pause(1_000_000_000L) && !timer.isRunning()
			&& timer.remainingSeconds(9_000_000_000L) == 1L
			&& "Timer 1s (paused)".equals(timer.status(9_000_000_000L)),
			"pause freezes the remaining monotonic duration");
		check(!timer.tick(9_000_000_000L), "a paused timer does not complete");
		check(timer.resume(5_000_000_000L) && timer.isRunning(), "resume restarts the saved duration");
		check(!timer.tick(5_999_999_999L) && timer.tick(6_000_000_000L)
			&& !timer.tick(7_000_000_000L) && timer.isComplete()
			&& "Timer 0s (complete)".equals(timer.status(7_000_000_000L)),
			"completion fires exactly once when the monotonic countdown reaches zero");
		check(timer.reset() && !timer.isRunning() && !timer.isComplete()
			&& timer.remainingSeconds(8_000_000_000L) == 2L
			&& "Timer 2s (paused)".equals(timer.status(8_000_000_000L)),
			"reset restores the original duration in a paused state");

		TimerCompletionPresentation presentation = TimerCompletionPresentation.resolve(
			true, true, true, true, "  Custom done  ", 900);
		check(presentation.showChat() && presentation.showTitle() && presentation.playSound()
			&& "Custom done".equals(presentation.message())
			&& presentation.titleDurationTicks() == TimerCompletionPresentation.MAX_TITLE_DURATION_TICKS,
			"completion keeps all selected output routes, trims visible text, and clamps title duration");
		TimerCompletionPresentation disabled = TimerCompletionPresentation.resolve(
			false, true, true, true, "Custom done", 40);
		check(!disabled.showChat() && !disabled.showTitle() && !disabled.playSound(),
			"the master completion alert switch suppresses every output route");
	}

	private static void assertNames(List<? extends Setting> settings, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : settings) names.add(setting.name());
		check(names.equals(expected), label + " changed: " + names);
	}

	private static void assertStaticField(Class<?> owner, String name, Class<?> type) {
		try {
			Field field = owner.getField(name);
			check(Modifier.isStatic(field.getModifiers()) && field.getType() == type,
				"Java field " + name + " remains public static with its original type");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Java field is missing: " + name, exception);
		}
	}

	private static void assertPublicMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
		try {
			Method method = owner.getMethod(name, parameters);
			check(method.getReturnType() == result, "Java method signature changed: " + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Java method is missing: " + name, exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
