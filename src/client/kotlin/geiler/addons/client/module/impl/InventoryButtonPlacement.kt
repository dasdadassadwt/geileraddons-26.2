package geiler.addons.client.module.impl

/** One locally stored 18×18 button anchored to an inventory-screen grid cell. */
class InventoryButtonPlacement {
    enum class Appearance { ITEM, TEXT, PNG }

    private var idValue = 0
    private var macroIdValue = -1
    private var gridXValue = 0
    private var gridYValue = 0
    private var slotAlignedValue = true
    private var appearanceValue = Appearance.ITEM
    private var valueValue = "minecraft:stone"
    private var hoverTooltipValue = ""
    private var textActionValue: String? = null

    constructor(id: Int) {
        idValue = maxOf(0, id)
    }

    constructor(source: InventoryButtonPlacement?) {
        val original = source!!
        idValue = original.id()
        macroIdValue = original.macroId()
        gridXValue = original.gridX()
        gridYValue = original.gridY()
        slotAlignedValue = original.slotAligned()
        appearanceValue = original.appearance()
        valueValue = original.value()
        hoverTooltipValue = original.hoverTooltip()
        textActionValue = original.textAction()
    }

    fun id(): Int = idValue
    fun macroId(): Int = macroIdValue
    fun gridX(): Int = gridXValue
    fun gridY(): Int = gridYValue
    fun slotAligned(): Boolean = slotAlignedValue
    fun appearance(): Appearance = appearanceValue
    fun value(): String = valueValue
    fun hoverTooltip(): String = hoverTooltipValue
    fun textAction(): String? = textActionValue
    fun hasTextAction(): Boolean = textActionValue != null && !javaBlank(textActionValue!!)

    fun setMacroId(value: Int) {
        macroIdValue = maxOf(-1, value)
    }

    fun setGrid(x: Int, y: Int) {
        gridXValue = x.coerceIn(-128, 128)
        gridYValue = y.coerceIn(-128, 128)
        slotAlignedValue = true
    }

    fun setSlotAligned(value: Boolean) {
        slotAlignedValue = value
    }

    fun setAppearance(value: Appearance?, content: String?) {
        appearanceValue = value ?: Appearance.ITEM
        val trimmed = javaTrim(content ?: "")
        valueValue = trimmed.substring(0, minOf(256, trimmed.length))
    }

    fun setHoverTooltip(value: String?) {
        val trimmed = javaTrim(value ?: "")
        hoverTooltipValue = trimmed.substring(0, minOf(256, trimmed.length))
    }

    fun setTextAction(value: String?) {
        val trimmed = javaTrim(value ?: "")
        textActionValue = if (trimmed.isEmpty()) null else trimmed.substring(0, minOf(256, trimmed.length))
    }

    private fun javaBlank(value: String): Boolean = value.all { java.lang.Character.isWhitespace(it) }

    private fun javaTrim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start] <= ' ') start++
        while (start < end && value[end - 1] <= ' ') end--
        return value.substring(start, end)
    }
}
