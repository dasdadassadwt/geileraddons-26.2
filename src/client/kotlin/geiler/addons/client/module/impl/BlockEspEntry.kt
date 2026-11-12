package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import java.util.Collections
import java.util.EnumMap

/** One exact registered block type with its own ESP style and island allowlist. */
class BlockEspEntry internal constructor(private val entryId: Int) {
    private var selectedBlockId = ""
    private var selectedFolderId: String? = null

    private val enabledSetting = BooleanSetting("Enabled", true)
    private val boxSetting = BooleanSetting("Box", true)
    private val fillSetting = BooleanSetting("Fill", true)
    private val outlineSetting = BooleanSetting("Outline", true)
    private val showLabelSetting = BooleanSetting("Show Label", true)
    private val tracerSetting = BooleanSetting("Tracer", false)
    private val connectTouchingSetting = BooleanSetting("Connect Touching Blocks", true)

    /** Seeing the block through terrain is the cheat; the safe state is depth-checked. */
    private val depthCheckSetting = BooleanSetting("Depth Check", true)
    private val useCustomRangeSetting = BooleanSetting("Use Custom Range", false)
    private val customRangeSetting = NumberSetting("Custom Range (blocks)", 1f, 512f, 32f, true)
    private val outlineColorSetting = ColorSetting("Outline Color", 255, 190, 0, 255)
    private val fillColorSetting = ColorSetting("Fill Color", 255, 190, 0, 48)
    private val displayNameSetting = TextSetting("Display Name", "New Block", 48)
    private val islandSettings = EnumMap<Island, BooleanSetting>(Island::class.java)
    private var currentMatchCount = 0
    private val deleteAction = ModuleAction(
        "Delete Block ESP Entry",
        Runnable { BlockEspModule.INSTANCE.remove(this) },
    )

    init {
        Island.values().filter(Island::selectable).forEach { island ->
            islandSettings[island] = BooleanSetting(island.label(), false)
        }
    }

    fun id(): Int = entryId
    fun blockId(): String = selectedBlockId

    fun setBlockId(value: String?) {
        // Keep Java String.trim() semantics from the saved-data path: strip only chars <= U+0020.
        selectedBlockId = value?.trim { it <= ' ' }?.take(128).orEmpty()
    }

    fun folderId(): String? = selectedFolderId
    fun setFolderId(value: String?) {
        selectedFolderId = value?.takeUnless(BlockEspStringSemantics::isJavaBlank)
    }

    fun enabled(): BooleanSetting = enabledSetting
    fun box(): BooleanSetting = boxSetting
    fun fill(): BooleanSetting = fillSetting
    fun outline(): BooleanSetting = outlineSetting
    fun showLabel(): BooleanSetting = showLabelSetting
    fun tracer(): BooleanSetting = tracerSetting
    fun connectTouching(): BooleanSetting = connectTouchingSetting
    fun depthCheck(): BooleanSetting = depthCheckSetting
    fun useCustomRange(): BooleanSetting = useCustomRangeSetting
    fun customRange(): NumberSetting = customRangeSetting
    fun outlineColor(): ColorSetting = outlineColorSetting
    fun fillColor(): ColorSetting = fillColorSetting
    fun displayName(): TextSetting = displayNameSetting
    fun islands(): Map<Island, BooleanSetting> = Collections.unmodifiableMap(islandSettings)
    fun matchCount(): Int = currentMatchCount

    internal fun setStatus(count: Int) {
        currentMatchCount = count.coerceAtLeast(0)
    }

    internal fun appliesOn(current: Island?): Boolean {
        if (!HypixelModApi.hasLocation() || current == null || current == Island.NONE || current == Island.OTHER) {
            return false
        }
        return islandSettings[current]?.value() == true
    }

    internal fun group(): SettingGroup {
        val displayName = displayNameSetting.value()
        val title = if (BlockEspStringSemantics.isJavaBlank(displayName)) "Block ESP #$entryId"
        else "#$entryId $displayName"
        val blockLabel = if (BlockEspStringSemantics.isJavaBlank(selectedBlockId)) {
            "Block: Not selected"
        } else {
            "Block: $selectedBlockId"
        }
        val islandGroup = SettingGroup.folded("Islands", *islandSettings.values.toTypedArray<Setting>())
            .keyed("block-esp-islands:$entryId")

        return SettingGroup.switched(
            title,
            enabledSetting,
            ModuleAction(
                blockLabel,
                "Select one exact block type from the registry.",
                Runnable { BlockEspModule.INSTANCE.openPicker(this) },
            ),
            useCustomRangeSetting,
            customRangeSetting,
            boxSetting,
            fillSetting,
            outlineSetting,
            displayNameSetting,
            showLabelSetting,
            tracerSetting,
            connectTouchingSetting,
            depthCheckSetting,
            outlineColorSetting,
            fillColorSetting,
            ModuleAction(
                "Matches: $currentMatchCount",
                "Every matching position in range from the last completed scan is included.",
                Runnable {},
            ),
            deleteAction,
        ).containing(islandGroup).keyed("block-esp-entry:$entryId")
    }
}
