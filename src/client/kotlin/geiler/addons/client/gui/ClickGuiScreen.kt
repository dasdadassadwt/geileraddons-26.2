package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Workspace
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleCategoryFilter
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/** Pure responsive category-rail geometry shared with the Click GUI screen. */
object ClickGuiScreenGeometry {
    @JvmStatic
    fun categoryRowHeightForPanelHeight(panelHeight: Int): Int =
        Math.max(24, Math.round(Math.max(1, panelHeight) * 0.05f))

    @JvmStatic
    fun categoryContentHeightForPanelHeight(panelHeight: Int): Int =
        categoryContentHeightForPanelHeight(panelHeight, Category.of(Workspace.NORMAL).size)

    @JvmStatic
    fun categoryContentHeightForPanelHeight(panelHeight: Int, rowCount: Int): Int {
        val row = categoryRowHeightForPanelHeight(panelHeight)
        return Math.max(8, Math.round(Math.max(1, panelHeight) * 0.034f)) +
            Math.max(0, rowCount) * row
    }

    @JvmStatic
    fun categoryMaxScroll(panelHeight: Int, viewportHeight: Int): Int =
        categoryMaxScroll(panelHeight, viewportHeight, Category.of(Workspace.NORMAL).size)

    @JvmStatic
    fun categoryMaxScroll(panelHeight: Int, viewportHeight: Int, rowCount: Int): Int =
        Math.max(
            0,
            categoryContentHeightForPanelHeight(panelHeight, rowCount) - Math.max(1, viewportHeight),
        )
}

enum class ClickGuiScrollTarget {
    CHANGELOG,
    CATEGORY,
    SETTINGS,
    GRID,
    NONE,
}

/** Category rail and favorite ordering rules used by the Click GUI screen. */
object ClickGuiScreenCategoryBehavior {
    @JvmStatic
    fun scrollTarget(
        changelogOpen: Boolean,
        categoryHovered: Boolean,
        settingsOpen: Boolean,
        settingsHovered: Boolean,
        gridHovered: Boolean,
    ): ClickGuiScrollTarget = when {
        changelogOpen -> ClickGuiScrollTarget.CHANGELOG
        categoryHovered -> ClickGuiScrollTarget.CATEGORY
        settingsOpen -> if (settingsHovered) ClickGuiScrollTarget.SETTINGS else ClickGuiScrollTarget.NONE
        gridHovered -> ClickGuiScrollTarget.GRID
        else -> ClickGuiScrollTarget.NONE
    }

    @JvmStatic
    fun isModuleCardVisible(module: Module?): Boolean =
        module != null && module !== VisualModule.INSTANCE && module !== GeneralModule.INSTANCE &&
            module !== geiler.addons.client.module.impl.CosmeticsModule.INSTANCE

    @JvmStatic
    fun orderFavoriteModules(modules: List<Module>): List<Module> {
        val favorites = ArrayList<Module>()
        val remaining = ArrayList<Module>()
        for (module in modules) {
            if (ClickGuiState.isFavorite(module)) favorites.add(module) else remaining.add(module)
        }
        favorites.sortWith { first, second ->
            val byName = String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name())
            if (byName != 0) byName else first.configName().compareTo(second.configName())
        }
        favorites.addAll(remaining)
        return java.util.List.copyOf(favorites)
    }

    @JvmStatic
    fun orderFavoriteCategories(categories: Array<Category>): List<Category> {
        val favorites = ArrayList<Category>()
        val remaining = ArrayList<Category>()
        for (category in categories) {
            if (ClickGuiState.isFavorite(category)) favorites.add(category) else remaining.add(category)
        }
        favorites.sortWith { first, second ->
            val byLabel = String.CASE_INSENSITIVE_ORDER.compare(first.displayName(), second.displayName())
            if (byLabel != 0) byLabel else first.name.compareTo(second.name)
        }
        favorites.addAll(remaining)
        return java.util.List.copyOf(favorites)
    }

    @JvmStatic
    fun categoryEntries(
        categories: List<Category>,
        expandedCategories: Set<Category>?,
    ): List<Any> {
        val entries = ArrayList<Any>()
        for (category in categories) {
            entries.add(ClickGuiScreen.CategoryEntry(category, ModuleCategoryFilter.resolve(category, "all")))
            if (expandedCategories != null && expandedCategories.contains(category)) {
                for (child in ModuleCategoryFilter.children(category)) {
                    entries.add(ClickGuiScreen.CategoryEntry(category, child))
                }
            }
        }
        return java.util.List.copyOf(entries)
    }

    @JvmStatic
    fun categoryEntrySelected(
        entry: Any?,
        selectedCategory: Category?,
        filterId: String?,
        expanded: Boolean,
    ): Boolean {
        val categoryEntry = entry as? ClickGuiScreen.CategoryEntry ?: return false
        if (categoryEntry.category() !== selectedCategory) return false
        val activeFilterId = ModuleCategoryFilter.resolve(categoryEntry.category(), filterId).id()
        return categoryEntry.filter().id() == activeFilterId ||
            categoryEntry.isParent() && !expanded && activeFilterId != "all"
    }

    /** Shows a retained child filter on its parent while the child row is folded away. */
    @JvmStatic
    fun categoryEntryLabel(
        entry: Any?,
        filterId: String?,
        expanded: Boolean,
    ): String {
        val categoryEntry = entry as? ClickGuiScreen.CategoryEntry ?: return ""
        if (!categoryEntry.isParent()) return requireNotNull(categoryEntry.filter().label())
        val label = categoryEntry.category().displayName()
        if (expanded) return label
        val activeFilter = ModuleCategoryFilter.resolve(categoryEntry.category(), filterId)
        return if (activeFilter.id() == "all") label else "$label · ${activeFilter.label()}"
    }

    @JvmStatic
    fun categorySelectionPlan(
        category: Category,
        filterId: String,
        selectedCategory: Category?,
        currentFilterId: String,
    ): ClickGuiCategorySelectionPlan = ClickGuiCategorySelectionPlan(
        category,
        filterId,
        !filterId.equals(currentFilterId),
        category !== selectedCategory,
    )

    @JvmStatic
    fun toggleCategoryFavorite(category: Category?) {
        ClickGuiState.toggleFavorite(category)
    }

    @JvmStatic
    fun categoryScrollAfterWheel(currentScroll: Int, notch: Int, maxScroll: Int): Int =
        Math.max(0, Math.min(maxScroll, currentScroll - notch))
}

