package geiler.addons.client.module.impl;

import geiler.addons.client.chat.ChatStringSemantics;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Focused parity checks for the Java-facing Key Pickup module and its persisted setting surface. */
public final class DungeonKeyPickupModuleChecks {
	private DungeonKeyPickupModuleChecks() { }

	public static void run() {
		checkIdentityAndJavaEntrypoints();
		checkDisabledLifecycle();
		checkSettingsAndGroups();
		checkSoundSettingTrimParity();
	}

	private static void checkIdentityAndJavaEntrypoints() {
		DungeonKeyPickupModule module = DungeonKeyPickupModule.INSTANCE;
		if (module == null || module.name() == null
			|| !module.name().equals("Key Pickup")
			|| !module.configName().equals("Dungeon Key Pickup")
			|| !module.description().equals("Highlights dropped dungeon keys and alerts on confirmed pickups.")
			|| module.category() != Category.F7 || module.defaultEnabled()) {
			throw new AssertionError("Key Pickup identity, persisted key, or fresh-install state changed");
		}

		try {
			Field instance = DungeonKeyPickupModule.class.getField("INSTANCE");
			if (!Modifier.isStatic(instance.getModifiers()) || instance.get(null) != module) {
				throw new AssertionError("The Java-visible INSTANCE field is not the registered singleton");
			}
			DungeonKeyPickupModule.class.getMethod("tick");
			DungeonKeyPickupModule.class.getMethod("onChatMessage", String.class);
			DungeonKeyPickupModule.class.getMethod("onEntityMetadataUpdated", int.class);
			DungeonKeyPickupModule.class.getMethod("onItemPickupPacket", ClientboundTakeItemEntityPacket.class);
			DungeonKeyPickupModule.class.getMethod("render", LevelRenderContext.class);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("A GeilerAddonsClient or Mixin entrypoint is missing from the Kotlin module", error);
		}
	}

	private static void checkDisabledLifecycle() {
		DungeonKeyPickupModule module = DungeonKeyPickupModule.INSTANCE;
		if (module.isEnabled() || module.isActive() || module.inactiveReason() != null) {
			throw new AssertionError("A fresh disabled Key Pickup module must remain inactive without an idle reason");
		}
	}

	private static void checkSettingsAndGroups() {
		Module module = DungeonKeyPickupModule.INSTANCE;
		List<BooleanSetting> booleans = module.booleanSettings();
		assertNames(booleans, List.of(
			"Highlight Dropped Keys",
			"Trace Dropped Keys",
			"Alert on Key Pickup",
			"Alert on Local Pickup",
			"Alert on Party Pickup",
			"Alert on Unattributed Pickup",
			"Alert on Confirmed Pickup Messages",
			"Alert on Newly Spawned Keys",
			"Draw Through Walls",
			"Show Alert Title",
			"Play Alert Sound"
		), "boolean setting IDs");
		for (BooleanSetting setting : booleans) {
			if (!setting.value()) throw new AssertionError("Key Pickup boolean default changed: " + setting.name());
		}

		List<NumberSetting> numbers = module.numberSettings();
		assertNames(numbers, List.of("Key Tracer Width", "Key Highlight Range", "Alert Volume", "Alert Pitch"),
			"number setting IDs");
		assertNumber(numbers.get(0), 1.5f, 0.5f, 5.0f, false, "tracer width");
		assertNumber(numbers.get(1), 64.0f, 8.0f, 128.0f, true, "highlight range");
		assertNumber(numbers.get(2), 0.8f, 0.0f, 1.0f, false, "alert volume");
		assertNumber(numbers.get(3), 1.0f, 0.1f, 2.0f, false, "alert pitch");

		List<TextSetting> texts = module.textSettings();
		assertNames(texts, List.of("Alert Sound"), "text setting IDs");
		if (!texts.get(0).value().equals("entity.experience_orb.pickup") || texts.get(0).maxLength() != 96) {
			throw new AssertionError("Alert sound setting default or length changed");
		}

		List<ColorSetting> colors = module.colorSettings();
		assertNames(colors, List.of("Wither Key Color", "Blood Key Color"), "color setting IDs");
		assertColor(colors.get(0), 255, 75, 75, "Wither key color");
		assertColor(colors.get(1), 255, 55, 55, "Blood key color");

		List<SettingGroup> groups = module.groups();
		if (groups.size() != 2 || !"Dropped Keys".equals(groups.get(0).name()) ||
			!"Alerts".equals(groups.get(1).name()) || groups.get(0).toggle() != booleans.get(0) ||
			groups.get(1).toggle() != booleans.get(2)) {
			throw new AssertionError("Key Pickup setting group names or section toggles changed");
		}
		assertNames(groups.get(0).settings(), List.of(
			"Trace Dropped Keys", "Key Tracer Width", "Key Highlight Range", "Draw Through Walls",
			"Wither Key Color", "Blood Key Color"
		), "Dropped Keys group rows");
		assertNames(groups.get(1).settings(), List.of(
			"Alert on Local Pickup", "Alert on Party Pickup", "Alert on Unattributed Pickup",
			"Alert on Confirmed Pickup Messages", "Alert on Newly Spawned Keys", "Show Alert Title",
			"Play Alert Sound", "Alert Sound", "Alert Volume", "Alert Pitch"
		), "Alerts group rows");
	}

	private static void checkSoundSettingTrimParity() {
		Identifier controlPadded = Identifier.tryParse(ChatStringSemantics.INSTANCE.trim("\u0001entity.experience_orb.pickup\u0001"));
		Identifier unicodePadded = Identifier.tryParse(ChatStringSemantics.INSTANCE.trim("\u2003entity.experience_orb.pickup"));
		Identifier expected = Identifier.tryParse("entity.experience_orb.pickup");
		if (expected == null || !expected.equals(controlPadded) ||
			unicodePadded != null) {
			throw new AssertionError("Key Pickup sound IDs no longer use Java String.trim semantics");
		}
	}

	private static void assertNumber(NumberSetting setting, float value, float minimum, float maximum,
		boolean integer, String label) {
		if (Float.compare(setting.value(), value) != 0 || Float.compare(setting.min(), minimum) != 0 ||
			Float.compare(setting.max(), maximum) != 0 || setting.isInteger() != integer) {
			throw new AssertionError(label + " default or bounds changed");
		}
	}

	private static void assertColor(ColorSetting setting, int red, int green, int blue, String label) {
		if (setting.red() != red || setting.green() != green || setting.blue() != blue || setting.alpha() != 255) {
			throw new AssertionError(label + " default changed");
		}
	}

	private static void assertNames(List<? extends Setting> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : actual) names.add(setting.name());
		if (!names.equals(expected)) throw new AssertionError(label + " changed: " + names);
	}
}
