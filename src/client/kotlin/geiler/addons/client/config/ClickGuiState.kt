package geiler.addons.client.config

import geiler.addons.client.macro.MacroStep
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.Workspace
import java.util.Collections
import java.util.EnumMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

/**
 * View state shared by GUI screens in one game session. Persisted positions and category selection
 * are copied to ModConfig; filter, expansion, and fold state intentionally remain in memory only.
 *
 * The persisted part is kept per [Workspace]: the Normal workspace is saved in `config.json` as before and the Macros
 * workspace in its own file, so each keeps its own category, scroll positions and favorites. The functions without a
 * workspace argument act on the workspace that is showing.
 */
object ClickGuiState {
    /** Everything the Click GUI remembers about one workspace. */
    private class Bank(workspace: Workspace) {
        var category: Category? = Category.of(workspace)[0]
        var openModule: Module? = null
        var expandedColor: ColorSetting? = null
        var settingsScroll = 0
        var categoryScroll = 0
        val gridScrollByCategory = EnumMap<Category, Int>(Category::class.java)

        /** Stable category enum names persisted for the Click GUI's favorites. */
        val favoriteCategoryIds: MutableSet<String> = LinkedHashSet()

        /** Stable module config names persisted for the Click GUI's favorites. */
        val favoriteModuleIds: MutableSet<String> = LinkedHashSet()
    }

    private val banks = EnumMap<Workspace, Bank>(Workspace::class.java).also { map ->
        for (workspace in Workspace.values()) map[workspace] = Bank(workspace)
    }
    private var workspace = Workspace.NORMAL

    private fun bank(): Bank = banks.getValue(workspace)

    private fun bankFor(category: Category): Bank = banks.getValue(category.workspace)

    /** Selected one-level module filter for each broad category; filter selections are session-only. */
    private val moduleFilterByCategory = EnumMap<Category, String>(Category::class.java)

    /** Expanded category parents. Expansion is a view preference and remains session-only. */
    private val expandedCategories: MutableSet<Category> = LinkedHashSet()

    /** Expanded section keys. This stays in memory so each fresh game session starts folded. */
    private val expandedGroups: MutableSet<String> = LinkedHashSet()

    /** Expanded control blocks, weakly held so removed macro nodes do not accumulate for the session. */
    private val expandedMacroNodes: MutableSet<MacroStep> =
        Collections.newSetFromMap(WeakHashMap<MacroStep, Boolean>())

    @JvmStatic
    fun workspace(): Workspace = workspace

    @JvmStatic
    fun setWorkspace(value: Workspace) {
        workspace = value
    }

    /** Runs [action] as if [target] were showing, then puts the showing workspace back. */
    @JvmStatic
    fun runIn(target: Workspace, action: Runnable) {
        val previous = workspace
        workspace = target
        try {
            action.run()
        } finally {
            workspace = previous
        }
    }

    @JvmStatic
    fun category(): Category? = bank().category

    /** A category goes to its own workspace's state, whichever workspace is showing. */
    @JvmStatic
    fun setCategory(value: Category?) {
        if (value == null) bank().category = null else bankFor(value).category = value
    }

    @JvmStatic
    fun openModule(): Module? = bank().openModule

    @JvmStatic
    fun setOpenModule(value: Module?) {
        bank().openModule = value
    }

    @JvmStatic
    fun expandedColor(): ColorSetting? = bank().expandedColor

    @JvmStatic
    fun setExpandedColor(value: ColorSetting?) {
        bank().expandedColor = value
    }

    @JvmStatic
    fun settingsScroll(): Int = bank().settingsScroll

    @JvmStatic
    fun setSettingsScroll(value: Int) {
        bank().settingsScroll = value
    }

    @JvmStatic
    fun categoryScroll(): Int = bank().categoryScroll

    @JvmStatic
    fun setCategoryScroll(value: Int) {
        bank().categoryScroll = maxOf(0, value)
    }

    @JvmStatic
    fun gridScroll(value: Category?): Int = value?.let { bankFor(it).gridScrollByCategory.getOrDefault(it, 0) } ?: 0

    @JvmStatic
    fun setGridScroll(category: Category?, value: Int) {
        if (category == null) return
        bankFor(category).gridScrollByCategory[category] = maxOf(0, value)
    }

