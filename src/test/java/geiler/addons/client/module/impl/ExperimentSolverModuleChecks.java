package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Direct checks for the Solver module's frozen settings and Java interoperability contract. */
public final class ExperimentSolverModuleChecks {
	private ExperimentSolverModuleChecks() {
	}

	public static void run() {
		checkScaledSlotOffsets();
		checkStatusTextTrimParity();
		checkBlankTextParity();
		checkIdentityAndSettings();
		checkSelectionAndPreviewSettings();
		checkJavaInteropSurface();
		checkControllerInteropSurface();
	}

	private static void checkStatusTextTrimParity() {
		String emSpace = "\u2003";
		try {
			Class<?> statusInfo = Class.forName(ExperimentSolverModule.Session.class.getName() + "$StatusInfo");
			var constructor = statusInfo.getDeclaredConstructor(String.class, int.class);
			constructor.setAccessible(true);
			Object value = constructor.newInstance(emSpace + "Timer: 3s" + emSpace, 3);
			Field text = statusInfo.getDeclaredField("text");
			text.setAccessible(true);
			String normalized = (String) text.get(value);
			check((emSpace + "Timer: 3s" + emSpace).equals(normalized)
				&& geiler.addons.client.enchanting.ExperimentPhase.detect(
					ExperimentType.ULTRASEQUENCER, normalized)
					== geiler.addons.client.enchanting.ExperimentPhase.WAITING,
				"status text preserves Java String.trim semantics before phase detection");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Experiment status normalization changed", exception);
		}
	}

	private static void checkBlankTextParity() {
		try {
			Class<?> semantics = Class.forName("geiler.addons.client.chat.ChatStringSemantics");
			Object instance = semantics.getField("INSTANCE").get(null);
			Method isBlank = null;
			for (Method candidate : semantics.getDeclaredMethods()) {
				Class<?>[] parameterTypes = candidate.getParameterTypes();
				if (candidate.getName().startsWith("isBlank") && parameterTypes.length == 1
					&& parameterTypes[0] == String.class) {
					isBlank = candidate;
					break;
				}
			}
			if (isBlank == null) throw new NoSuchMethodException("ChatStringSemantics.isBlank(String)");
			isBlank.setAccessible(true);
			check(Boolean.TRUE.equals(isBlank.invoke(instance, " \t\n")),
				"ASCII whitespace remains blank under Java String.isBlank semantics");
			check(Boolean.TRUE.equals(isBlank.invoke(instance, "\u2003")),
				"Unicode whitespace remains blank under Java String.isBlank semantics");
			check(Boolean.FALSE.equals(isBlank.invoke(instance, "\u00a0")),
				"non-breaking space remains non-blank under Java String.isBlank semantics");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Experiment text blankness semantics changed", exception);
		}
	}

	private static void checkScaledSlotOffsets() {
		check(ExperimentSolverModule.scaledSlotOffset(0, 3, 1.5f) == 3,
			"the first slot inset scales with the panel layout");
		check(ExperimentSolverModule.scaledSlotOffset(2, 3, 1.5f) == 84,
			"a later slot offset scales both panel padding and the full slot stride");
	}

