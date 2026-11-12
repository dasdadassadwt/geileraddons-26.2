package geiler.addons.client.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Direct Java interop and ModConfig round-trip checks for the Kotlin-backed module. */
public final class InventoryButtonsModuleKotlinChecks {
	private InventoryButtonsModuleKotlinChecks() { }

	public static void run() throws Exception {
		checkJavaAbi();
		InventoryButtonsModule module = InventoryButtonsModule.INSTANCE;
		List<InventoryButtonPlacement> original = module.placements();
		boolean wasEnabled = module.isEnabled();
		try {
			checkModuleActionsAndSnapshots(module);
			checkRestoreAndMutationSemantics(module);
			checkIconPathValidation(module);
			checkModConfigRoundTrip(module);
		} finally {
			module.restore(original);
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkJavaAbi() throws ReflectiveOperationException {
		check(Modifier.isPublic(InventoryButtonsModule.class.getModifiers())
			&& Modifier.isFinal(InventoryButtonsModule.class.getModifiers()),
			"the registered module keeps its public final class boundary");
		Field instance = InventoryButtonsModule.class.getField("INSTANCE");
		check(Modifier.isPublic(instance.getModifiers()) && Modifier.isStatic(instance.getModifiers())
			&& Modifier.isFinal(instance.getModifiers())
			&& instance.getType() == InventoryButtonsModule.class,
			"Java callers retain the public static final InventoryButtonsModule.INSTANCE field");
		check(instance.get(null) == InventoryButtonsModule.INSTANCE,
			"the Java-visible singleton field resolves to the registered module instance");

		checkMethod("placements", List.class);
		checkMethod("placement", InventoryButtonPlacement.class, int.class);
		checkMethod("create", InventoryButtonPlacement.class, int.class, int.class, int.class,
			InventoryButtonPlacement.Appearance.class, String.class);
		checkMethod("createTextAction", InventoryButtonPlacement.class, String.class, int.class, int.class,
			InventoryButtonPlacement.Appearance.class, String.class);
		checkMethod("restore", void.class, List.class);
		checkMethod("remove", void.class, int.class);
		checkMethod("unassignMacro", void.class, int.class);
		checkMethod("isPngIconAllowed", boolean.class, String.class);
		checkMethod("iconDirectory", Path.class);
		checkMethod("groups", List.class);
		checkMethod("tick", void.class);
	}

	private static void checkMethod(String name, Class<?> returnType, Class<?>... parameters)
		throws ReflectiveOperationException {
		Method method = InventoryButtonsModule.class.getMethod(name, parameters);
		check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()),
			"Java ABI keeps instance method " + name + " with return type " + returnType.getSimpleName());
	}

