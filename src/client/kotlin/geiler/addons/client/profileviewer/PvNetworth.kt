package geiler.addons.client.profileviewer

import com.google.gson.JsonObject
import geiler.addons.client.pricing.NetworthSupport
import net.minecraft.nbt.CompoundTag
import java.util.function.ToDoubleFunction

/**
 * A profile member's networth by category, counted the way the common networth calculators count it: coins (purse,
 * profile bank, personal bank), every item container, pets, sacks, essence and, when loaded, museum donations.
 * Items and pets are priced through [NetworthSupport]; sacks and essence by their Bazaar price. Runs off the client thread.
 */
object PvNetworth {
    class Result(val categories: Map<String, Double>, val soulbound: Double, val museumIncluded: Boolean) {
        val total: Double get() = categories.values.sum()
        val tradeable: Double get() = total - soulbound
    }

    /**
     * [decode] turns one container blob into item tags; [museum] is this member's entry of the museum endpoint or null.
     * Legacy `wardrobe_contents` is skipped when the loadout sets are present (the same pieces).
     */
    @JvmStatic
    fun compute(pr: PvProfile, museum: JsonObject?, decode: (String) -> List<CompoundTag>?, price: ToDoubleFunction<String>): Result {
        val categories = LinkedHashMap<String, Double>()
        var soulbound = 0.0
        fun add(category: String, value: Double) {
            if (value > 0.0 && value.isFinite()) categories.merge(category, value, Double::plus)
        }
        add("Purse", pr.purse)
        add("Bank", pr.bank ?: 0.0)
        add("Personal bank", pr.personalBank ?: 0.0)
        fun items(category: String, tags: List<CompoundTag>) {
            for (tag in tags) {
                val extra = tag.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes")
                if (extra.isEmpty) continue
                val value = NetworthSupport.estimate(extra, tag.getByteOr("Count", 1).toInt().coerceAtLeast(1), price)
                add(category, value)
                if (PvItems.soulbound(tag)) soulbound += value
            }
        }
        for ((key, data) in pr.inventories) {
            if (key.startsWith("backpack_icon")) continue
            if (key == "wardrobe_contents" && (pr.armorSets.isNotEmpty() || pr.equipmentSets.isNotEmpty())) continue
            items(category(key), decode(data) ?: continue)
        }
        for (pet in pr.pets) add("Pets", NetworthSupport.pet(pet.type, pet.exp.toDouble(), pet.tier, pet.candyUsed, pet.heldItem, pet.skin, price))
        for ((id, amount) in pr.sacks) add("Sacks", NetworthSupport.sackItem(id, amount, price))
        for ((type, amount) in pr.sections.essence) add("Essence", NetworthSupport.essence(type, amount, price))
        if (museum != null) {
            // Borrowed items are back in the player's containers and already counted there.
            museum.getAsJsonObjectOrNull("items")?.entrySet()?.forEach { (_, entry) ->
                val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (item.get("borrowing")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return@forEach
                item.getAsJsonObjectOrNull("items")?.get("data")?.takeIf { it.isJsonPrimitive }?.asString?.let { decode(it) }?.let { items("Museum", it) }
            }
            museum.get("special")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { entry ->
                val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                item.getAsJsonObjectOrNull("items")?.get("data")?.takeIf { it.isJsonPrimitive }?.asString?.let { decode(it) }?.let { items("Museum", it) }
            }
        }
        return Result(categories, soulbound, museum != null)
    }

    /** Display category of a container key. */
    @JvmStatic
    fun category(key: String): String = when {
        key == "inv_contents" -> "Inventory"
        key == "inv_armor" || key.startsWith("wardrobe") -> "Armor and wardrobe"
        key == "equipment_contents" || key.startsWith("equipment_") -> "Equipment"
        key == "ender_chest_contents" -> "Ender Chest"
        key.startsWith("backpack_") -> "Backpacks"
        key == "talisman_bag" -> "Accessories"
        key == "personal_vault_contents" -> "Personal Vault"
        else -> "Other bags"
    }

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
