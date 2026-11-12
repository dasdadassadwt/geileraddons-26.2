package geiler.addons.client;

import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.SettingGroup;

import java.util.List;
import java.util.Map;

/** Covers session-only navigation and favorite state exposed to the Click GUI. */
public final class ClickGuiStateChecks {
	private ClickGuiStateChecks() { }

	public static void run() {
		Category category = Category.values()[0];
		ClickGuiState.setCategory(category);
		check(ClickGuiState.category() == category, "selected category survives the Java-facing state API");
		ClickGuiState.setCategory(null);
		check(ClickGuiState.category() == null, "null category state remains available to compatibility callers");
		ClickGuiState.setCategory(category);
		ClickGuiState.setCategoryScroll(-10);
		ClickGuiState.setGridScroll(category, -10);
		check(ClickGuiState.categoryScroll() == 0 && ClickGuiState.gridScroll(category) == 0,
			"category scroll positions remain nonnegative");
		ClickGuiState.setGridScrolls(Map.of(category.name(), 7));
		check(ClickGuiState.gridScroll(category) == 7 && ClickGuiState.gridScrolls().get(category) == 7,
			"persisted category keys restore category-specific grid positions");
		try {
			ClickGuiState.gridScrolls().put(category, 8);
			throw new AssertionError("grid scroll snapshot is mutable");
		} catch (UnsupportedOperationException expected) {
			// Returned navigation snapshots cannot mutate the live state.
		}
		ClickGuiState.setGridScrolls(null);
		check(ClickGuiState.gridScrolls().isEmpty(), "missing saved grid positions clear old view state");

		ClickGuiState.setModuleFilter(category, "");
		check("all".equals(ClickGuiState.moduleFilter(category)) && "all".equals(ClickGuiState.moduleFilter(null)),
			"blank and missing category filters use the all-modules view");
		ClickGuiState.setModuleFilter(category, "favorites");
		check("favorites".equals(ClickGuiState.moduleFilter(category)), "one-level category filters retain their selected ID");

		ClickGuiState.setFavoriteCategoryIds(List.of(category.name()));
		check(ClickGuiState.isFavorite(category), "persisted category favorites use stable enum names");
		try {
			ClickGuiState.favoriteCategoryIds().add("changed");
			throw new AssertionError("category favorite snapshot is mutable");
		} catch (UnsupportedOperationException expected) {
			// Returned favorite snapshots cannot mutate the live state.
		}
		ClickGuiState.setFavoriteCategoryIds(List.of());
		check(!ClickGuiState.isFavorite(category), "reloading category favorites replaces old selections");

		Module module = ModuleManager.modules().get(0);
		ClickGuiState.setFavoriteModuleIds(List.of(module.configName(), " "));
		check(ClickGuiState.isFavorite(module) && ClickGuiState.favoriteModuleIds().size() == 1,
			"module favorites retain stable config names and discard blank IDs");
		ClickGuiState.toggleFavorite(module);
		check(!ClickGuiState.isFavorite(module), "module favorite toggles remove the current stable ID");
		ClickGuiState.setFavoriteModuleIds(List.of());

		ClickGuiState.toggleCategoryExpanded(category);
		check(ClickGuiState.isCategoryExpanded(category) && ClickGuiState.expandedCategories().contains(category),
			"category expansion snapshots include the selected parent");
		try {
			ClickGuiState.expandedCategories().clear();
			throw new AssertionError("expanded category snapshot is mutable");
		} catch (UnsupportedOperationException expected) {
			// Returned expansion snapshots cannot mutate the live state.
		}
		ClickGuiState.toggleCategoryExpanded(category);
		check(!ClickGuiState.isCategoryExpanded(category), "category expansion toggles back to the folded state");

		SettingGroup namedGroup = new SettingGroup("Parity Check");
		SettingGroup unnamedGroup = new SettingGroup((String) null);
		check(ClickGuiState.isCollapsed(module, namedGroup) && !ClickGuiState.isCollapsed(module, unnamedGroup),
			"named setting sections start folded while unnamed sections stay open");
		ClickGuiState.toggleCollapsed(module, namedGroup);
		check(!ClickGuiState.isCollapsed(module, namedGroup), "named setting section state toggles open");
		ClickGuiState.toggleCollapsed(module, namedGroup);
		check(ClickGuiState.isCollapsed(module, namedGroup), "named setting section state toggles closed");

		ClickGuiState.setCategoryScroll(0);
		ClickGuiState.setGridScrolls(Map.of());
		ClickGuiState.setModuleFilter(category, "all");
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
