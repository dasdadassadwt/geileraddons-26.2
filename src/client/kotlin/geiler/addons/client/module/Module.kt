package geiler.addons.client.module

import geiler.addons.client.config.GeilerAddonsLog
import java.util.Collections

abstract class Module {
    private val moduleName: String
    private val moduleDescription: String
    private val moduleCategory: Category
    private val freshInstallEnabled: Boolean
    private val settings: List<Setting>
    private val colorSettings: List<ColorSetting>
    private val numberSettings: List<NumberSetting>
    private val booleanSettings: List<BooleanSetting>
    private val choiceSettings: List<ChoiceSetting>
    private val textSettings: List<TextSetting>
    private val actions: List<ModuleAction>
    private var groups: List<SettingGroup>? = null
    private var enabled = false
    private var keybind: ModuleKeybind = ModuleKeybind.NONE

    /** One flat list of settings in declaration order, sorted into typed lists here. */
    protected constructor(name: String, description: String, category: Category, vararg settings: Setting) :
        this(name, description, category, false, *settings)

    /** Saved user state is applied later by ModConfig. */
    protected constructor(
        name: String,
        description: String,
        category: Category,
        enabledByDefault: Boolean,
        vararg settings: Setting,
    ) {
        moduleName = name
        moduleDescription = description
        moduleCategory = category
        freshInstallEnabled = enabledByDefault
        this.settings = Collections.unmodifiableList(settings.toList())
        colorSettings = ofType(ColorSetting::class.java)
        // A range is saved through its two parts, so they are listed with the plain numbers.
        numberSettings = Collections.unmodifiableList(
            settings.flatMap { s ->
                when (s) {
                    is NumberSetting -> listOf(s)
                    is RangeSetting -> listOf(s.lowPart, s.highPart)
                    else -> emptyList()
                }
            },
        )
        booleanSettings = ofType(BooleanSetting::class.java)
        choiceSettings = ofType(ChoiceSetting::class.java)
        textSettings = ofType(TextSetting::class.java)
        actions = ofType(ModuleAction::class.java)
    }

    private fun <T : Setting> ofType(type: Class<T>): List<T> =
        Collections.unmodifiableList(settings.filter { type.isInstance(it) }.map(type::cast))

    open fun name(): String = moduleName

    /** Stable persisted key for a visible module whose display name changes. */
    open fun configName(): String = moduleName

    open fun description(): String = moduleDescription

    open fun category(): Category = moduleCategory

    open fun colorSettings(): List<ColorSetting> = colorSettings

    open fun numberSettings(): List<NumberSetting> = numberSettings

    open fun booleanSettings(): List<BooleanSetting> = booleanSettings

    open fun choiceSettings(): List<ChoiceSetting> = choiceSettings

    open fun textSettings(): List<TextSetting> = textSettings

    open fun actions(): List<ModuleAction> = actions

    /** Presentation only; the flat setting lists remain the persistence source of truth. */
    protected fun group(vararg groups: SettingGroup) {
        this.groups = Collections.unmodifiableList(groups.toList())
    }

    /** Modules without explicit groups keep the original single-section settings presentation. */
    open fun groups(): List<SettingGroup> {
        groups?.let { return it }
        return Collections.singletonList(SettingGroup(null, null, false, settings, emptyList()))
            .also { groups = it }
    }

    /**
     * More previews or custom rows, each placed in the settings group named by its [ModulePreview.previewGroupName].
     * The module itself is also a preview when it implements [ModulePreview]; this is for the rest.
     */
    open fun extraPreviews(): List<ModulePreview> = emptyList()

    open fun hasSettings(): Boolean = settings.isNotEmpty()

    open fun showsToggleControl(): Boolean = true

    open fun showsKeybindControl(): Boolean = true

    open fun isSettingVisible(setting: Setting): Boolean = true

    /** Module-specific hover help for one setting; null falls back to the generic text for its type. */
    open fun settingTooltip(setting: Setting): String? = null

    open fun isEnabled(): Boolean = enabled

    /** Fresh-install enabled state, used when a config has no saved value for this module. */
    open fun defaultEnabled(): Boolean = freshInstallEnabled

    open fun keybind(): ModuleKeybind = keybind

    /** Whether a press of the keybind flips the module (false for Zoom, which holds on it instead). */
    open fun togglesOnKeybind(): Boolean = true

    /** The bind a fresh install and "reset to defaults" use; only Zoom has one. */
    open fun defaultKeybind(): ModuleKeybind = ModuleKeybind.NONE

    open fun setKeybind(keybind: ModuleKeybind?) {
        this.keybind = keybind ?: ModuleKeybind.NONE
    }

    /** User intent plus the module's current context. */
    open fun isActive(): Boolean = enabled

    /** Short reason why an enabled module is idle, or null when no explanation is needed. */
    open fun inactiveReason(): String? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        GeilerAddonsLog.write(moduleCategory, moduleName, 0, if (enabled) "MODULE ENABLED" else "MODULE DISABLED")
        if (enabled) onEnable() else onDisable()
    }

    fun toggle() = setEnabled(!enabled)

    /** Called after isEnabled already reflects the new state. */
    protected open fun onEnable() = Unit

    /** Called after isEnabled already reflects the new state. */
    protected open fun onDisable() = Unit
}