data class ClickGuiCategorySelectionPlan(
    val category: Category,
    val filterId: String,
    val filterChanged: Boolean,
    val categoryChanged: Boolean,
)

/** Drawing helpers shared by the Click GUI category rail and settings controls. */
object ClickGuiScreenRenderBehavior {
    /** Side of the smoothed toolbar icon PNGs (octicon-gear-16 and friends); the title screen reuses the gear. */
    const val TOOLBAR_ICON_TEXTURE_SIZE = 128

    /** Side of the favorite-heart PNGs (smoothed, `blur` in their .mcmeta). */
    const val FAVORITE_TEXTURE_SIZE = 64

    @JvmStatic
    fun drawScaledText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        color: Int,
        scale: Float,
    ) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
    }

    @JvmStatic
    fun drawCenteredScaledText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        centerX: Int,
        y: Int,
        color: Int,
        scale: Float,
    ) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        graphics.centeredText(font, text, 0, 0, color)
        pose.popMatrix()
    }

    @JvmStatic
    fun textFit(font: Font, text: String?, width: Int): String {
        if (text == null || width <= 0) return ""
        if (font.width(text) <= width) return text
        val ellipsis = "…"
        val available = Math.max(0, width - font.width(ellipsis))
        return font.plainSubstrByWidth(text, available) + ellipsis
    }

    @JvmStatic
    fun renderFavoriteHeart(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        favorite: Boolean,
        hovered: Boolean,
        favoriteColor: Int,
        primaryTextColor: Int,
        mutedTextColor: Int,
        filledIcon: Identifier,
        outlineIcon: Identifier,
    ) {
        val iconSize = Math.max(11, Math.min(17, Math.round(Math.min(width, height) * 0.64f)))
        val left = x + (width - iconSize) / 2
        val top = y + (height - iconSize) / 2
        val color = if (favorite) favoriteColor else if (hovered) primaryTextColor else mutedTextColor
        val icon = if (favorite) filledIcon else outlineIcon
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(left.toFloat(), top.toFloat())
        pose.scale(iconSize / FAVORITE_TEXTURE_SIZE.toFloat(), iconSize / FAVORITE_TEXTURE_SIZE.toFloat())
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, icon, 0, 0, 0f, 0f,
            FAVORITE_TEXTURE_SIZE, FAVORITE_TEXTURE_SIZE, FAVORITE_TEXTURE_SIZE, FAVORITE_TEXTURE_SIZE, color,
        )
        pose.popMatrix()
    }

    @JvmStatic
    fun renderCategoryRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        labelText: String,
        selected: Boolean,
        showSelection: Boolean,
        hovered: Boolean,
        favoriteX: Int,
        favoriteY: Int,
        favoriteWidth: Int,
        favoriteHeight: Int,
        favorite: Boolean,
        favoriteHovered: Boolean,
        radius: Int,
        textHeight: Int,
        textScale: Float,
        favoriteColor: Int,
        favoriteFilledIcon: Identifier,
        favoriteOutlineIcon: Identifier,
    ) {
        if (showSelection) {
            GuiTheme.roundedRect(
                graphics,
                x + 7,
                y,
                width - 7,
                height,
                radius,
                GuiTheme.withOpacity(GuiTheme.CATEGORY_SELECTED, 0.19f),
            )
        } else if (hovered) {
            GuiTheme.roundedRect(graphics, x + 7, y, width - 7, height, radius, GuiTheme.CATEGORY_HOVER)
        }

        val color = if (selected) GuiTheme.SLIDER_FILL else GuiTheme.TEXT_PRIMARY
        val labelX = x + Math.max(11, Math.round(width * 0.10f))
        val labelRight = favoriteX - 5
        val labelWidth = Math.max(1, labelRight - labelX)
        val label = textFit(font, labelText, Math.max(1, Math.round(labelWidth / textScale)))
        drawScaledText(
            graphics,
            font,
            label,
            labelX,
            y + (height - Math.round(textHeight * textScale)) / 2,
            color,
            textScale,
        )
        renderFavoriteHeart(
            graphics,
            favoriteX,
            favoriteY,
            favoriteWidth,
            favoriteHeight,
            favorite,
            favoriteHovered,
            favoriteColor,
            GuiTheme.TEXT_PRIMARY,
            GuiTheme.TEXT_MUTED,
            favoriteFilledIcon,
            favoriteOutlineIcon,
        )
    }

    @JvmStatic
    fun groupHeaderColor(hovered: Boolean, pulse: Float): Int = when {
        hovered -> GuiTheme.CARD_BG_HOVER
        pulse > 0.0f -> GuiTheme.lerpColor(GuiTheme.GROUP_HEADER, GuiTheme.CARD_BG_HOVER, pulse * 0.8f)
        else -> GuiTheme.GROUP_HEADER
    }

    @JvmStatic
    fun settingsRowHighlightColor(hovered: Boolean, pulse: Float): Int =
        if (hovered) GuiTheme.CATEGORY_HOVER else GuiTheme.withOpacity(GuiTheme.PANEL_HIGHLIGHT, pulse * 0.35f)

    @JvmStatic
    fun choiceFieldColor(hovered: Boolean, pulse: Float): Int = when {
        hovered -> GuiTheme.BUTTON_HOVER
        pulse > 0.0f -> GuiTheme.lerpColor(GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER, pulse * 0.75f)
        else -> GuiTheme.BUTTON_BG
    }

    @JvmStatic
    fun actionButtonColor(hovered: Boolean, pulse: Float): Int = when {
        hovered -> GuiTheme.BUTTON_HOVER
        pulse > 0.0f -> GuiTheme.lerpColor(GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER, pulse * 0.8f)
        else -> GuiTheme.BUTTON_BG
    }

    @JvmStatic
    fun renderGroupSettingRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        boundsValue: Any,
        toggleBoundsValue: Any?,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean,
        pulse: Float,
        groupName: String,
        collapsed: Boolean,
        depth: Int,
        toggleOn: Boolean,
        toggleLead: Float,
        toggleTrail: Float,
        textScale: Float,
        textHeight: Int,
        groupIndent: Int,
        switchWidth: Int,
        radius: Int,
    ) {
        val bounds = boundsValue as ClickGuiScreen.Rect
        val toggleBounds = toggleBoundsValue as? ClickGuiScreen.Rect
        val hovered = hoverable && bounds.contains(mouseX.toDouble(), mouseY.toDouble())
        val indent = depth * groupIndent
        val headerColor = groupHeaderColor(hovered, pulse)
        GuiTheme.roundedRect(
            graphics,
            bounds.x() + 5 + indent,
            bounds.y() + 1,
            bounds.w() - 10 - indent,
            bounds.h() - 3,
            radius,
            headerColor,
        )
        drawScaledText(
            graphics,
            font,
            if (collapsed) "▸" else "▾",
            bounds.x() + 12 + indent,
            bounds.y() + (bounds.h() - textHeight) / 2,
            GuiTheme.TEXT_SECONDARY,
            textScale,
        )
        val labelWidth = bounds.w() - 28 - indent - if (toggleBounds == null) 0 else switchWidth + 18
        val label = textFit(font, groupName, moduleTextWidth(labelWidth, textScale))
        drawScaledText(
            graphics,
            font,
            label,
            bounds.x() + 24 + indent,
            bounds.y() + (bounds.h() - textHeight) / 2,
            GuiTheme.TEXT_PRIMARY,
            textScale,
        )
        if (toggleBounds != null) {
            GuiTheme.liquidSwitch(
                graphics,
                toggleBounds.x(),
                toggleBounds.y(),
                toggleBounds.w(),
                toggleBounds.h(),
                toggleOn,
                toggleLead,
                toggleTrail,
                false,
            )
        }
    }

    @JvmStatic
    fun renderToggleSettingRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        boundsValue: Any,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean,
        pulse: Float,
        displayName: String,
        toggleOn: Boolean,
        toggleLead: Float,
        toggleTrail: Float,
        textScale: Float,
        textHeight: Int,
        switchWidth: Int,
        switchHeight: Int,
        radius: Int,
    ) {
        val bounds = boundsValue as ClickGuiScreen.Rect
        val hovered = hoverable && bounds.contains(mouseX.toDouble(), mouseY.toDouble())
        if (hovered || pulse > 0.0f) {
            GuiTheme.roundedRect(
                graphics,
                bounds.x() + 9,
                bounds.y(),
                bounds.w() - 18,
                bounds.h() - 2,
                radius,
                settingsRowHighlightColor(hovered, pulse),
            )
        }
        val label = textFit(font, displayName, moduleTextWidth(bounds.w() - switchWidth - 36, textScale))
        drawScaledText(
            graphics,
            font,
            label,
            bounds.x() + 16,
            bounds.y() + (bounds.h() - textHeight) / 2,
            GuiTheme.TEXT_SECONDARY,
            textScale,
        )
        val switchX = bounds.x() + bounds.w() - switchWidth - 14
        val switchY = bounds.y() + (bounds.h() - switchHeight) / 2
        GuiTheme.liquidSwitch(graphics, switchX, switchY, switchWidth, switchHeight, toggleOn, toggleLead, toggleTrail, false)
    }

    @JvmStatic
    fun renderChoiceSettingRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        boundsValue: Any,
        fieldValue: Any,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean,
        pulse: Float,
        displayName: String,
        displayValue: String,
        textScale: Float,
        textHeight: Int,
        radius: Int,
    ) {
        val bounds = boundsValue as ClickGuiScreen.Rect
        val field = fieldValue as ClickGuiScreen.Rect
        val hovered = hoverable && bounds.contains(mouseX.toDouble(), mouseY.toDouble())
        if (hovered) {
            GuiTheme.roundedRect(
                graphics,
                bounds.x() + 9,
                bounds.y(),
                bounds.w() - 18,
                bounds.h() - 2,
                radius,
                GuiTheme.CATEGORY_HOVER,
            )
        }
        val label = textFit(font, displayName, moduleTextWidth(bounds.w() - field.w() - 36, textScale))
        drawScaledText(
            graphics,
            font,
            label,
            bounds.x() + 16,
            bounds.y() + (bounds.h() - textHeight) / 2,
            GuiTheme.TEXT_SECONDARY,
            textScale,
        )
        val fieldColor = choiceFieldColor(hovered, pulse)
        GuiTheme.roundedRect(graphics, field.x(), field.y(), field.w(), field.h(), 4, fieldColor)
        val value = textFit(font, displayValue, field.w() - 12)
        drawCenteredScaledText(
            graphics,
            font,
            "‹ $value ›",
            field.x() + field.w() / 2,
            field.y() + (field.h() - textHeight) / 2,
            GuiTheme.TEXT_ON_ACCENT,
            textScale,
        )
    }

    @JvmStatic
    fun renderActionSettingRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        boundsValue: Any,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean,
        pulse: Float,
        actionLabel: String,
        textScale: Float,
        textHeight: Int,
        radius: Int,
    ) {
        val bounds = boundsValue as ClickGuiScreen.Rect
        val hovered = hoverable && bounds.contains(mouseX.toDouble(), mouseY.toDouble())
        val background = actionButtonColor(hovered, pulse)
        GuiTheme.roundedRect(
            graphics,
            bounds.x() + 14,
            bounds.y() + 2,
            bounds.w() - 28,
            bounds.h() - 6,
            radius,
            background,
        )
        val label = textFit(font, actionLabel, moduleTextWidth(bounds.w() - 36, textScale))
        drawCenteredScaledText(
            graphics,
            font,
            label,
            bounds.x() + bounds.w() / 2,
            bounds.y() + (bounds.h() - textHeight) / 2,
            GuiTheme.TEXT_ON_ACCENT,
            textScale,
        )
    }

    private fun moduleTextWidth(pixels: Int, textScale: Float): Int =
        Math.max(1, Math.ceil((pixels.toFloat() / textScale).toDouble()).toInt())
}
