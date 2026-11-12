package geiler.addons.client.macro

import geiler.addons.client.location.Island
import geiler.addons.client.module.ModuleKeybind
import java.util.ArrayList
import java.util.EnumSet

/** User-owned macro metadata and workflow. */
class MacroDefinition(id: Int) {
    private val idValue = maxOf(0, id)
    private var nameValue = "Macro $idValue"
    private var folderIdValue: String? = null
    private var enabledValue = true
    /** New macros are useful in both the world and ordinary container screens by default. */
    private var triggerContextValue = MacroTriggerContext.ANY_NON_TEXT_SCREEN
    private var islandRestrictedValue = false
    private val islandsValue: EnumSet<Island> = EnumSet.noneOf(Island::class.java)
    private val scriptsValue = ArrayList<MacroScript>()
    /** Unconnected blocks live on the editor canvas and are deliberately never visited by MacroRunner. */
    private val detachedBlocksValue = ArrayList<MacroStep>()
    private var canvasPanXValue = 0.0f
    private var canvasPanYValue = 0.0f
    /** Canvas zoom as a user-facing percentage, from 1% through 100%. */
    private var canvasZoomValue = 100.0f

    init {
        scriptsValue.add(MacroScript(MacroScript.Trigger.KEY_PRESS))
    }

    fun id(): Int = idValue
    fun name(): String = nameValue
    fun setName(value: String?) {
        nameValue = if (MacroJavaStrings.isBlank(value)) "Macro $idValue" else value!!.substring(0, minOf(48, value.length))
    }

    fun folderId(): String? = folderIdValue
    fun setFolderId(value: String?) {
        folderIdValue = if (MacroJavaStrings.isBlank(value)) null else value
    }

    fun enabled(): Boolean = enabledValue
    fun setEnabled(value: Boolean) {
        enabledValue = value
    }

    fun keybind(): ModuleKeybind = primaryKeyScript().keybind()
    fun setKeybind(value: ModuleKeybind?) {
        primaryKeyScript().setKeybind(value)
    }

    fun triggerContext(): MacroTriggerContext = triggerContextValue
    fun setTriggerContext(value: MacroTriggerContext?) {
        triggerContextValue = value ?: MacroTriggerContext.WORLD_ONLY
    }

    fun islandRestricted(): Boolean = islandRestrictedValue
    fun setIslandRestricted(value: Boolean) {
        islandRestrictedValue = value
    }

    fun islands(): EnumSet<Island> = EnumSet.copyOf(islandsValue)
    fun setIslands(values: Iterable<Island?>?) {
        islandsValue.clear()
        if (values != null) for (island in values) if (island != null && island.selectable()) islandsValue.add(island)
    }

    /** Backward-compatible view of the original macro workflow. */
    fun steps(): MutableList<MacroStep> = primaryKeyScript().steps()
    fun scripts(): MutableList<MacroScript> = scriptsValue
    fun detachedBlocks(): MutableList<MacroStep> = detachedBlocksValue

    fun restoreDetachedBlocks(restored: Iterable<MacroStep?>?) {
        detachedBlocksValue.clear()
        if (restored == null) return
        for (step in restored) {
            if (detachedBlocksValue.size >= 512) break
            if (step != null) detachedBlocksValue.add(step)
        }
    }

    fun primaryKeyScript(): MacroScript {
        for (script in scriptsValue) if (script.trigger() == MacroScript.Trigger.KEY_PRESS) return script
        val script = MacroScript(MacroScript.Trigger.KEY_PRESS)
        scriptsValue.add(0, script)
        return script
    }

    fun addScript(trigger: MacroScript.Trigger?): MacroScript {
        val script = MacroScript(trigger)
        val index = scriptsValue.size
        script.setCanvasPosition((index % 3) * 310.0f, (index / 3) * 90.0f)
        scriptsValue.add(script)
        return script
    }

    fun restoreScripts(restored: Iterable<MacroScript?>?) {
        scriptsValue.clear()
        if (restored != null) for (script in restored) if (script != null) scriptsValue.add(script)
        if (scriptsValue.isEmpty()) scriptsValue.add(MacroScript(MacroScript.Trigger.KEY_PRESS))
        if (scriptsValue.size > 1 && scriptsValue.all { it.canvasX() == 0.0f && it.canvasY() == 0.0f }) {
            for (index in scriptsValue.indices) {
                scriptsValue[index].setCanvasPosition((index % 3) * 310.0f, (index / 3) * 90.0f)
            }
        }
        primaryKeyScript()
    }

    fun canvasPanX(): Float = canvasPanXValue
    fun canvasPanY(): Float = canvasPanYValue
    fun canvasZoom(): Float = canvasZoomValue

    fun setCanvasView(panX: Float, panY: Float, zoom: Float) {
        canvasPanXValue = if (panX.isFinite()) panX.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
        canvasPanYValue = if (panY.isFinite()) panY.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
        setCanvasZoom(zoom)
    }

    fun setCanvasZoom(zoom: Float) {
        canvasZoomValue = if (zoom.isFinite()) zoom.coerceIn(1.0f, 100.0f) else 100.0f
    }

    fun allowsIsland(island: Island?): Boolean = !islandRestrictedValue || (island != null && islandsValue.contains(island))
}