    @JvmStatic
    fun moduleFilter(category: Category?): String =
        category?.let { moduleFilterByCategory.getOrDefault(it, "all") } ?: "all"

    @JvmStatic
    fun setModuleFilter(category: Category?, filterId: String?) {
        if (category == null) return
        moduleFilterByCategory[category] = if (filterId.isNullOrBlank()) "all" else filterId
    }

    @JvmStatic
    fun isCategoryExpanded(category: Category?): Boolean = category != null && expandedCategories.contains(category)

    @JvmStatic
    fun expandedCategories(): Set<Category> = java.util.Set.copyOf(expandedCategories)

    @JvmStatic
    fun toggleCategoryExpanded(category: Category?) {
        if (category == null) return
        if (!expandedCategories.remove(category)) expandedCategories.add(category)
    }

    @JvmStatic
    fun gridScrolls(): Map<Category, Int> = java.util.Map.copyOf(bank().gridScrollByCategory)

    @JvmStatic
    fun setGridScrolls(values: Map<String, Int?>?) {
        bank().gridScrollByCategory.clear()
        if (values == null) return
        for (category in Category.of(workspace)) {
            val saved = values[category.name]
            if (saved != null) setGridScroll(category, saved)
        }
    }

    @JvmStatic
    fun isFavorite(value: Category?): Boolean = value != null && bankFor(value).favoriteCategoryIds.contains(value.name)

    @JvmStatic
    fun toggleFavorite(value: Category?) {
        if (value == null) return
        val ids = bankFor(value).favoriteCategoryIds
        if (!ids.remove(value.name)) ids.add(value.name)
    }

    @JvmStatic
    fun favoriteCategoryIds(): Set<String> = java.util.Set.copyOf(bank().favoriteCategoryIds)

    @JvmStatic
    fun setFavoriteCategoryIds(values: Collection<String?>?) {
        val ids = bank().favoriteCategoryIds
        ids.clear()
        if (values == null) return
        for (value in Category.of(workspace)) {
            if (values.contains(value.name)) ids.add(value.name)
        }
    }

    @JvmStatic
    fun isFavorite(value: Module?): Boolean =
        value != null && bankFor(value.category()).favoriteModuleIds.contains(value.configName())

    @JvmStatic
    fun toggleFavorite(value: Module?) {
        if (value == null) return
        val stableId = value.configName()
        val ids = bankFor(value.category()).favoriteModuleIds
        if (!ids.remove(stableId)) ids.add(stableId)
    }

    @JvmStatic
    fun favoriteModuleIds(): Set<String> = java.util.Set.copyOf(bank().favoriteModuleIds)

    @JvmStatic
    fun setFavoriteModuleIds(values: Collection<String?>?) {
        val ids = bank().favoriteModuleIds
        ids.clear()
        if (values == null) return
        for (value in values) {
            if (value != null && value.isNotBlank()) ids.add(value)
        }
    }

    /**
     * Unnamed sections have no header to click, so they can never be folded shut. Named sections
     * begin folded at process startup; their open state is retained only until the game exits.
     */
    @JvmStatic
    fun isCollapsed(module: Module, group: SettingGroup): Boolean {
        if (group.name == null) return false
        return !expandedGroups.contains(key(module, group))
    }

    @JvmStatic
    fun toggleCollapsed(module: Module, group: SettingGroup) {
        if (group.name == null) return
        val key = key(module, group)
        if (!expandedGroups.remove(key)) expandedGroups.add(key)
    }

    @JvmStatic
    fun macroNodeExpanded(step: MacroStep?): Boolean = step != null && expandedMacroNodes.contains(step)

    /** New nodes that hold other nodes start open so their bag is visible and droppable. */
    @JvmStatic
    fun expandNewMacroNode(step: MacroStep?): MacroStep? {
        if (step is MacroStep.IfElse || step is MacroStep.Repeat || step is MacroStep.RepeatUntil || step is MacroStep.Switch) {
            expandedMacroNodes.add(step)
        }
        return step
    }

    @JvmStatic
    fun toggleMacroNode(step: MacroStep?) {
        if (step == null) return
        if (!expandedMacroNodes.remove(step)) expandedMacroNodes.add(step)
    }

    /** Qualified by module so two modules can name a section the same thing. */
    private fun key(module: Module, group: SettingGroup): String =
        module.name() + "." + (group.stateKey ?: group.name)
}
