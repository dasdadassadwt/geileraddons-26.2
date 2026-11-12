package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Direct regression checks for Blood Camp's Java-facing module contract and prediction timing. */
public final class DungeonBloodCampModuleChecks {
	private DungeonBloodCampModuleChecks() { }

	public static void run() {
		checkWatcherOpeners();
		DungeonBloodCampModule module = DungeonBloodCampModule.INSTANCE;
		checkJavaApi(module);
		checkIdentityAndSettings(module);
		checkEnableLifecycle(module);
		checkVisibilityDependencies(module);
		checkMovePredictionBoundaries(module);
		checkTimerLabelColorBoundaries(module);
	}

	private static void checkEnableLifecycle(DungeonBloodCampModule module) {
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(false);
			check(!module.isEnabled(), "Blood Camp can be disabled through the shared module lifecycle");
			module.setEnabled(true);
			check(module.isEnabled(), "Blood Camp can be enabled through the shared module lifecycle");
			module.setEnabled(false);
			check(!module.isEnabled(), "disabling Blood Camp clears its enabled state");
		} finally {
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkJavaApi(DungeonBloodCampModule module) {
		try {
			Field instance = DungeonBloodCampModule.class.getField("INSTANCE");
			int modifiers = instance.getModifiers();
			check(instance.getType() == DungeonBloodCampModule.class
				&& Modifier.isPublic(modifiers)
				&& Modifier.isStatic(modifiers)
				&& Modifier.isFinal(modifiers)
				&& instance.get(null) == module,
				"the Kotlin module keeps the public static final singleton field used by Java callers");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the Kotlin module exposes its Java singleton", error);
		}

		requireMethod("bloodCampAssistSetting");
		requireMethod("positionColorSetting");
		requireMethod("spawnColorSetting");
		requireMethod("boxSizeSetting");
		requireMethod("lineSetting");
		requireMethod("timeLeftSetting");
		requireMethod("tick", Minecraft.class, long.class, long.class);
		requireMethod("onChatMessage", String.class, long.class, long.class);
		requireMethod("onHeadEquipment", Entity.class, EquipmentSlot.class, ItemStack.class);
		requireMethod("onEntityMove", Entity.class);
		requireMethod("onEntityRemoved", int.class);
		requireMethod("onWorldChanged", String.class);
		requireMethod("applyWatcherBossBar", BossEvent.class);
		requireMethod("render", LevelRenderContext.class);
		requireMethod("drawPreview", GuiGraphicsExtractor.class, Font.class,
			int.class, int.class, int.class, int.class);
		requireMethod("previewLines", Font.class, int.class);
	}

	private static void checkIdentityAndSettings(DungeonBloodCampModule module) {
		check(module.name().equals("Blood Camp")
			&& module.configName().equals("Blood Camp")
			&& module.description().equals("Predicts Blood Camp mob spawn positions and the Watcher's move timing.")
			&& module.category() == Category.F7
			&& !module.defaultEnabled(),
			"module display name, saved config ID, description, category, and fresh default are preserved");
		check(ModuleManager.modules().stream().filter(registered -> registered == module).count() == 1L,
			"the original singleton remains registered exactly once");
		check(module.booleanSettings().stream().map(Setting::name).toList().equals(List.of(
			"Prediction Dropdown", "Move Prediction", "Move Message", "Party Move Message", "Kill Title",
			"Blood Assist Dropdown", "Blood Camp Assist", "Line", "Time Left", "Advanced", "Interpolation",
			"Ping Offset", "Watcher Bar")),
			"boolean setting IDs and persistence order are preserved");
		check(module.colorSettings().stream().map(Setting::name).toList().equals(
			List.of("Spawn Color", "Final Color", "Position Color")),
			"color setting IDs and persistence order are preserved");
		check(module.numberSettings().stream().map(Setting::name).toList().equals(
			List.of("Box Size", "Offset", "Tick", "Mob Box Offset")),
			"numeric setting IDs and persistence order are preserved");

		check(bool(module, "Prediction Dropdown").rawValue()
			&& bool(module, "Move Prediction").rawValue()
			&& bool(module, "Move Message").rawValue()
			&& !bool(module, "Party Move Message").rawValue()
			&& bool(module, "Kill Title").rawValue()
			&& bool(module, "Blood Assist Dropdown").rawValue()
			&& bool(module, "Blood Camp Assist").rawValue()
			&& bool(module, "Line").rawValue()
			&& bool(module, "Time Left").rawValue()
			&& !bool(module, "Advanced").rawValue()
			&& bool(module, "Interpolation").rawValue()
			&& bool(module, "Ping Offset").rawValue()
			&& bool(module, "Watcher Bar").rawValue(),
			"all boolean setting defaults are preserved");

		NumberSetting boxSize = module.boxSizeSetting();
		NumberSetting offset = number(module, "Offset");
		NumberSetting spawnTick = number(module, "Tick");
		NumberSetting manualOffset = number(module, "Mob Box Offset");
		check(boxSize.value() == 1.0f && boxSize.min() == 0.1f && boxSize.max() == 1.0f
			&& offset.value() == 40.0f && offset.min() == -100.0f && offset.max() == 100.0f
			&& spawnTick.value() == 38.0f && spawnTick.min() == 35.0f && spawnTick.max() == 41.0f
			&& manualOffset.value() == 0.0f && manualOffset.min() == 0.0f && manualOffset.max() == 300.0f,
			"numeric setting defaults and bounds are preserved");
		ColorSetting spawn = module.spawnColorSetting();
		ColorSetting position = module.positionColorSetting();
		ColorSetting finalColor = color(module, "Final Color");
		check(spawn.red() == 255 && spawn.green() == 85 && spawn.blue() == 85 && spawn.alpha() == 255
			&& finalColor.red() == 0 && finalColor.green() == 170 && finalColor.blue() == 170
			&& finalColor.alpha() == 255 && position.red() == 85 && position.green() == 255
			&& position.blue() == 85 && position.alpha() == 255,
			"all marker color defaults are preserved");

		List<SettingGroup> groups = module.groups();
		check(groups.size() == 3
			&& "Prediction".equals(groups.get(0).name())
			&& !groups.get(0).collapsedByDefault()
			&& names(groups.get(0).settings()).equals(List.of(
				"Move Prediction", "Move Message", "Party Move Message", "Kill Title"))
			&& "Blood Camp Assist".equals(groups.get(1).name())
			&& !groups.get(1).collapsedByDefault()
			&& groups.get(0).toggle() == bool(module, "Prediction Dropdown")
			&& groups.get(1).toggle() == bool(module, "Blood Assist Dropdown")
			&& names(groups.get(1).settings()).equals(List.of(
				"Blood Camp Assist", "Position Color", "Spawn Color", "Final Color", "Box Size", "Line", "Time Left"))
			&& groups.get(1).children().size() == 1
			&& "Advanced".equals(groups.get(1).children().get(0).name())
			&& groups.get(1).children().get(0).collapsedByDefault()
			&& groups.get(1).children().get(0).toggle() == bool(module, "Advanced")
			&& names(groups.get(1).children().get(0).settings()).equals(
				List.of("Offset", "Tick", "Interpolation", "Ping Offset", "Mob Box Offset"))
			&& groups.get(2).name() == null
			&& names(groups.get(2).settings()).equals(List.of("Watcher Bar")),
			"settings stay in their original groups and advanced subgroup");
	}

	private static void checkVisibilityDependencies(DungeonBloodCampModule module) {
		BooleanSetting predictionDropdown = bool(module, "Prediction Dropdown");
		BooleanSetting movePrediction = bool(module, "Move Prediction");
		BooleanSetting bloodAssistDropdown = bool(module, "Blood Assist Dropdown");
		BooleanSetting bloodAssist = module.bloodCampAssistSetting();
		BooleanSetting advanced = bool(module, "Advanced");
		BooleanSetting pingOffset = bool(module, "Ping Offset");
		boolean originalPredictionDropdown = predictionDropdown.rawValue();
		boolean originalMovePrediction = movePrediction.rawValue();
		boolean originalBloodAssistDropdown = bloodAssistDropdown.rawValue();
		boolean originalBloodAssist = bloodAssist.rawValue();
		boolean originalAdvanced = advanced.rawValue();
		boolean originalPingOffset = pingOffset.rawValue();
		try {
			check(module.isSettingVisible(movePrediction)
				&& module.isSettingVisible(bool(module, "Move Message"))
				&& module.isSettingVisible(bloodAssist)
				&& module.isSettingVisible(module.positionColorSetting())
				&& module.isSettingVisible(bool(module, "Advanced"))
				&& !module.isSettingVisible(number(module, "Offset"))
				&& module.isSettingVisible(bool(module, "Watcher Bar")),
				"default settings visibility follows the original dropdowns");

			predictionDropdown.setValue(false);
			check(module.isSettingVisible(predictionDropdown)
				&& !module.isSettingVisible(movePrediction)
				&& !module.isSettingVisible(bool(module, "Move Message")),
				"closing Prediction hides its dependent rows while keeping the dropdown visible");
			predictionDropdown.setValue(true);
			movePrediction.setValue(false);
			check(!module.isSettingVisible(bool(module, "Move Message"))
				&& !module.isSettingVisible(bool(module, "Party Move Message"))
				&& !module.isSettingVisible(bool(module, "Kill Title")),
				"disabling Move Prediction hides all prediction outputs");
			movePrediction.setValue(true);

			bloodAssistDropdown.setValue(false);
			check(!module.isSettingVisible(bloodAssist)
				&& !module.isSettingVisible(module.positionColorSetting())
				&& module.isSettingVisible(bool(module, "Watcher Bar")),
				"closing the assist dropdown hides assist rows but leaves Watcher Bar independent");
			bloodAssistDropdown.setValue(true);
			bloodAssist.setValue(false);
			check(!module.isSettingVisible(module.positionColorSetting())
				&& !module.isSettingVisible(bool(module, "Advanced")),
				"disabling assist hides its presentation settings");
			bloodAssist.setValue(true);
			advanced.setValue(true);
			check(module.isSettingVisible(number(module, "Offset"))
				&& module.isSettingVisible(number(module, "Tick"))
				&& module.isSettingVisible(bool(module, "Interpolation"))
				&& module.isSettingVisible(pingOffset)
				&& !module.isSettingVisible(number(module, "Mob Box Offset")),
				"advanced settings show while manual offset remains gated by Ping Offset");
			pingOffset.setValue(false);
			check(module.isSettingVisible(number(module, "Mob Box Offset")),
				"manual offset appears when ping compensation is disabled");
		} finally {
			predictionDropdown.setValue(originalPredictionDropdown);
			movePrediction.setValue(originalMovePrediction);
			bloodAssistDropdown.setValue(originalBloodAssistDropdown);
			bloodAssist.setValue(originalBloodAssist);
			advanced.setValue(originalAdvanced);
			pingOffset.setValue(originalPingOffset);
		}
	}

	private static void checkMovePredictionBoundaries(DungeonBloodCampModule module) {
		check(predictedTicks(module, -100L) == 1 && predictedTicks(module, 0L) == 3,
			"fallback prediction clamps nonpositive elapsed time to the existing lower bound");
		check(predictedTicks(module, 1L) == 24 && predictedTicks(module, 21L) == 24
			&& predictedTicks(module, 22L) == 27 && predictedTicks(module, 24L) == 27,
			"the 1-24 second prediction windows retain their inclusive/exclusive edges");
		check(predictedTicks(module, 25L) == 30 && predictedTicks(module, 27L) == 30
			&& predictedTicks(module, 28L) == 33 && predictedTicks(module, 30L) == 33
			&& predictedTicks(module, 31L) == 36 && predictedTicks(module, 33L) == 36,
			"the 25-33 second prediction windows retain their boundaries");
		check(predictedTicks(module, 34L) == 37 && predictedTicks(module, 1_197L) == 1_200
			&& predictedTicks(module, 20_000L) == 1_200,
			"fallback prediction and its upper clamp remain intact");
	}

	private static void checkTimerLabelColorBoundaries(DungeonBloodCampModule module) {
		check(timeColor(module, 1.5001f) == 0xFF55FF55
			&& timeColor(module, 1.5f) == 0xFFFFAA00
			&& timeColor(module, 0.5f) == 0xFFFFAA00
			&& timeColor(module, 0.0f) == 0xFFFF5555
			&& timeColor(module, -0.001f) == 0xFF55FFFF,
			"rendered timer labels retain green, amber, red, and expired-color boundaries");
	}

	private static int timeColor(DungeonBloodCampModule module, float seconds) {
		try {
			Method method = DungeonBloodCampModule.class.getDeclaredMethod("timeColor", float.class);
			method.setAccessible(true);
			return (int) method.invoke(module, seconds);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the rendered timer color mapping remains directly checkable", error);
		}
	}

	private static int predictedTicks(DungeonBloodCampModule module, long elapsedSeconds) {
		try {
			Method method = DungeonBloodCampModule.class.getDeclaredMethod("movePredictionTicks", long.class);
			method.setAccessible(true);
			return (int) method.invoke(module, elapsedSeconds);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the move prediction boundary helper remains directly checkable", error);
		}
	}

	private static void requireMethod(String name, Class<?>... parameterTypes) {
		try {
			Method method = DungeonBloodCampModule.class.getMethod(name, parameterTypes);
			check(method.getDeclaringClass() == DungeonBloodCampModule.class,
				"Java caller method remains declared on the module: " + name);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("missing Java-facing Blood Camp method: " + name, missing);
		}
	}

	private static BooleanSetting bool(DungeonBloodCampModule module, String name) {
		return module.booleanSettings().stream()
			.filter(setting -> setting.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing boolean setting: " + name));
	}

	private static NumberSetting number(DungeonBloodCampModule module, String name) {
		return module.numberSettings().stream()
			.filter(setting -> setting.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing numeric setting: " + name));
	}

	private static ColorSetting color(DungeonBloodCampModule module, String name) {
		return module.colorSettings().stream()
			.filter(setting -> setting.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing color setting: " + name));
	}

	private static List<String> names(List<Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("DungeonBloodCampModuleChecks: " + message);
	}

	/** Every Watcher opening line must be recognised, otherwise the move countdown never starts. */
	private static void checkWatcherOpeners() {
		String[] lines = {
			"Congratulations, you made it through the Entrance.", "Ah, you've finally arrived.", "Ah, we meet again...",
			"So you made it this far... interesting.", "You've managed to scratch and claw your way here, eh?",
			"I'm starting to get tired of seeing you around here...", "Oh.. hello?",
			"Things feel a little more roomy now, eh?", "Ah, we meet again. As I foresaw..." };
		for (String line : lines) {
			if (!DungeonBloodCampModule.isWatcherOpenLine("[BOSS] The Watcher: " + line)) {
				throw new AssertionError("Watcher opener not recognised: " + line);
			}
		}
		if (DungeonBloodCampModule.isWatcherOpenLine("[BOSS] The Watcher: Let's see how you can handle this.")) {
			throw new AssertionError("the move line is not an opener");
		}
	}
}
