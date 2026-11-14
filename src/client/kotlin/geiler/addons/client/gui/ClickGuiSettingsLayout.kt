package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.DebugState
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.RangeSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting

/** Shared Click GUI row metrics and the recursive settings-panel layout. */
internal object ClickGuiSettingsLayout {
    const val ROW_HEIGHT = 22
    const val GROUP_HEADER_HEIGHT = 18
    const val GROUP_INDENT = 10
    const val PADDING = 8

    const val PICKER_INSET = 16
    const val PICKER_SQUARE_HEIGHT = 64
    const val PICKER_BAR_HEIGHT = 8
    const val PICKER_GAP = 5
    const val PICKER_HEX_HEIGHT = 13
    const val PICKER_HEX_WIDTH = 74
    const val PICKER_RGB_HEIGHT = PICKER_SQUARE_HEIGHT + PICKER_GAP + PICKER_BAR_HEIGHT +
        PICKER_GAP + PICKER_HEX_HEIGHT + PADDING
    const val PICKER_HEIGHT = PICKER_RGB_HEIGHT + PICKER_BAR_HEIGHT + PICKER_GAP

    const val PREVIEW_GAP = 6
    const val SWITCH_WIDTH = 20
    const val SWITCH_HEIGHT = 11
    const val TEXT_FIELD_WIDTH = 64
    const val TEXT_FIELD_HEIGHT = 13
    const val NUMBER_FIELD_WIDTH = 64
    const val CHOICE_FIELD_WIDTH = 92
    const val CHOICE_FIELD_HEIGHT = 15

    @JvmStatic
    fun colorRowHeight(setting: ColorSetting, expandedColor: ColorSetting?): Int =
        ROW_HEIGHT + if (setting == expandedColor) pickerHeight(setting) else 0

    @JvmStatic
    fun pickerHeight(setting: ColorSetting): Int =
        if (setting.alphaEditable()) PICKER_HEIGHT else PICKER_RGB_HEIGHT

    @JvmStatic
    fun previewHeight(module: Module, width: Int, availableHeight: Int): Int =
        (module as? ModulePreview)?.let { maxOf(1, it.previewHeight(width, availableHeight)) } ?: 0

    @JvmStatic
    fun previewOffset(module: Module, width: Int, availableHeight: Int): Int =
        if (module is ModulePreview && module.previewGroupName() == null)
            previewHeight(module, width, availableHeight) + PREVIEW_GAP
        else 0

    /** [startY] is the first row position after any ungrouped preview and applied scroll offset. */
    @JvmStatic
    fun layoutRows(
        module: Module,
        x: Int,
        startY: Int,
        expandedColor: ColorSetting?,
        moduleWidth: Int,
        availableHeight: Int,
    ): List<Row> {
        val rows = ArrayList<Row>()
        var cursorY = startY
        for (group in flattenSmallGroups(module.groups())) {
            cursorY = layoutGroup(module, group, x, cursorY, 0, moduleWidth, availableHeight, rows, expandedColor)
        }
        return java.util.List.copyOf(rows)
    }

    private const val LOOSE_GROUP_MAX_SETTINGS = 3
    private const val FLAT_MODULE_MAX_SETTINGS = 8

    private fun isPlain(group: SettingGroup) = group.toggle == null && group.children.isEmpty() &&
        group.stateKey == null && !group.debugOnly

    private fun merged(groups: List<SettingGroup>): SettingGroup =
        SettingGroup(null, null, false, groups.flatMap { it.settings }, emptyList(), false, null)

