package geiler.addons.client.gui;

import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Workspace;

import java.util.List;

/** Covers the Normal/Macros workspace split of the Click GUI category rail and its per-workspace view state. */
public final class ClickGuiWorkspaceChecks {
	private ClickGuiWorkspaceChecks() { }

	public static void run() {
		checkCategorySplit();
		checkStateBanks();
		checkSearchMemory();
	}

	private static void checkSearchMemory() {
		long limit = ClickGuiState.SEARCH_MEMORY_MILLIS;
		check(limit == 120_000L, "a Click GUI search is kept for two minutes");
		check("wheat".equals(ClickGuiState.recallSearch("wheat", 1_000L, 1_000L + limit)), "a search at exactly two minutes is still recalled");
		check(ClickGuiState.recallSearch("wheat", 1_000L, 1_001L + limit).isEmpty(), "a search older than two minutes is cleared");
		check(ClickGuiState.recallSearch("  ", 1_000L, 2_000L).isEmpty() && ClickGuiState.recallSearch("wheat", 5_000L, 1_000L).isEmpty(),
			"a blank search and a clock that went backwards recall nothing");
	}

	private static void checkCategorySplit() {
		check(Category.Companion.of(Workspace.NORMAL).equals(List.of(
			Category.F7, Category.ENCHANTING, Category.SLAYER, Category.HUNTING, Category.FISHING, Category.FARMING,
			Category.FORAGING, Category.MINING, Category.KUUDRA, Category.MISCELLANEOUS, Category.VISUAL, Category.DEV)),
			"the Normal workspace keeps the original categories in their original order");
		check(Category.Companion.of(Workspace.MACROS).equals(List.of(
			Category.ENGINE, Category.AREA_KILLER, Category.SIM)),
			"the Macros workspace lists Engine, Area Killer and Sim");
		int counted = Category.Companion.of(Workspace.NORMAL).size() + Category.Companion.of(Workspace.MACROS).size();
		check(counted == Category.values().length, "every category belongs to exactly one workspace");
		check("Sim".equals(Category.SIM.displayName()) && "Engine".equals(Category.ENGINE.displayName())
			&& "Dungeons".equals(Category.F7.displayName()), "category labels stay as declared");
		check(Category.F7.getWorkspace() == Workspace.NORMAL && Category.SIM.getWorkspace() == Workspace.MACROS,
			"a category reports its workspace");
		check(Workspace.NORMAL.otherWorkspace() == Workspace.MACROS && Workspace.MACROS.otherWorkspace() == Workspace.NORMAL,
			"switching a workspace always lands on the other one");
		check("Macros".equals(Workspace.NORMAL.switchLabel()) && "Modules".equals(Workspace.MACROS.switchLabel()),
			"the rail button names the workspace it leads to");
		check(Workspace.fromName("MACROS") == Workspace.MACROS && Workspace.fromName("nonsense") == Workspace.NORMAL
			&& Workspace.fromName(null) == Workspace.NORMAL, "unknown persisted workspace names fall back to Normal");
	}

	private static void checkStateBanks() {
		Workspace original = ClickGuiState.workspace();
		try {
			ClickGuiState.setWorkspace(Workspace.NORMAL);
			ClickGuiState.setCategory(Category.SLAYER);
			ClickGuiState.setCategoryScroll(11);
			ClickGuiState.setSettingsScroll(4);
			ClickGuiState.setGridScroll(Category.SLAYER, 5);
			ClickGuiState.setFavoriteCategoryIds(List.of(Category.SLAYER.name()));

			ClickGuiState.setWorkspace(Workspace.MACROS);
			check(ClickGuiState.workspace() == Workspace.MACROS, "the active workspace can be switched");
			check(ClickGuiState.category() == Category.ENGINE,
				"a workspace that was never visited starts on its first category");
			check(ClickGuiState.categoryScroll() == 0 && ClickGuiState.settingsScroll() == 0
				&& ClickGuiState.gridScroll(Category.ENGINE) == 0 && ClickGuiState.favoriteCategoryIds().isEmpty(),
				"the other workspace's scroll positions and favorites are not inherited");

			ClickGuiState.setCategory(Category.SIM);
			ClickGuiState.setCategoryScroll(3);
			ClickGuiState.toggleFavorite(Category.SIM);
			ClickGuiState.setGridScroll(Category.SIM, 9);

			ClickGuiState.setWorkspace(Workspace.NORMAL);
			check(ClickGuiState.category() == Category.SLAYER && ClickGuiState.categoryScroll() == 11
				&& ClickGuiState.settingsScroll() == 4 && ClickGuiState.gridScroll(Category.SLAYER) == 5
				&& ClickGuiState.favoriteCategoryIds().equals(java.util.Set.of(Category.SLAYER.name())),
				"switching back restores the Normal workspace view exactly");
			check(!ClickGuiState.favoriteCategoryIds().contains(Category.SIM.name()),
				"a Macros favorite never shows up in the Normal workspace's saved favorites");

			ClickGuiState.setWorkspace(Workspace.MACROS);
			check(ClickGuiState.category() == Category.SIM && ClickGuiState.categoryScroll() == 3
				&& ClickGuiState.gridScroll(Category.SIM) == 9 && ClickGuiState.isFavorite(Category.SIM),
				"switching again restores the Macros workspace view exactly");

			ClickGuiState.setWorkspace(Workspace.NORMAL);
			ClickGuiState.runIn(Workspace.MACROS, () -> {
				check(ClickGuiState.workspace() == Workspace.MACROS && ClickGuiState.category() == Category.SIM,
					"runIn reads the requested workspace");
				ClickGuiState.setCategoryScroll(6);
			});
			check(ClickGuiState.workspace() == Workspace.NORMAL && ClickGuiState.categoryScroll() == 11,
				"runIn puts the active workspace back and leaves its state alone");
			ClickGuiState.runIn(Workspace.MACROS, () -> check(ClickGuiState.categoryScroll() == 6,
				"runIn writes land in the requested workspace"));

			ClickGuiState.setCategory(Category.VISUAL);
			ClickGuiState.setCategory(Category.ENGINE);
			check(ClickGuiState.category() == Category.VISUAL,
				"selecting a Macros category while Normal is active only changes the Macros bank");
			ClickGuiState.setCategory(null);
			check(ClickGuiState.category() == null, "a null category stays available to compatibility callers");
		} finally {
			for (Workspace workspace : Workspace.values()) {
				ClickGuiState.runIn(workspace, () -> {
					ClickGuiState.setCategory(Category.Companion.of(workspace).get(0));
					ClickGuiState.setOpenModule(null);
					ClickGuiState.setExpandedColor(null);
					ClickGuiState.setSettingsScroll(0);
					ClickGuiState.setCategoryScroll(0);
					ClickGuiState.setGridScrolls(java.util.Map.of());
					ClickGuiState.setFavoriteCategoryIds(List.of());
					ClickGuiState.setFavoriteModuleIds(List.of());
				});
			}
			ClickGuiState.setWorkspace(original);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
