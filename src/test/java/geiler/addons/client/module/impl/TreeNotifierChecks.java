package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.tree.ChatSuppressor;
import geiler.addons.client.tree.TreeFelled;
import geiler.addons.client.tree.TreeType;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Focused parity checks for Tree Broken Notifier's Java-facing API and persisted settings. */
public final class TreeNotifierChecks {
	private TreeNotifierChecks() {
	}

	public static void main(String[] arguments) throws Exception {
		checkIdentityAndEntrypoints();
		checkSettingsAndGroups();
		checkFelledMessagePatterns();
		checkActiveChatBehavior();
		System.out.println("Tree Broken Notifier checks passed");
	}

	private static void checkIdentityAndEntrypoints() {
		TreeNotifierModule module = TreeNotifierModule.INSTANCE;
		if (module != TreeNotifierModule.INSTANCE || !module.name().equals("Tree Broken Notifier")
			|| !module.configName().equals("Tree Broken Notifier")
			|| !module.description().equals("Shows a title when a tree is felled or gives a gift.")
			|| module.category() != Category.FORAGING || module.defaultEnabled() || module.isEnabled()) {
			throw new AssertionError("Tree Broken Notifier identity or fresh-install state changed");
		}

		// Both orderings remain available to the existing Kotlin caller and previous Java callers.
		assertFalse(module.onChatMessage("disabled", null), "the original Java argument order is callable while disabled");
		assertFalse(module.onChatMessage(null, "disabled"), "the current client argument order is callable while disabled");
	}

	private static void checkSettingsAndGroups() {
		TreeNotifierModule module = TreeNotifierModule.INSTANCE;

		List<BooleanSetting> booleans = module.booleanSettings();
		assertNames(booleans, List.of(
			"On Tree Gift", "Hide Gift Message", "On Timber / Petalfall", "Hide Felled Message", "Play Sound"),
			"boolean setting IDs");
		boolean[] booleanDefaults = {true, false, true, false, true};
		for (int i = 0; i < booleanDefaults.length; i++) {
			if (booleans.get(i).rawValue() != booleanDefaults[i]) {
				throw new AssertionError("Boolean default changed: " + booleans.get(i).name());
			}
		}

		List<NumberSetting> numbers = module.numberSettings();
		assertNames(numbers, List.of("Gift Pitch", "Felled Pitch", "Duration (s)", "Volume"), "number setting IDs");
		float[][] numberDefaults = {
			{0.5f, 2.0f, 1.0f}, {0.5f, 2.0f, 0.8f}, {0.5f, 10.0f, 2.0f}, {0.0f, 1.0f, 1.0f},
		};
		for (int i = 0; i < numberDefaults.length; i++) {
			NumberSetting setting = numbers.get(i);
			float[] expected = numberDefaults[i];
			if (!close(setting.min(), expected[0]) || !close(setting.max(), expected[1])
				|| !close(setting.value(), expected[2])) {
				throw new AssertionError("Number bounds/default changed: " + setting.name());
			}
		}

		List<TextSetting> texts = module.textSettings();
		assertNames(texts, List.of("Gift Text", "Gift Sound", "Felled Text", "Felled Sound"), "text setting IDs");
		String[] textDefaults = {
			"Baum Kaputt", "minecraft:entity.player.levelup", "Kaboom", "minecraft:block.note_block.bass",
		};
		int[] textMaxLengths = {48, 64, 48, 64};
		for (int i = 0; i < texts.size(); i++) {
			TextSetting setting = texts.get(i);
			if (!setting.value().equals(textDefaults[i]) || setting.maxLength() != textMaxLengths[i]) {
				throw new AssertionError("Text default/length changed: " + setting.name());
			}
		}

		List<SettingGroup> groups = module.groups();
		assertGroupNames(groups, List.of("Tree Gift", "Timber & Petalfall", "Shared"), "setting group IDs");
		assertGroup(groups.get(0), List.of(
			"On Tree Gift", "Gift Text", "Gift Sound", "Gift Pitch", "Hide Gift Message"), "Tree Gift");
		assertGroup(groups.get(1), List.of(
			"On Timber / Petalfall", "Felled Text", "Felled Sound", "Felled Pitch", "Hide Felled Message"),
			"Timber & Petalfall");
		assertGroup(groups.get(2), List.of("Duration (s)", "Play Sound", "Volume"), "Shared");
	}

	private static void checkFelledMessagePatterns() {
		assertTrue(TreeFelled.matches("§cTIMBER! You felled the entire Helix Tree!"),
			"the player's Timber proc message is recognized for a tree-break title");
		assertTrue(TreeFelled.matches("§cTIMBER! OtherPlayer felled the entire Oak Tree!"),
			"another player's Timber proc message is recognized");
		assertTrue(TreeFelled.matches("§dPETALFALL! You felled the entire Fig Tree!"),
			"Petalfall tree felling is recognized independently from Timber");
		assertFalse(TreeFelled.matches("TIMBER increases the chance for a tree to be felled."),
			"ordinary Timber descriptions do not trigger a tree-break title");
	}