	private static void checkModuleActionsAndSnapshots(InventoryButtonsModule module) {
		check(module.name().equals("Inventory Buttons") && module.category() == Category.VISUAL,
			"module keeps its registered display name and category");
		check(!module.defaultEnabled(), "the module remains disabled on a fresh install");
		check(module.hasSettings() && module.actions().size() == 1
			&& module.actions().getFirst().label().equals("Edit Inventory Layout"),
			"the original edit-layout module action stays in the Java-visible settings list");

		module.restore(List.of());
		InventoryButtonPlacement first = module.create(17, 3, -2,
			InventoryButtonPlacement.Appearance.TEXT, "Craft");
		check(first.id() == 0, "new placement IDs start at zero after an empty restore");
		List<InventoryButtonPlacement> snapshot = module.placements();
		InventoryButtonPlacement second = module.createTextAction("hello party", 4, -2,
			InventoryButtonPlacement.Appearance.TEXT, "Party");
		check(second.id() == 1, "create and createTextAction share one sequential placement ID source");
		check(snapshot.size() == 1 && snapshot.getFirst() == first,
			"placements returns a point-in-time list snapshot while retaining its placement objects");
		boolean immutable = false;
		try {
			snapshot.clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(immutable, "the placement snapshot remains immutable to Java callers");

		List<SettingGroup> groups = module.groups();
		check(groups.size() == 3, "the settings panel keeps one layout row plus one group per placement");
		check(groups.getFirst().settings().getFirst() instanceof ModuleAction action
			&& action.label().equals("Edit Layout · 2 buttons"),
			"the layout group keeps its live placement count and action label");
		SettingGroup buttonGroup = groups.get(1);
		check(buttonGroup.stateKey().equals("inventory-button:" + first.id())
			&& buttonGroup.collapsedByDefault(), "placement groups keep stable keys and collapsed defaults");
		List<Setting> actions = buttonGroup.settings();
		check(actions.size() == 7
			&& ((ModuleAction) actions.get(0)).label().equals("Edit in Inventory")
			&& ((ModuleAction) actions.get(1)).label().equals("Choose Macro")
			&& ((ModuleAction) actions.get(2)).label().equals("Set Text Action")
			&& ((ModuleAction) actions.get(3)).label().equals("Clear Text Action")
			&& ((ModuleAction) actions.get(4)).label().equals("Set Hover Tooltip")
			&& ((ModuleAction) actions.get(5)).label().equals("Unassign Macro")
			&& ((ModuleAction) actions.get(6)).label().equals("Delete Button"),
			"placement action names and order remain compatible with the existing settings UI");
	}

	private static void checkRestoreAndMutationSemantics(InventoryButtonsModule module) {
		InventoryButtonPlacement source = new InventoryButtonPlacement(9);
		source.setMacroId(23);
		source.setGrid(4, 2);
		InventoryButtonPlacement duplicate = new InventoryButtonPlacement(9);
		InventoryButtonPlacement other = new InventoryButtonPlacement(12);
		other.setMacroId(23);
		other.setTextAction("plain chat");
		module.restore(Arrays.asList(source, null, duplicate, other));
		check(module.placements().size() == 2 && module.placement(9) != source,
			"restore skips null and duplicate IDs and copies accepted placements");
		source.setGrid(30, 40);
		check(module.placement(9).gridX() == 4 && module.placement(9).gridY() == 2,
			"later edits to the caller-owned restore source do not mutate module state");
		InventoryButtonPlacement next = module.create(-1, 0, 0,
			InventoryButtonPlacement.Appearance.ITEM, "minecraft:stone");
		check(next.id() == 13, "create resumes IDs above the highest restored placement");

		module.unassignMacro(23);
		check(module.placement(9).macroId() == -1 && module.placement(12).macroId() == -1
			&& module.placement(12).hasTextAction(),
			"macro deletion unassigns every matching placement while preserving its direct text action");
		module.remove(9);
		module.remove(9);
		check(module.placement(9) == null && module.placements().size() == 2,
			"remove deletes a placement and remains harmless when repeated");
	}

	private static void checkIconPathValidation(InventoryButtonsModule module) {
		check(module.isPngIconAllowed("icon.PNG"), "PNG extension validation remains case-insensitive");
		check(!module.isPngIconAllowed(null) && !module.isPngIconAllowed(" "),
			"empty icon names remain rejected");
		check(!module.isPngIconAllowed("../icon.png") && !module.isPngIconAllowed("nested/icon.png"),
			"icon names cannot escape the dedicated directory or name nested paths");
		check(!module.isPngIconAllowed(FabricLoader.getInstance().getConfigDir()
			.resolve("outside.png").toString()), "absolute icon paths remain rejected");
		check(module.iconDirectory().endsWith(Path.of("geileraddons", "inventory-button-icons")),
			"the dedicated inventory icon directory keeps its existing config-relative path");
	}

	private static void checkModConfigRoundTrip(InventoryButtonsModule module) throws Exception {
		module.restore(List.of());
		InventoryButtonPlacement saved = module.createTextAction("hello party", 2, -1,
			InventoryButtonPlacement.Appearance.TEXT, "Party");
		saved.setHoverTooltip("Open party action");
		ModConfig.markDirty();
		check(ModConfig.flushNow(), "ModConfig flushes the test placement to its isolated config path");

		Path configPath = FabricLoader.getInstance().getConfigDir()
			.resolve("geileraddons").resolve("config.json");
		JsonObject root = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
		JsonArray savedButtons = root.getAsJsonArray("inventoryButtons");
		check(savedButtons != null && savedButtons.size() == 1,
			"ModConfig persists Inventory Buttons through the existing inventoryButtons config field");
		JsonObject savedButton = savedButtons.get(0).getAsJsonObject();
		check(savedButton.get("id").getAsInt() == saved.id()
			&& savedButton.get("macroId").getAsInt() == -1
			&& savedButton.get("gridX").getAsInt() == 2
			&& savedButton.get("gridY").getAsInt() == -1
			&& savedButton.get("slotAligned").getAsBoolean()
			&& savedButton.get("appearance").getAsString().equals("TEXT")
			&& savedButton.get("value").getAsString().equals("Party")
			&& savedButton.get("hoverTooltip").getAsString().equals("Open party action")
			&& savedButton.get("textAction").getAsString().equals("hello party"),
			"the config payload keeps placement, appearance, tooltip, and direct-action fields");

		module.restore(List.of());
		ModConfig.load();
		InventoryButtonPlacement restored = module.placement(saved.id());
		check(restored != null && restored.macroId() == -1 && restored.gridX() == 2 && restored.gridY() == -1
			&& restored.slotAligned() && restored.appearance() == InventoryButtonPlacement.Appearance.TEXT
			&& restored.value().equals("Party") && restored.hoverTooltip().equals("Open party action")
			&& restored.textAction().equals("hello party"),
			"ModConfig reload restores the same button without changing the JSON format");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