    /**
     * Presentation only: sections with a few plain settings do not earn a collapsible heading. A module
     * with at most [FLAT_MODULE_MAX_SETTINGS] plain settings shows none; otherwise adjacent sections of at most
     * [LOOSE_GROUP_MAX_SETTINGS] settings merge into one headerless block. Switched, nested, keyed and
     * debug sections keep their headings.
     */
    @JvmStatic
    fun flattenSmallGroups(groups: List<SettingGroup>): List<SettingGroup> {
        if (groups.all(::isPlain) && groups.sumOf { it.settings.size } <= FLAT_MODULE_MAX_SETTINGS) {
            return if (groups.size == 1 && groups[0].name == null) groups else listOf(merged(groups))
        }
        val result = ArrayList<SettingGroup>(groups.size)
        val run = ArrayList<SettingGroup>()
        fun flush() {
            if (run.isEmpty()) return
            result.add(if (run.size == 1 && run[0].name == null) run[0] else merged(run))
            run.clear()
        }
        for (group in groups) {
            if (isPlain(group) && group.settings.size <= LOOSE_GROUP_MAX_SETTINGS) run.add(group)
            else { flush(); result.add(group) }
        }
        flush()
        return result
    }

    @JvmStatic
    fun visibleSetting(module: Module, setting: Setting?): Boolean =
        setting != null && (DebugState.enabled() || !setting.isDebugOnly()) && module.isSettingVisible(setting)

    @JvmStatic
    fun visibleGroup(module: Module, group: SettingGroup): Boolean {
        if (!DebugState.enabled() && group.debugOnly) return false
        if (group.name != null && group.toggle != null && visibleSetting(module, group.toggle)) return true
        for (setting in group.settings) {
            if (visibleSetting(module, setting)) return true
        }
        for (child in group.children) {
            if (visibleGroup(module, child)) return true
        }
        // A section that holds only a custom row has no setting to show but is still there.
        return group.name != null && module.extraPreviews().any {
            it.previewEnabled() && it.previewGroupName() == group.name
        }
    }

