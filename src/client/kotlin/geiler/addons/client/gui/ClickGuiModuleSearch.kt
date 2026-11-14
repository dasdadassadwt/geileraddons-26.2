package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleCategoryFilter
import geiler.addons.client.module.ModuleManager
import java.util.Locale

/** Precomputed search text used by the Click GUI's all-category module search. */
@JvmRecord
data class ClickGuiSearchEntry(val module: Module, val searchText: String)

/** Search indexing, filtering, and favorite ordering kept out of the screen adapter. */
object ClickGuiModuleSearchBehavior {
    @JvmStatic
    fun normalizeQuery(query: String?): String =
        if (query.isNullOrBlank()) "" else query.trim().lowercase(Locale.ROOT)

    @JvmStatic
    fun searchableText(module: Module): String =
        (module.name() + " " + module.description()).lowercase(Locale.ROOT)

    @JvmStatic
    fun buildSearchIndex(modules: List<Module>): List<ClickGuiSearchEntry> {
        val entries = ArrayList<ClickGuiSearchEntry>(modules.size)
        for (module in modules) {
            if (!ClickGuiScreenCategoryBehavior.isModuleCardVisible(module)) continue
            entries.add(ClickGuiSearchEntry(module, searchableText(module)))
        }
        return java.util.List.copyOf(entries)
    }

    @JvmStatic
    fun matchingModules(index: List<ClickGuiSearchEntry>, normalizedQuery: String): List<Module> {
        if (normalizedQuery.isEmpty()) return java.util.List.of()
        val matches = ArrayList<Module>()
        for (entry in index) {
            if (entry.searchText.contains(normalizedQuery)) matches.add(entry.module)
        }
        return java.util.List.copyOf(matches)
    }

    @JvmStatic
    fun categoryMatches(category: Category, module: Module): Boolean =
        ClickGuiScreenCategoryBehavior.isModuleCardVisible(module) &&
            ModuleCategoryFilter.resolve(category, "all").matches(module)
}

/** Owns the screen's cached module search index and visible result snapshot. */
class ClickGuiModuleSearch {
    private var searchIndex: List<ClickGuiSearchEntry>? = null
    private var visibleSnapshot: List<Module> = java.util.List.of()
    private var visibleCategory: Category? = null
    private var visibleRawQuery: String? = null
    private var visibleFavoritesRevision = -1L
    private var visibleFilterId: String? = null

    fun visibleModules(category: Category, rawQuery: String, favoritesRevision: Long): List<Module> {
        val filterId = "all"
        if (visibleCategory == category && rawQuery == visibleRawQuery &&
            visibleFilterId == filterId && visibleFavoritesRevision == favoritesRevision
        ) return visibleSnapshot

        val query = ClickGuiModuleSearchBehavior.normalizeQuery(rawQuery)
        val matches = if (query.isEmpty()) {
            ModuleManager.modules(category).filter { ClickGuiModuleSearchBehavior.categoryMatches(category, it) }
        } else {
            ClickGuiModuleSearchBehavior.matchingModules(moduleSearchIndex(), query)
        }
        visibleSnapshot = ClickGuiScreenCategoryBehavior.orderFavoriteModules(matches)
        visibleCategory = category
        visibleRawQuery = rawQuery
        visibleFilterId = filterId
        visibleFavoritesRevision = favoritesRevision
        return visibleSnapshot
    }

    fun invalidateVisibleModules() {
        visibleCategory = null
    }

    /** Drops the cached index and results; the next query is built for the workspace that is showing. */
    fun invalidate() {
        searchIndex = null
        visibleCategory = null
    }

    /** A search only finds modules of the workspace that is showing. */
    private fun moduleSearchIndex(): List<ClickGuiSearchEntry> {
        searchIndex?.let { return it }
        val workspace = ClickGuiState.workspace()
        return ClickGuiModuleSearchBehavior
            .buildSearchIndex(ModuleManager.modules().filter { it.category().workspace == workspace })
            .also { searchIndex = it }
    }
}
