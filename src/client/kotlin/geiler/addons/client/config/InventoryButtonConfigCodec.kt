package geiler.addons.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.client.module.impl.InventoryButtonPlacement
import java.util.ArrayList
import java.util.HashSet
import java.util.function.IntPredicate
import java.util.function.Predicate

/** Local inventory-button layout codec, including legacy config fallback behavior. */
object InventoryButtonConfigCodec {
    @JvmStatic
    fun encode(placements: List<InventoryButtonPlacement?>?): JsonArray {
        val result = JsonArray()
        if (placements == null) return result
        for (placement in placements) {
            if (placement == null) continue
            val item = JsonObject()
            item.addProperty("id", placement.id())
            item.addProperty("macroId", placement.macroId())
            item.addProperty("gridX", placement.gridX())
            item.addProperty("gridY", placement.gridY())
            item.addProperty("slotAligned", placement.slotAligned())
            item.addProperty("appearance", placement.appearance().name)
            item.addProperty("value", placement.value())
            item.addProperty("hoverTooltip", placement.hoverTooltip())
            if (placement.hasTextAction()) item.addProperty("textAction", placement.textAction())
            result.add(item)
        }
        return result
    }

    /** Unknown/deleted macro IDs survive as unassigned buttons; unsafe PNG paths use a registered item. */
    @JvmStatic
    fun decode(
        data: JsonArray?,
        macroExists: IntPredicate?,
        pngNameAllowed: Predicate<String>?,
    ): List<InventoryButtonPlacement> {
        if (data == null || data.isEmpty) return emptyList()
        val ids = HashSet<Int>()
        val result = ArrayList<InventoryButtonPlacement>()
        for (element in data) {
            if (!element.isJsonObject) continue
            val saved = element.asJsonObject
            val id = integer(saved, "id", -1)
            if (id < 0 || !ids.add(id)) continue

            val placement = InventoryButtonPlacement(id)
            var macroId = integer(saved, "macroId", -1)
            if (macroId >= 0 && (macroExists == null || !macroExists.test(macroId))) macroId = -1
            placement.setMacroId(macroId)
            placement.setGrid(integer(saved, "gridX", 0), integer(saved, "gridY", 0))
            // Pre-lattice layouts used the inventory origin itself as cell zero; retain those pixel positions.
            placement.setSlotAligned(bool(saved, "slotAligned", false))

            var appearance = appearance(saved)
            var value = string(saved, "value", if (appearance == InventoryButtonPlacement.Appearance.ITEM) {
                "minecraft:stone"
            } else {
                ""
            })
            if (appearance == InventoryButtonPlacement.Appearance.PNG &&
                (pngNameAllowed == null || !pngNameAllowed.test(value))
            ) {
                appearance = InventoryButtonPlacement.Appearance.ITEM
                value = "minecraft:stone"
            }
            placement.setAppearance(appearance, value)
            placement.setHoverTooltip(string(saved, "hoverTooltip", ""))
            placement.setTextAction(string(saved, "textAction", ""))
            result.add(placement)
        }
        return java.util.List.copyOf(result)
    }

    private fun appearance(saved: JsonObject): InventoryButtonPlacement.Appearance = try {
        InventoryButtonPlacement.Appearance.valueOf(string(saved, "appearance", "ITEM"))
    } catch (_: IllegalArgumentException) {
        InventoryButtonPlacement.Appearance.ITEM
    }

    private fun integer(objectValue: JsonObject, name: String, fallback: Int): Int = try {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) fallback else value.asInt
    } catch (_: RuntimeException) {
        fallback
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String): String = try {
        val value: JsonElement? = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) fallback else value.asString
    } catch (_: RuntimeException) {
        fallback
    }

    private fun bool(objectValue: JsonObject, name: String, fallback: Boolean): Boolean = try {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) fallback else value.asBoolean
    } catch (_: RuntimeException) {
        fallback
    }
}