	private static void checkIdentityAndSettings() {
		ExperimentSolverModule module = ExperimentSolverModule.INSTANCE;
		check(module == ExperimentSolverModule.INSTANCE, "the singleton module identity is stable");
		check("Experimentation Solver".equals(module.name()) && "Solver".equals(module.configName()),
			"the display name and persisted module key remain unchanged");
		check("Solves Experimentation Table games.".equals(module.description())
			&& module.category() == Category.ENCHANTING && !module.defaultEnabled() && !module.isEnabled(),
			"the module keeps its description, category, and disabled fresh-install state");

		check(names(module.booleanSettings()).equals(List.of("Chronomatron", "Ultrasequencer", "Superpairs",
			"Hide Text Above Board", "Show Superpairs Clicks Remaining", "Prevent Misclicks",
			"Complete Sounds", "Max Click Alert")), "boolean config keys and order remain stable");
		check(names(module.numberSettings()).equals(List.of("Chronomatron Future Clicks",
			"Ultrasequencer Future Clicks", "Serums Consumed", "Term Size", "Roundness", "Slot Gap",
			"Complete Sound Pitch", "Complete Sound Volume")), "number config keys and order remain stable");
		check(names(module.textSettings()).equals(List.of("Complete Sound")), "sound config key remains stable");
		check(names(module.colorSettings()).equals(List.of("Panel Color", "Text Color", "Current Color", "Next Color",
			"Next Next Color", "Next Next Next Color", "Discovered Color", "Unknown Color")),
			"color config keys and order remain stable");
		check("Size".equals(module.numberSettings().get(3).displayName()),
			"the historical Term Size persistence key keeps its Size label");

		check(values(module.booleanSettings()).equals(List.of(true, true, true, false, true, true, false, true)),
			"experiment, protection, display, and sound toggle defaults are unchanged");
		List<NumberSetting> numbers = module.numberSettings();
		check(numbers.get(0).intValue() == 3 && numbers.get(1).intValue() == 3
			&& numbers.get(2).intValue() == 0 && Float.compare(numbers.get(3).value(), 1.4978355f) == 0
			&& numbers.get(4).intValue() == 0 && numbers.get(5).intValue() == 0
			&& Float.compare(numbers.get(6).value(), 1f) == 0 && Float.compare(numbers.get(7).value(), 1f) == 0,
			"preview, serum, geometry, and sound setting defaults are unchanged");
		TextSetting completeSound = module.textSettings().getFirst();
		check("entity.experience_orb.pickup".equals(completeSound.value()) && completeSound.maxLength() == 96,
			"the completion sound value and input bound remain unchanged");
		check(module.colorSettings().stream().map(ColorSetting::argb).toList().equals(List.of(
			0xF01B1B22, 0xFFF0F0F4, 0xFFFFFFFF, 0xFF7D7D7D, 0xFF3D3D3D, 0xFF101010,
			0xFF6C63FF, 0xFF4B4B55)), "appearance color defaults are unchanged");

		check(module.groups().stream().map(SettingGroup::name).toList().equals(List.of(
			"Experiments", "Display", "Preview", "Protection", "Appearance", "Sounds", "Colors")),
			"the settings panel keeps its seven groups in order");
		check(groupNames(module.groups().get(0)).equals(List.of("Chronomatron", "Ultrasequencer", "Superpairs"))
			&& groupNames(module.groups().get(1)).equals(List.of("Hide Text Above Board",
				"Show Superpairs Clicks Remaining"))
			&& groupNames(module.groups().get(2)).equals(List.of("Chronomatron Future Clicks",
				"Ultrasequencer Future Clicks"))
			&& groupNames(module.groups().get(3)).equals(List.of("Prevent Misclicks", "Serums Consumed"))
			&& groupNames(module.groups().get(4)).equals(List.of("Term Size", "Roundness", "Slot Gap"))
			&& groupNames(module.groups().get(5)).equals(List.of("Complete Sounds", "Max Click Alert",
				"Complete Sound", "Complete Sound Pitch", "Complete Sound Volume"))
			&& groupNames(module.groups().get(6)).equals(List.of("Panel Color", "Text Color", "Current Color",
				"Next Color", "Next Next Color", "Next Next Next Color", "Discovered Color", "Unknown Color")),
			"settings remain in their original UI groups");
	}