	private static void checkActiveChatBehavior() throws Exception {
		TreeNotifierModule module = TreeNotifierModule.INSTANCE;
		if (module.isEnabled()) throw new AssertionError("the active behavior fixture must start disabled");

		List<BooleanSetting> booleans = module.booleanSettings();
		boolean[] originalBooleans = new boolean[booleans.size()];
		for (int i = 0; i < booleans.size(); i++) originalBooleans[i] = booleans.get(i).rawValue();
		List<TextSetting> texts = module.textSettings();
		String[] originalTexts = new String[texts.size()];
		for (int i = 0; i < texts.size(); i++) originalTexts[i] = texts.get(i).value();

		try {
			booleans.get(0).setValue(true);  // Gift titles enabled
			booleans.get(1).setValue(true);  // Hide gift messages
			booleans.get(2).setValue(true);  // Felled titles enabled
			booleans.get(3).setValue(true);  // Hide felled messages
			booleans.get(4).setValue(false); // Avoid loading/playing a Minecraft sound
			texts.get(0).setValue("");       // Blank titles return before touching the Minecraft HUD
			texts.get(2).setValue("");

			module.setEnabled(true);

			String rule = "\u25ac".repeat(8);
			Component separator = Component.literal(rule);
			assertTrue(module.onChatMessage(rule, separator), "an opening gift separator is held from chat");
			assertEquals(separator, privateField(module, "heldLine"), "the module retains the original separator component");
			assertTrue(module.onChatMessage("TREE GIFT", Component.literal("TREE GIFT")),
				"a gift header is hidden");
			assertEquals(null, privateField(module, "heldLine"), "the opening separator is discarded with a gift block");
			assertTrue(module.onChatMessage("gift detail", Component.literal("gift detail")),
				"gift block contents are hidden");
			assertTrue(module.onChatMessage(rule, separator), "the gift block closing separator is hidden");
			assertFalse(module.onChatMessage("ordinary chat", Component.literal("ordinary chat")),
				"ordinary chat is shown after the gift block closes");

			String figGift = "You cut 40% of the Fig Tree.";
			assertFalse(module.onChatMessage(figGift, Component.literal(figGift)),
				"a recognized tree gift remains visible outside a gift block");
			assertEquals(TreeType.FIG, privateField(module, "lastTree"),
				"the active gift path remembers its tree type");
			assertEquals(2, diagnosticEvents(module),
				"the first gift reaches the active title submission path");

			// Pin the debounce clock to the current instant so this stays deterministic even if the
			// offline harness pauses between the two calls.
			setPrivateField(module, "lastGiftTitle", System.currentTimeMillis());
			module.onChatMessage(figGift, Component.literal(figGift));
			assertEquals(2, diagnosticEvents(module),
				"a repeated gift does not submit a second title inside the one-second window");

			String felled = "TIMBER! You felled the entire Fig Tree!";
			assertTrue(module.onChatMessage(felled, Component.literal(felled)), "felled chat is hidden when configured");
			assertEquals(3, diagnosticEvents(module),
				"a felled event still submits its own title during the gift debounce window");
			assertEquals("Felled Fig", invokePrivate(module, "substitute",
				new Class<?>[]{String.class, TreeType.class}, "Felled {tree}", (Object) null),
				"the felled title uses the most recently recognized tree");

			module.setEnabled(false);
			assertEquals(null, privateField(module, "lastTree"), "disabling clears the remembered tree");
			assertEquals(0L, numberField(module, "lastGiftTitle").longValue(), "disabling resets gift debounce state");
		} finally {
			// Keep failure cleanup safe in a headless run: onDisable must not try to print a held
			// component through Minecraft if an earlier assertion interrupted the sequence.
			setPrivateField(module, "heldLine", null);
			((ChatSuppressor) privateField(module, "suppressor")).endOfTick();
			if (module.isEnabled()) module.setEnabled(false);
			for (int i = 0; i < booleans.size(); i++) booleans.get(i).setValue(originalBooleans[i]);
			for (int i = 0; i < texts.size(); i++) texts.get(i).setValue(originalTexts[i]);
		}
	}

	private static void assertGroup(SettingGroup group, List<String> settingNames, String label) {
		assertNames(group.settings(), settingNames, label + " group rows");
		if (group.toggle() != null || group.collapsedByDefault() || !group.children().isEmpty()) {
			throw new AssertionError(label + " group presentation changed");
		}
	}

	private static void assertNames(List<? extends Setting> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : actual) names.add(setting.name());
		if (!names.equals(expected)) throw new AssertionError(label + " changed: " + names);
	}

	private static void assertGroupNames(List<SettingGroup> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (SettingGroup group : actual) names.add(group.name());
		if (!names.equals(expected)) throw new AssertionError(label + " changed: " + names);
	}

	/** Events written this tick; the enable bookend is event number one. */
	private static int diagnosticEvents(TreeNotifierModule module) throws Exception {
		return ((ModuleDiagnostics) privateField(module, "diagnostics")).getEventsThisTick();
	}

	private static boolean close(float actual, float expected) {
		return Math.abs(actual - expected) < 0.0001f;
	}

	private static Object privateField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Number numberField(Object target, String name) throws Exception {
		return (Number) privateField(target, name);
	}

	private static void setPrivateField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object invokePrivate(Object target, String name, Class<?>[] parameterTypes,
		Object... arguments) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(target, arguments);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
