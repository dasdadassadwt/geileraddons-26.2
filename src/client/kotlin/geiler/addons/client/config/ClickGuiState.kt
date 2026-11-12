package geiler.addons.client.config

import geiler.addons.client.macro.MacroStep
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import java.util.Collections
import java.util.EnumMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

/**
 * View state shared by GUI screens in one game session. Persisted positions and category selection
 * are copied to ModConfig; filter, expansion, and fold state intentionally remain in memory only.
 */
object ClickGuiState {
    private var category: Category? = Category.values()[0]
    private var openModule: Module? = null
    private var expandedColor: ColorSetting? = null
    private var settingsScroll = 0
    private var categoryScroll = 0
    private val gridScrollByCategory = EnumMap<Category, Int>(Category::class.java)

    /** Selected one-level module filter for each broad category; filter selections are session-only. */
    private val moduleFilterByCategory = EnumMap<Category, String>(Category::class.java)

    /** Expanded category parents. Expansion is a view preference and remains session-only. */
    private val expandedCategories: MutableSet<Category> = LinkedHashSet()

    /** Stable category enum names persisted for the Click GUI's favorites. */
    private val favoriteCategoryIds: MutableSet<String> = LinkedHashSet()

    /** Stable module config names persisted for the Click GUI's favorites. */
    private val favoriteModuleIds: MutableSet<String> = LinkedHashSet()

    /** Expanded section keys. This stays in memory so each fresh game session starts folded. */
    private val expandedGroups: MutableSet<String> = LinkedHashSet()

    /** Expanded control blocks, weakly held so removed macro nodes do not accumulate for the session. */
    private val expandedMacroNodes: MutableSet<MacroStep> =
        Collections.newSetFromMap(WeakHashMap<MacroStep, Boolean>())

    @JvmStatic
    fun category(): Category? = category

    @JvmStatic
    fun setCategory(value: Category?) {
        category = value
    }

    @JvmStatic
    fun openModule(): Module? = openModule

    @JvmStatic
    fun setOpenModule(value: Module?) {
        openModule = value
    }

    @JvmStatic
    fun expandedColor(): ColorSetting? = expandedColor

    @JvmStatic
    fun setExpandedColor(value: ColorSetting?) {
        expandedColor = value
    }

    @JvmStatic
    fun settingsScroll(): Int = settingsScroll

    @JvmStatic
    fun setSettingsScroll(value: Int) {
        settingsScroll = value
    }

    @JvmStatic
    fun categoryScroll(): Int = categoryScroll

    @JvmStatic
    fun setCategoryScroll(value: Int) {
        categoryScroll = maxOf(0, value)
    }

    @JvmStatic
    fun gridScroll(value: Category?): Int = value?.let { gridScrollByCategory.getOrDefault(it, 0) } ?: 0

    @JvmStatic
    fun setGridScroll(category: Category?, value: Int) {
        if (category == null) return
        gridScrollByCategory[category] = maxOf(0, value)
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
    fun gridScrolls(): Map<Category, Int> = java.util.Map.copyOf(gridScrollByCategory)

    @JvmStatic
    fun setGridScrolls(values: Map<String, Int?>?) {
        gridScrollByCategory.clear()
        if (values == null) return
        for (category in Category.values()) {
            val saved = values[category.name]
            if (saved != null) setGridScroll(category, saved)
        }
    }

    @JvmStatic
    fun isFavorite(value: Category?): Boolean = value != null && favoriteCategoryIds.contains(value.name)

    @JvmStatic
    fun toggleFavorite(value: Category?) {
        if (value == null) return
        if (!favoriteCategoryIds.remove(value.name)) favoriteCategoryIds.add(value.name)
    }

    @JvmStatic
    fun favoriteCategoryIds(): Set<String> = java.util.Set.copyOf(favoriteCategoryIds)

    @JvmStatic
    fun setFavoriteCategoryIds(values: Collection<String?>?) {
        favoriteCategoryIds.clear()
        if (values == null) return
        for (value in Category.values()) {
            if (values.contains(value.name)) favoriteCategoryIds.add(value.name)
        }
    }

    @JvmStatic
    fun isFavorite(value: Module?): Boolean = value != null && favoriteModuleIds.contains(value.configName())

    @JvmStatic
    fun toggleFavorite(value: Module?) {
        if (value == null) return
        val stableId = value.configName()
        if (!favoriteModuleIds.remove(stableId)) favoriteModuleIds.add(stableId)
    }

    @JvmStatic
    fun favoriteModuleIds(): Set<String> = java.util.Set.copyOf(favoriteModuleIds)

    @JvmStatic
    fun setFavoriteModuleIds(values: Collection<String?>?) {
        favoriteModuleIds.clear()
        if (values == null) return
        for (value in values) {
            if (value != null && value.isNotBlank()) favoriteModuleIds.add(value)
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
