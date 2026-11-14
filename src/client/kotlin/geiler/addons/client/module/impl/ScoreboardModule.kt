package geiler.addons.client.module.impl

import geiler.addons.client.gui.ScoreboardFontImportScreen
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.scoreboard.ScoreboardBackend
import geiler.addons.client.scoreboard.ScoreboardFontService
import geiler.addons.client.scoreboard.ScoreboardLayout
import geiler.addons.client.scoreboard.ScoreboardLine
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** Opt-in display-only replacement for the vanilla sidebar, configured from the Click GUI. */
class ScoreboardModule private constructor(
    settings: Settings = Settings(),
) : Module(
    "Custom Scoreboard",
    "Replace the sidebar with custom text and a chosen font, background, and border.",
    Category.VISUAL,
    false,
    *settings.allSettings(),
) {
    private val font = settings.font
    private val background = settings.background
    private val backgroundColor = settings.backgroundColor
    private val borderColor = settings.borderColor
    private val importFont = settings.importFont
    private val customRows = settings.customRows
    private var fontChoices: List<ScoreboardFontService.FontChoice> = emptyList()
    private var fontChoiceIds: List<String> = emptyList()
    private var lastLayoutState: EditableState? = null
    private var lastSettingsState: EditableState? = null

    init {
        group(
            SettingGroup("Appearance", font, importFont, background, backgroundColor, borderColor),
            SettingGroup.folded("Custom Rows", *customRows),
        )
        ClientTickEvents.END_CLIENT_TICK.register { _ -> synchronizeSettings() }
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        val rowIndex = customRows.indexOfFirst { it === setting }
        if (rowIndex < 0) return super.isSettingVisible(setting)
        val lastUsedRow = customRows.indexOfLast { it.value().isNotBlank() }
        val availableRows = customRowCapacity(ScoreboardBackend.layout())
        return rowIndex < availableRows && rowIndex <= (lastUsedRow + 1).coerceAtMost(customRows.lastIndex)
    }

    private fun synchronizeSettings() {
        val layout = ScoreboardBackend.layout()
        prepareFontChoices(layout)

        val layoutState = layoutState(layout)
        val settingState = settingsState()
        val previousLayoutState = lastLayoutState
        val previousSettingsState = lastSettingsState
        if (previousLayoutState == null || previousSettingsState == null) {
            copyLayoutToSettings(layout)
            rememberCurrentState(layout)
            return
        }

        // Imports, resets, and legacy edits remain authoritative when they replace the stored layout.
        if (layoutState != previousLayoutState) {
            copyLayoutToSettings(layout)
            rememberCurrentState(layout)
            return
        }

        if (settingState != previousSettingsState) {
            applySettingsToLayout(layout)
            ScoreboardBackend.updateLayout(layout)
        }
        rememberCurrentState(layout)
    }

    private fun prepareFontChoices(layout: ScoreboardLayout) {
        if (fontChoices.isEmpty()) fontChoices = ScoreboardFontService.choices()
        val currentFont = layout.fontId().orEmpty()
        val choices = if (fontChoices.any { it.id == currentFont }) {
            fontChoices
        } else {
            fontChoices + ScoreboardFontService.FontChoice(currentFont, "Unavailable: $currentFont", false)
        }
        val nextIds = choices.map { it.id }
        if (nextIds != fontChoiceIds) {
            font.setChoices(choices.associate { it.id to it.label }, *nextIds.toTypedArray())
            fontChoiceIds = nextIds
        }
    }

    /** Refreshes the Click GUI font list after the import flow finishes on the client thread. */
    fun refreshFontChoices() {
        fontChoices = ScoreboardFontService.choices()
        fontChoiceIds = emptyList()
    }

    private fun copyLayoutToSettings(layout: ScoreboardLayout) {
        font.setValue(layout.fontId())
        background.setValue(layout.background()?.name ?: ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED.name)
        backgroundColor.setArgb(layout.backgroundColor())
        borderColor.setArgb(layout.borderColor())
        val lines = layout.lines().filter { it.type() == ScoreboardLine.Type.CUSTOM_TEXT }
        for (index in customRows.indices) {
            customRows[index].setValue(lines.getOrNull(index)?.text().orEmpty())
        }
    }

    private fun applySettingsToLayout(layout: ScoreboardLayout) {
        layout.setFontId(font.value())
        layout.setBackground(runCatching { ScoreboardLayout.BackgroundStyle.valueOf(background.value()) }
            .getOrDefault(ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED))
        layout.setBackgroundColor(backgroundColor.argb())
        layout.setBorderColor(borderColor.argb())

        val existingCustomRows = layout.lines().filter { it.type() == ScoreboardLine.Type.CUSTOM_TEXT }
        val otherLineCount = layout.lines().size - existingCustomRows.size
        val availableRows = (64 - otherLineCount).coerceAtLeast(0)
        val editedTexts = customRows.map { it.value().take(256) }.filter { it.isNotBlank() }.take(availableRows)
        for (index in existingCustomRows.indices) {
            val existing = existingCustomRows[index]
            if (index < editedTexts.size) {
                existing.setText(editedTexts[index])
            } else {
                layout.lines().remove(existing)
            }
        }
        for (index in existingCustomRows.size until editedTexts.size) {
            if (layout.lines().size >= 64) break
            layout.lines().add(ScoreboardLine.customText(editedTexts[index]))
        }
    }

    private fun rememberCurrentState(layout: ScoreboardLayout) {
        lastLayoutState = layoutState(layout)
        lastSettingsState = settingsState()
    }

    private fun layoutState(layout: ScoreboardLayout): EditableState = EditableState(
        layout.fontId().orEmpty(),
        layout.background()?.name ?: ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED.name,
        layout.backgroundColor(),
        layout.borderColor(),
        layout.lines().asSequence()
            .filter { it.type() == ScoreboardLine.Type.CUSTOM_TEXT }
            .map { it.text().orEmpty() }
            .toList(),
    )

    private fun settingsState(): EditableState = EditableState(
        font.value(),
        background.value(),
        backgroundColor.argb(),
        borderColor.argb(),
        customRows.map { it.value().take(256) }.filter { it.isNotBlank() },
    )

    private data class EditableState(
        val fontId: String,
        val background: String,
        val backgroundColor: Int,
        val borderColor: Int,
        val customRows: List<String>,
    )

    private fun customRowCapacity(layout: ScoreboardLayout): Int {
        val customCount = layout.lines().count { it.type() == ScoreboardLine.Type.CUSTOM_TEXT }
        return minOf(MAX_CUSTOM_ROWS, 64 - (layout.lines().size - customCount)).coerceAtLeast(customCount)
    }

    private class Settings {
        val font = ChoiceSetting("Font", "minecraft:default", "minecraft:default")
        val background = ChoiceSetting(
            "Background",
            ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED.name,
            mapOf(
                "NONE" to "None",
                "SOLID" to "Solid",
                "GLASS" to "Glass",
                "ROUNDED" to "Rounded",
                "GLASS_ROUNDED" to "Rounded Glass",
            ),
            *ScoreboardLayout.BackgroundStyle.values().map { it.name }.toTypedArray(),
        )
        val backgroundColor = ColorSetting("Background Color", 0, 0, 0, 170)
        val borderColor = ColorSetting("Border Color", 0, 0, 0, 102)
        val importFont = ModuleAction(
            "Import Font",
            "Import a local font for the scoreboard.",
            Runnable { openFontImporter() },
        )
        val customRows = Array(MAX_CUSTOM_ROWS) { index -> TextSetting("Custom Row ${index + 1}", "", 256) }

        fun allSettings(): Array<Setting> = arrayOf<Setting>(
            font,
            background,
            backgroundColor,
            borderColor,
            importFont,
            *customRows,
        )
    }

    private fun ColorSetting.setArgb(argb: Int) = set(
        (argb ushr 16) and 0xFF,
        (argb ushr 8) and 0xFF,
        argb and 0xFF,
        (argb ushr 24) and 0xFF,
    )

    companion object {
        private const val MAX_CUSTOM_ROWS = 64

        /** Java field retained for module registration and config callers. */
        @JvmField
        val INSTANCE = ScoreboardModule()

        @JvmStatic
        fun openFontImporter() {
            val minecraft = Minecraft.getInstance()
            minecraft.gui.setScreen(ScoreboardFontImportScreen(minecraft.gui.screen()))
        }
    }
}