	private static void checkSelectionAndPreviewSettings() {
		ExperimentSolverModule module = ExperimentSolverModule.INSTANCE;
		check(module.supports(ExperimentType.CHRONOMATRON) && module.supports(ExperimentType.ULTRASEQUENCER)
			&& module.supports(ExperimentType.SUPERPAIRS), "all three solver toggles default on");
		BooleanSetting chronomatron = module.chronomatron();
		BooleanSetting ultrasequencer = module.ultrasequencer();
		BooleanSetting superpairs = module.superpairs();
		boolean chronomatronWasSelected = chronomatron.rawValue();
		boolean ultrasequencerWasSelected = ultrasequencer.rawValue();
		boolean superpairsWasSelected = superpairs.rawValue();
		NumberSetting chronomatronFuture = module.chronomatronFutureClicks();
		NumberSetting ultrasequencerFuture = module.ultrasequencerFutureClicks();
		float chronomatronFutureWas = chronomatronFuture.value();
		float ultrasequencerFutureWas = ultrasequencerFuture.value();
		try {
			chronomatron.setValue(false);
			ultrasequencer.setValue(false);
			superpairs.setValue(false);
			check(!module.supports(ExperimentType.CHRONOMATRON)
				&& !module.supports(ExperimentType.ULTRASEQUENCER)
				&& !module.supports(ExperimentType.SUPERPAIRS), "each switch independently controls its puzzle");

			check(module.previewSteps(ExperimentType.CHRONOMATRON) == 4
				&& module.previewSteps(ExperimentType.ULTRASEQUENCER) == 4
				&& module.previewSteps(ExperimentType.SUPERPAIRS) == 1, "preview depth defaults are unchanged");
			chronomatronFuture.setValue(0f);
			ultrasequencerFuture.setValue(0f);
			check(module.previewSteps(ExperimentType.CHRONOMATRON) == 1
				&& module.previewSteps(ExperimentType.ULTRASEQUENCER) == 1,
				"zero future clicks leaves only the current sequence tile");
			check(!module.isSettingVisible(setting(module, "Next Color"))
				&& !module.isSettingVisible(setting(module, "Next Next Color"))
				&& !module.isSettingVisible(setting(module, "Next Next Next Color")),
				"future-order colors hide when both preview counts are zero");
			chronomatronFuture.setValue(1f);
			check(module.isSettingVisible(setting(module, "Next Color"))
				&& !module.isSettingVisible(setting(module, "Next Next Color")),
				"future-order color visibility follows the greatest puzzle preview setting");
		} finally {
			chronomatron.setValue(chronomatronWasSelected);
			ultrasequencer.setValue(ultrasequencerWasSelected);
			superpairs.setValue(superpairsWasSelected);
			chronomatronFuture.setValue(chronomatronFutureWas);
			ultrasequencerFuture.setValue(ultrasequencerFutureWas);
		}
	}