    private fun layoutGroup(
        module: Module,
        group: SettingGroup,
        x: Int,
        startY: Int,
        depth: Int,
        moduleWidth: Int,
        availableHeight: Int,
        rows: MutableList<Row>,
        expandedColor: ColorSetting?,
    ): Int {
        if (!visibleGroup(module, group)) return startY
        var cursorY = startY

        if (group.name != null) {
            val bounds = ClickGuiScreen.Rect(x, cursorY, moduleWidth, GROUP_HEADER_HEIGHT)
            val toggle = if (group.toggle == null || !visibleSetting(module, group.toggle)) null else
                ClickGuiScreen.Rect(
                    bounds.x() + bounds.w() - SWITCH_WIDTH - 14,
                    bounds.y() + (bounds.h() - SWITCH_HEIGHT) / 2,
                    SWITCH_WIDTH,
                    SWITCH_HEIGHT,
                )
            val collapsed = ClickGuiState.isCollapsed(module, group)
            rows.add(GroupRow(group, bounds, toggle, depth, collapsed))
            cursorY += GROUP_HEADER_HEIGHT
            // Folding a section takes all nested content with it.
            if (collapsed) return cursorY
        }

        for (setting in group.settings) {
            if (!visibleSetting(module, setting)) continue
            when (setting) {
                is ColorSetting -> {
                    val rowHeight = colorRowHeight(setting, expandedColor)
                    rows.add(ColorRow(
                        setting,
                        ClickGuiScreen.Rect(x, cursorY, moduleWidth, rowHeight),
                        picker(setting, x, cursorY, moduleWidth, expandedColor),
                    ))
                    cursorY += rowHeight
                }
                is NumberSetting -> {
                    val bounds = ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT)
                    val fieldWidth = minOf(NUMBER_FIELD_WIDTH, maxOf(1, moduleWidth - 18))
                    val field = ClickGuiScreen.Rect(
                        x + moduleWidth - fieldWidth - 14,
                        cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2,
                        fieldWidth,
                        TEXT_FIELD_HEIGHT,
                    )
                    val sliderRight = field.x() - 8
                    val slider = ClickGuiScreen.Rect(
                        x + 16,
                        cursorY + 16,
                        maxOf(1, sliderRight - (x + 16)),
                        4,
                    )
                    rows.add(NumberRow(setting, bounds, slider, field))
                    cursorY += ROW_HEIGHT
                }
                is RangeSetting -> {
                    val bounds = ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT)
                    val fieldWidth = minOf(NUMBER_FIELD_WIDTH * 2, maxOf(1, moduleWidth - 18))
                    val field = ClickGuiScreen.Rect(
                        x + moduleWidth - fieldWidth - 14,
                        cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2,
                        fieldWidth,
                        TEXT_FIELD_HEIGHT,
                    )
                    val sliderRight = field.x() - 8
                    val slider = ClickGuiScreen.Rect(x + 16, cursorY + 16, maxOf(1, sliderRight - (x + 16)), 4)
                    rows.add(RangeRow(setting, bounds, slider, field))
                    cursorY += ROW_HEIGHT
                }
                is BooleanSetting -> {
                    rows.add(ToggleRow(setting, ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT)))
                    cursorY += ROW_HEIGHT
                }
                is ChoiceSetting -> {
                    val fieldWidth = minOf(CHOICE_FIELD_WIDTH, maxOf(1, moduleWidth - 18))
                    val field = ClickGuiScreen.Rect(
                        x + moduleWidth - fieldWidth - 14,
                        cursorY + (ROW_HEIGHT - CHOICE_FIELD_HEIGHT) / 2,
                        fieldWidth,
                        CHOICE_FIELD_HEIGHT,
                    )
                    rows.add(ChoiceRow(
                        setting,
                        ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT),
                        field,
                    ))
                    cursorY += ROW_HEIGHT
                }
                is TextSetting -> {
                    val fieldWidth = minOf(TEXT_FIELD_WIDTH, maxOf(1, moduleWidth - 18))
                    val field = ClickGuiScreen.Rect(
                        x + moduleWidth - fieldWidth - 14,
                        cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2,
                        fieldWidth,
                        TEXT_FIELD_HEIGHT,
                    )
                    rows.add(TextRow(setting, ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT), field))
                    cursorY += ROW_HEIGHT
                }
                is ModuleAction -> {
                    rows.add(ActionRow(setting, ClickGuiScreen.Rect(x, cursorY, moduleWidth, ROW_HEIGHT)))
                    cursorY += ROW_HEIGHT
                }
            }
        }

        for (preview in listOfNotNull(module as? ModulePreview) + module.extraPreviews()) {
            if (preview.previewEnabled() && preview.previewGroupName() != null && preview.previewGroupName() == group.name) {
                val contentHeight = maxOf(1, preview.previewHeight(moduleWidth, availableHeight))
                rows.add(PreviewRow(preview, ClickGuiScreen.Rect(x, cursorY, moduleWidth, contentHeight)))
                cursorY += contentHeight + PREVIEW_GAP
            }
        }

        for (child in group.children) {
            cursorY = layoutGroup(
                module, child, x, cursorY, depth + 1, moduleWidth, availableHeight, rows, expandedColor,
            )
        }
        return cursorY
    }

    private fun picker(
        setting: ColorSetting,
        x: Int,
        cursorY: Int,
        moduleWidth: Int,
        expandedColor: ColorSetting?,
    ): Picker? {
        if (setting != expandedColor) return null
        val left = x + PICKER_INSET
        val width = maxOf(1, moduleWidth - PICKER_INSET * 2)
        val square = ClickGuiScreen.Rect(left, cursorY + ROW_HEIGHT, width, PICKER_SQUARE_HEIGHT)
        val hueY = square.y() + square.h() + PICKER_GAP
        val hue = ClickGuiScreen.Rect(left, hueY, width, PICKER_BAR_HEIGHT)
        val alphaY = hueY + PICKER_BAR_HEIGHT + PICKER_GAP
        val alpha = if (setting.alphaEditable()) ClickGuiScreen.Rect(left, alphaY, width, PICKER_BAR_HEIGHT) else null
        val hexY = if (setting.alphaEditable()) alphaY + PICKER_BAR_HEIGHT + PICKER_GAP else alphaY
        val hex = ClickGuiScreen.Rect(left, hexY, minOf(PICKER_HEX_WIDTH, width), PICKER_HEX_HEIGHT)
        return Picker(square, hue, alpha, hex)
    }
}
