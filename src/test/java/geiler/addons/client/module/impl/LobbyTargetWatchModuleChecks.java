package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.utility.LobbySniperStore;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.utility.LobbyTargetWatchState;


/** Focused checks for the registered Lobby Sniper module's saved identity and settings. */
public final class LobbyTargetWatchModuleChecks {
	private LobbyTargetWatchModuleChecks() { }

	public static void run() {
		checkInlinePlayerList();
		checkUnavailableOutputKeepsArrivalPending();
		LobbyTargetWatchModule module = LobbyTargetWatchModule.INSTANCE;
		check("Lobby Sniper".equals(module.name()), "display name is preserved");
		check("Lobby Target Watch".equals(module.configName()), "legacy persisted module key is preserved");
		check(module.category() == Category.MISCELLANEOUS, "module stays in the Miscellaneous category");
		check(!module.defaultEnabled() && !module.isEnabled(), "fresh-install module remains disabled");

		TextSetting legacyTargets = module.textSettings().stream()
			.filter(setting -> "Watched Players".equals(setting.name()))
			.findFirst().orElseThrow(() -> new AssertionError("legacy Watched Players setting is registered"));
		check(legacyTargets.value().isEmpty(), "legacy setting keeps its empty default");
		check(legacyTargets.maxLength() == 512, "legacy setting keeps its 512-character bound");
		check(!module.isSettingVisible(legacyTargets), "legacy setting remains hidden from the current editor");
	}

	/** The managed player list lives on the module page: per-player switch, title and delete, no Save or hub. */
	private static void checkInlinePlayerList() {
		LobbyTargetWatchModule module = LobbyTargetWatchModule.INSTANCE;
		LobbySniperStore store = LobbySniperStore.INSTANCE;
		store.remove("InlineAlice");
		store.remove("InlineBob");
		check(module.actions().stream().noneMatch(action -> "Manage Players".equals(action.name())),
			"the separate Manage Players hub action is gone");

		TextSetting nameField = module.textSettings().stream().filter(s -> "Player Name".equals(s.name()))
			.findFirst().orElseThrow(() -> new AssertionError("Player Name field is registered"));
		ModuleAction add = module.actions().stream().filter(a -> "Add Player".equals(a.name()))
			.findFirst().orElseThrow(() -> new AssertionError("Add Player action is registered"));
		check(module.actions().stream().noneMatch(a -> a.name().toLowerCase().contains("save")), "no separate Save action");
		nameField.setValue("InlineAlice");
		add.onClick().run();
		check(store.find("InlineAlice") != null && store.find("InlineAlice").enabled(), "Add stores an enabled player");
		check(nameField.value().isEmpty(), "Add clears the name field");

		SettingGroup row = playerRow(module, "InlineAlice");
		check(row.toggle() != null && row.toggle().value(), "each player row carries its own enabled switch");
		row.toggle().setValue(false);
		playerRow(module, "InlineAlice");
		check(!store.find("InlineAlice").enabled(), "turning the row switch off persists the player as disabled");

		row = playerRow(module, "InlineAlice");
		TextSetting title = row.settings().stream().filter(s -> s instanceof TextSetting).map(s -> (TextSetting) s)
			.findFirst().orElseThrow(() -> new AssertionError("row has a title field"));
		title.setValue("Found!");
		playerRow(module, "InlineAlice");
		check("Found!".equals(store.find("InlineAlice").title()), "editing the row title persists it");

		ModuleAction delete = playerRow(module, "InlineAlice").settings().stream()
			.filter(s -> s instanceof ModuleAction).map(s -> (ModuleAction) s).findFirst()
			.orElseThrow(() -> new AssertionError("row has its own Delete"));
		delete.onClick().run();
		check(store.find("InlineAlice") == null, "the row Delete removes the player");
	}

	private static SettingGroup playerRow(LobbyTargetWatchModule module, String name) {
		SettingGroup players = module.groups().stream().filter(g -> "Players".equals(g.name())).findFirst()
			.orElseThrow(() -> new AssertionError("Players group exists"));
		return players.children().stream().filter(g -> name.equals(g.name())).findFirst()
			.orElseThrow(() -> new AssertionError("row exists for " + name));
	}

	private static void checkUnavailableOutputKeepsArrivalPending() {
		LobbyTargetWatchState state = new LobbyTargetWatchState();
		check(state.update(java.util.List.of("Alice"), java.util.List.of("Alice"), false).isEmpty(),
			"an arrival is not emitted while its output surface is unavailable");
		check(state.pendingTargetCount() == 1, "an unavailable arrival remains pending");
		check(state.update(java.util.List.of("Alice"), java.util.List.of("Alice"), true)
			.equals(java.util.List.of("Alice")), "the pending arrival is emitted when output becomes available");
		check(state.pendingTargetCount() == 0, "a delivered arrival is no longer pending");

		check(state.update(java.util.List.of("Alice"), java.util.List.of(), false).isEmpty(),
			"absence clears the previous arrival while output is unavailable");
		check(state.update(java.util.List.of("Alice"), java.util.List.of("Alice"), false).isEmpty(),
			"a later arrival while unavailable remains pending");
		check(state.update(java.util.List.of("Alice"), java.util.List.of("Alice"), true)
			.equals(java.util.List.of("Alice")), "a re-entry is emitted after output becomes available");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