	private static void checkJavaInteropSurface() {
		try {
			Field singleton = ExperimentSolverModule.class.getField("INSTANCE");
			check(singleton.getType() == ExperimentSolverModule.class
				&& Modifier.isStatic(singleton.getModifiers()) && Modifier.isFinal(singleton.getModifiers())
				&& singleton.get(null) == ExperimentSolverModule.INSTANCE,
				"Java callers retain the public static singleton field");
			check(Modifier.isProtected(ExperimentSolverModule.class.getDeclaredMethod("onEnable").getModifiers())
				&& Modifier.isProtected(ExperimentSolverModule.class.getDeclaredMethod("onDisable").getModifiers()),
				"diagnostic ownership activation and release keep protected lifecycle hooks");
			requireMethod(ExperimentSolverModule.class, "supports", boolean.class, ExperimentType.class);
			requireMethod(ExperimentSolverModule.class, "configuration", ExperimentSolverEngine.Configuration.class);
			requireMethod(ExperimentSolverModule.class, "chronomatron", BooleanSetting.class);
			requireMethod(ExperimentSolverModule.class, "ultrasequencer", BooleanSetting.class);
			requireMethod(ExperimentSolverModule.class, "superpairs", BooleanSetting.class);
			requireMethod(ExperimentSolverModule.class, "chronomatronFutureClicks", NumberSetting.class);
			requireMethod(ExperimentSolverModule.class, "ultrasequencerFutureClicks", NumberSetting.class);
			requireMethod(ExperimentSolverModule.class, "serumsConsumed", NumberSetting.class);
			requireMethod(ExperimentSolverModule.class, "tick", void.class);
			requireMethod(ExperimentSolverModule.class, "onScreenClosed", void.class);
			requireMethod(ExperimentSolverModule.class, "onScreenOpened", void.class, AbstractContainerScreen.class);
			requireMethod(ExperimentSolverModule.class, "onContainerUpdated", void.class, AbstractContainerMenu.class);
			requireMethod(ExperimentSolverModule.class, "observesMenu", boolean.class, AbstractContainerMenu.class);
			requireMethod(ExperimentSolverModule.class, "renderCustomScreen", boolean.class,
				AbstractContainerScreen.class, GuiGraphicsExtractor.class, int.class, int.class, float.class);
			requireMethod(ExperimentSolverModule.class, "handleCustomClick", boolean.class,
				AbstractContainerScreen.class, MouseButtonEvent.class, ExperimentSolverModule.SlotClickDispatcher.class);
			requireMethod(ExperimentSolverModule.class, "ownsScreen", boolean.class, AbstractContainerScreen.class);
			requireMethod(ExperimentSolverModule.class, "isDispatchingCustomClick", boolean.class);
			requireMethod(ExperimentSolverModule.class, "previewSteps", int.class, ExperimentType.class);
			requireStaticMethod(ExperimentSolverModule.class, "shouldPlayMaxClickMilestoneSound", boolean.class,
				boolean.class, boolean.class);
			requireStaticMethod(ExperimentSolverModule.class, "isRecognized", boolean.class,
				AbstractContainerScreen.class);
			requireStaticMethod(ExperimentSolverModule.class, "ultrasequencerPreview", List.class,
				geiler.addons.client.enchanting.SolverView.class, Optional.class, int.class);

			Class<?> session = ExperimentSolverModule.Session.class;
			requireField(session, "type", ExperimentType.class);
			requireField(session, "tier", ExperimentTier.class);
			requireField(session, "menuId", int.class);
			requireField(session, "slots", List.class);
			requireField(session, "instruction", String.class);
			requireField(session, "slotUpdates", List.class);
			requireMethod(session, "observe", boolean.class, AbstractContainerScreen.class);
			requireMethod(session, "refreshRenderSlots", void.class, AbstractContainerScreen.class);
			requireMethod(session, "takeSuperpairsObservationDirty", boolean.class);
			requireMethod(session, "drainSlotUpdates", List.class);
			requireMethod(session, "snapshot", geiler.addons.client.enchanting.ExperimentSnapshot.class,
				AbstractContainerScreen.class, boolean.class);
			Class<?> slotUpdate = ExperimentSolverModule.Session.SlotUpdate.class;
			requireMethod(slotUpdate, "kind", ExperimentSolverModule.Session.SlotUpdate.Kind.class);
			requireMethod(slotUpdate, "slotId", int.class);
			requireMethod(slotUpdate, "stack", ItemStack.class);
			requireMethod(slotUpdate, "status", String.class);
			requireMethod(slotUpdate, "sequence", long.class);
			requireMethod(slotUpdate, "boardStacks", Map.class);
			requireMethod(slotUpdate, "chronomatronEvent",
				geiler.addons.client.enchanting.ChronomatronEvent.class);
			ExperimentSolverModule.Session.SlotUpdate first = new ExperimentSolverModule.Session.SlotUpdate(
				ExperimentSolverModule.Session.SlotUpdate.Kind.BOARD, 30, ItemStack.EMPTY, "", 1L, Map.of());
			ExperimentSolverModule.Session.SlotUpdate second = new ExperimentSolverModule.Session.SlotUpdate(
				ExperimentSolverModule.Session.SlotUpdate.Kind.BOARD, 30, ItemStack.EMPTY, "", 1L, Map.of());
			check(first.kind() == ExperimentSolverModule.Session.SlotUpdate.Kind.BOARD && first.slotId() == 30
				&& first.stack() == ItemStack.EMPTY && first.status().isEmpty() && first.sequence() == 1L
				&& first.boardStacks().isEmpty() && first.equals(second) && first.hashCode() == second.hashCode()
				&& first.toString().startsWith("SlotUpdate[kind=BOARD, slotId=30,"),
				"the Kotlin event frame keeps the Java record's accessors and value behavior");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Experimentation Solver Java interoperability changed", exception);
		}
	}

