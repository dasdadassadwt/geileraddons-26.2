package geiler.addons.client.entity

import geiler.addons.client.location.Island

/**
 * The preset rare mobs. [title] is the name on the Hypixel name tag; names and the non-name-tag rules (Special Zealot,
 * Ender Dragon) follow SkyHanni's mob data. [common] mobs get boxes but never a title, sound or party message.
 */
enum class RareMob(
    val title: String,
    val island: Island,
    val color: Int,
    private val common: Boolean = false,
    private val nameTag: Boolean = true,
) {
    ZEALOT("Zealot", Island.THE_END, 0x00AAAA, common = true),
    SPECIAL_ZEALOT("Special Zealot", Island.THE_END, 0xAA0000, nameTag = false),
    ENDSTONE_PROTECTOR("Endstone Protector", Island.THE_END, 0xFF5555),
    ENDER_DRAGON("Ender Dragon", Island.THE_END, 0xAA00AA, nameTag = false),
    ARACHNE("Arachne", Island.SPIDERS_DEN, 0xFF5555),
    ARACHNE_BROOD("Arachne's Brood", Island.SPIDERS_DEN, 0xFFAA00, common = true),
    ASHFANG("Ashfang", Island.CRIMSON_ISLE, 0xFFAA00),
    BARBARIAN_DUKE("Barbarian Duke X", Island.CRIMSON_ISLE, 0xFFFF55),
    MAGE_OUTLAW("Mage Outlaw", Island.CRIMSON_ISLE, 0xAA00AA),
    MAGMA_BOSS("Magma Boss", Island.CRIMSON_ISLE, 0xAA0000),
    BLADESOUL("Bladesoul", Island.CRIMSON_ISLE, 0x555555),
    VANQUISHER("Vanquisher", Island.CRIMSON_ISLE, 0xFF55FF),
    MINOS_INQUISITOR("Minos Inquisitor", Island.HUB, 0xFFAA00),
    KING_MINOS("King Minos", Island.HUB, 0xFFFF55),
    MANTICORE("Manticore", Island.HUB, 0x55FF55),
    SPHINX("Sphinx", Island.HUB, 0x55FFFF),
    SCATHA("Scatha", Island.CRYSTAL_HOLLOWS, 0x55FF55),
    ;

    fun common(): Boolean = common

    companion object {
        private val COLOR_CODE = Regex("§.")
        private val LEVEL = Regex("""\[Lv\d+]""")
        private val HEALTH = Regex("""^ [\d.,]+[kKMB]?(?:/[\d.,]+[kKMB]?)?\s*❤""")
        private val PREFIXES = listOf("Runic ", "Corrupted ")

        /** The preset whose name tag this is on [island], or null. The name must be followed by a health value. */
        @JvmStatic
        fun fromNameTag(raw: String, island: Island): RareMob? {
            if ('❤' !in raw) return null
            var text = raw.replace(COLOR_CODE, "").replace(LEVEL, "").trimStart { !it.isLetter() }
            for (prefix in PREFIXES) text = text.removePrefix(prefix)
            return entries.firstOrNull {
                it.nameTag && it.island == island && text.startsWith(it.title) &&
                    HEALTH.containsMatchIn(text.substring(it.title.length))
            }
        }
    }
}
