package geiler.addons.client.gui

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting

/** A laid-out line in the Click GUI settings panel. */
internal sealed interface Row {
    fun getBounds(): ClickGuiScreen.Rect
}

/** The four hit areas of an expanded colour row. */
@JvmRecord
internal data class Picker(
    val square: ClickGuiScreen.Rect,
    val hue: ClickGuiScreen.Rect,
    val alpha: ClickGuiScreen.Rect?,
    val hex: ClickGuiScreen.Rect,
)

@JvmRecord
internal data class GroupRow(
    val group: SettingGroup,
    val bounds: ClickGuiScreen.Rect,
    val toggle: ClickGuiScreen.Rect?,
    val depth: Int,
    val collapsed: Boolean,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class PreviewRow(
    val preview: ModulePreview,
    val bounds: ClickGuiScreen.Rect,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class ColorRow(
    val setting: ColorSetting,
    val bounds: ClickGuiScreen.Rect,
    val picker: Picker?,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class NumberRow(
    val setting: NumberSetting,
    val bounds: ClickGuiScreen.Rect,
    val slider: ClickGuiScreen.Rect?,
    val field: ClickGuiScreen.Rect?,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class ToggleRow(
    val setting: BooleanSetting,
    val bounds: ClickGuiScreen.Rect,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class ChoiceRow(
    val setting: ChoiceSetting,
    val bounds: ClickGuiScreen.Rect,
    val field: ClickGuiScreen.Rect?,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class TextRow(
    val setting: TextSetting,
    val bounds: ClickGuiScreen.Rect,
    val field: ClickGuiScreen.Rect?,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}

@JvmRecord
internal data class ActionRow(
    val action: ModuleAction,
    val bounds: ClickGuiScreen.Rect,
) : Row {
    override fun getBounds(): ClickGuiScreen.Rect = bounds
}