	private static void checkControllerInteropSurface() {
		try {
			Field singleton = ExperimentController.class.getField("INSTANCE");
			check(singleton.getType() == ExperimentController.class
				&& Modifier.isStatic(singleton.getModifiers()) && Modifier.isFinal(singleton.getModifiers())
				&& singleton.get(null) == ExperimentController.INSTANCE,
				"Java and Kotlin callers retain the controller singleton field");
			check(Modifier.isFinal(ExperimentController.class.getModifiers())
				&& Modifier.isPrivate(ExperimentController.class.getDeclaredConstructor().getModifiers()),
				"the controller remains final with a private constructor");
			requireMethod(ExperimentController.class, "engine", ExperimentSolverEngine.class);
			requireMethod(ExperimentController.class, "view", geiler.addons.client.enchanting.SolverView.class);
			requireMethod(ExperimentController.class, "sessionGeneration", long.class);
			requireMethod(ExperimentController.class, "isDispatchingClick", boolean.class);
			requireMethod(ExperimentController.class, "tick", void.class);
			requireMethod(ExperimentController.class, "onScreenOpened", void.class, AbstractContainerScreen.class);
			requireMethod(ExperimentController.class, "observeScreen", void.class, AbstractContainerScreen.class);
			requireMethod(ExperimentController.class, "onOwnerDisabled", void.class);
			requireMethod(ExperimentController.class, "onScreenClosed", void.class);
			requireMethod(ExperimentController.class, "onContainerUpdated", void.class, AbstractContainerMenu.class);
			requireMethod(ExperimentController.class, "observesMenu", boolean.class, AbstractContainerMenu.class);
			requireMethod(ExperimentController.class, "isAutoEligibleScreen", boolean.class,
				AbstractContainerScreen.class);
			requireMethod(ExperimentController.class, "isDispatchingCustomClick", boolean.class);
			requireMethod(ExperimentController.class, "isExpectedStepClick", boolean.class, int.class);
			requireMethod(ExperimentController.class, "dispatchSequenceClick", boolean.class,
				AbstractContainerScreen.class, int.class, ExperimentSolverModule.SlotClickDispatcher.class);
			requireMethod(ExperimentController.class, "dispatchAutoSequenceClick", boolean.class,
				AbstractContainerScreen.class, int.class, int.class, ExperimentSolverModule.SlotClickDispatcher.class);
			requireMethod(ExperimentController.class, "dispatchSuperpairsClick", boolean.class,
				AbstractContainerScreen.class, int.class, int.class, ExperimentSolverModule.SlotClickDispatcher.class);
			// Kotlin cannot emit package-private JVM methods, so former package methods remain public
			// while their Java signatures and static/instance shape stay stable.
			requireKotlinInstanceMethod(ExperimentController.class, "session",
				ExperimentSolverModule.Session.class);
			requireKotlinInstanceMethod(ExperimentController.class, "dispatchManualSequenceClick",
				ExperimentClickGate.SequenceDispatchResult.class, AbstractContainerScreen.class, int.class,
				ExperimentSolverModule.SlotClickDispatcher.class);
			requireKotlinInstanceMethod(ExperimentController.class, "dispatchAutoUltrasequencerClick", boolean.class,
				AbstractContainerScreen.class, int.class, ExperimentSolverModule.SlotClickDispatcher.class);
			requireKotlinInstanceMethod(ExperimentController.class, "hasActiveOwner", boolean.class,
				ExperimentType.class);
			requireStaticMethod(ExperimentController.class, "hasActiveOwner", boolean.class,
				ExperimentType.class, boolean.class, boolean.class, boolean.class, boolean.class);

			check(ExperimentController.hasActiveOwner(ExperimentType.CHRONOMATRON, false, false, true, true)
				&& ExperimentController.hasActiveOwner(ExperimentType.SUPERPAIRS, true, true, false, false)
				&& !ExperimentController.hasActiveOwner(ExperimentType.SUPERPAIRS, false, false, true, true)
				&& !ExperimentController.hasActiveOwner(null, true, true, true, true),
				"owner eligibility keeps Auto limited to sequence games and rejects a missing puzzle type");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Experimentation controller Java interoperability changed", exception);
		}
	}

	private static void requireStaticMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
		throws ReflectiveOperationException {
		Method method = owner.getMethod(name, parameters);
		check(method.getReturnType() == result && Modifier.isStatic(method.getModifiers()),
			"Java callers retain static method " + name);
	}

	private static void requireMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
		throws ReflectiveOperationException {
		Method method = owner.getMethod(name, parameters);
		check(method.getReturnType() == result, "Java callers retain method " + name);
	}

	private static void requireKotlinInstanceMethod(Class<?> owner, String name, Class<?> result,
		Class<?>... parameters)
		throws ReflectiveOperationException {
		Method method = owner.getDeclaredMethod(name, parameters);
		int modifiers = method.getModifiers();
		check(method.getReturnType() == result && Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers),
			"Java callers retain Kotlin facade instance method " + name);
	}

	private static void requireField(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
		check(owner.getField(name).getType() == type, "Java callers retain field " + name);
	}

	private static Setting setting(ExperimentSolverModule module, String name) {
		return module.groups().stream().flatMap(group -> group.settings().stream())
			.filter(candidate -> candidate.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("missing setting " + name));
	}

	private static List<String> groupNames(SettingGroup group) {
		return group.settings().stream().map(Setting::name).toList();
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static List<Boolean> values(List<BooleanSetting> settings) {
		return settings.stream().map(BooleanSetting::value).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("ExperimentSolverModuleChecks: " + message);
	}
}
