package geiler.addons.client.module

/** A named, collapsible section of a module's settings panel. */
@JvmRecord
data class SettingGroup(
    val name: String?,
    val toggle: BooleanSetting?,
    val collapsedByDefault: Boolean,
    val settings: List<Setting>,
    val children: List<SettingGroup>,
    val debugOnly: Boolean,
    val stateKey: String?,
) {
    constructor(name: String?, vararg settings: Setting) :
        this(name, null, false, listOf(*settings), emptyList(), false, null)

    /** Backwards-compatible constructor for ordinary groups. */
    constructor(
        name: String?,
        toggle: BooleanSetting?,
        collapsedByDefault: Boolean,
        settings: List<Setting>,
        children: List<SettingGroup>,
    ) : this(name, toggle, collapsedByDefault, settings, children, false, null)

    /** Backwards-compatible constructor for groups with debug-only visibility. */
    constructor(
        name: String?,
        toggle: BooleanSetting?,
        collapsedByDefault: Boolean,
        settings: List<Setting>,
        children: List<SettingGroup>,
        debugOnly: Boolean,
    ) : this(name, toggle, collapsedByDefault, settings, children, debugOnly, null)

    /** This section with sub-sections nested inside it. */
    fun containing(vararg nested: SettingGroup): SettingGroup =
        SettingGroup(name, toggle, collapsedByDefault, settings, listOf(*nested), debugOnly, stateKey)

    /** Stable identity for repeated or dynamic headings whose visible names can change. */
    fun keyed(key: String?): SettingGroup =
        SettingGroup(name, toggle, collapsedByDefault, settings, children, debugOnly, key)

    override fun toString(): String =
        "SettingGroup[name=$name, toggle=$toggle, collapsedByDefault=$collapsedByDefault, " +
            "settings=$settings, children=$children, debugOnly=$debugOnly, stateKey=$stateKey]"

    companion object {
        /** A switched section that remains open by default. */
        @JvmStatic
        fun switched(name: String?, toggle: BooleanSetting, vararg settings: Setting): SettingGroup =
            SettingGroup(name, toggle, false, listOf(*settings), emptyList(), false, null)

        /** A switched section that starts folded shut. */
        @JvmStatic
        fun switchedFolded(name: String?, toggle: BooleanSetting, vararg settings: Setting): SettingGroup =
            SettingGroup(name, toggle, true, listOf(*settings), emptyList(), false, null)

        /** A section that starts folded shut. */
        @JvmStatic
        fun folded(name: String?, vararg settings: Setting): SettingGroup =
            SettingGroup(name, null, true, listOf(*settings), emptyList(), false, null)

        /** A group that disappears while the global Dev Debug switch is off. */
        @JvmStatic
        fun debug(name: String?, vararg settings: Setting): SettingGroup =
            SettingGroup(name, null, false, listOf(*settings), emptyList(), true, null)
    }
}
