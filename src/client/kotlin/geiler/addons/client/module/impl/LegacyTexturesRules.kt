package geiler.addons.client.module.impl

import java.util.Locale

/** Pure rules for Legacy Textures, kept apart from the module so the offline checks can run them. */
object LegacyTexturesRules {
    /** What an item is, so each kind can be switched on its own. Heads come before armor: a skull is also equippable. */
    enum class Group { WEAPONS, ARMOR, HEADS, OTHER }

    /** Base items a pack model can stand on without being a real item; these get a [fallbackItem] look. */
    private val PLACEHOLDERS = setOf("minecraft:barrier", "minecraft:structure_void", "minecraft:light", "minecraft:debug_stick")

    /** First keyword found in the SkyBlock id wins, so more specific words come first. */
    private val FALLBACKS = listOf(
        "HELMET" to "minecraft:iron_helmet", "CHESTPLATE" to "minecraft:iron_chestplate", "TUNIC" to "minecraft:iron_chestplate",
        "LEGGINGS" to "minecraft:iron_leggings", "BOOTS" to "minecraft:iron_boots",
        "PICKAXE" to "minecraft:iron_pickaxe", "DRILL" to "minecraft:iron_pickaxe", "AXE" to "minecraft:iron_axe",
        "SHOVEL" to "minecraft:iron_shovel", "HOE" to "minecraft:iron_hoe", "SWORD" to "minecraft:iron_sword",
        "BLADE" to "minecraft:iron_sword", "BOW" to "minecraft:bow", "ROD" to "minecraft:fishing_rod",
        "WAND" to "minecraft:stick", "STAFF" to "minecraft:stick",
    )

    /** A pack item: a model outside minecraft and outside this mod, or any custom model data. */
    @JvmStatic
    fun isPackModel(modelNamespace: String?, hasCustomModelData: Boolean): Boolean =
        hasCustomModelData || (modelNamespace != null && modelNamespace != "minecraft" && modelNamespace != "geileraddons")

    /** Hypixel or one of its subdomains; the port is ignored and look-alike hosts do not match. */
    @JvmStatic
    fun isHypixelAddress(address: String?): Boolean {
        val host = address?.trim()?.lowercase(Locale.ROOT)?.substringBefore(':') ?: return false
        return host == "hypixel.net" || host.endsWith(".hypixel.net")
    }

    @JvmStatic
    fun groupOf(equippable: Boolean, playerHead: Boolean, weaponOrTool: Boolean): Group = when {
        playerHead -> Group.HEADS
        equippable -> Group.ARMOR
        weaponOrTool -> Group.WEAPONS
        else -> Group.OTHER
    }

    /** A vanilla look for a SkyBlock item whose base item is a placeholder, or null when the base item is real or the id empty. */
    @JvmStatic
    fun fallbackItem(skyblockId: String, baseItem: String): String? {
        if (skyblockId.isEmpty() || baseItem !in PLACEHOLDERS) return null
        val id = skyblockId.uppercase(Locale.ROOT)
        return FALLBACKS.firstOrNull { id.contains(it.first) }?.second ?: "minecraft:paper"
    }
}
