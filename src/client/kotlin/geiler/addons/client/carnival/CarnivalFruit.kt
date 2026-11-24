package geiler.addons.client.carnival

import java.util.Base64
import java.util.Locale

/**
 * Fruit Digging contents, after SkyHanni's `CarnivalFruitDigging.Fruit` (LGPL-2.1). Counts and points are the board's
 * fixed composition; the texture ids are the skin hashes of SkyHanni-REPO `constants/Skulls.json` `CARNIVAL_*` heads.
 */
enum class CarnivalFruit(
    val inGameName: String,
    val points: Int,
    val count: Int,
    val textureHash: String = "",
    val isEdible: Boolean = true,
) {
    UNKNOWN("Unknown", 0, 0, "", false),
    NO_FRUIT("No Fruit", 0, 0, "", false),
    BOMB("Bomb", 0, 10, "a76a2811d1e176a07b6d0a657b910f134896ce30850f6e80c7c83732d85381ea", false),
    RUM("Rum", 0, 5, "407b275d28b927b1bf7f6dd9f45fbdad2af8571c54c8f027d1bff6956fbf3c16", false),
    MANGO("Mango", 300, 10, "f363a62126a35537f8189343a22660de75e810c6ac004a7d3da65f1c040a839"),
    APPLE("Apple", 100, 8, "17ea278d6225c447c5943d652798d0bbbd1418434ce8c54c54fdac79994ddd6c"),
    WATERMELON("Watermelon", 100, 4, "efe4ef83baf105e8dee6cf03dfe7407f1911b3b9952c891ae34139560f2931d6"),
    POMEGRANATE("Pomegranate", 200, 4, "40824d18079042d5769f264f44394b95b9b99ce689688cc10c9eec3f882ccc08"),
    COCONUT("Coconut", 200, 3, "10ceb1455b471d016a9f06d25f6e468df9fcf223e2c1e4795b16e84fcca264ee"),
    CHERRY("Cherry", 200, 2, "c92b099a62cd2fbf8ada09dec145c75d7fda4dc57b968bea3a8fa11e37aa48b2"),
    DURIAN("Durian", 800, 2, "ac268d36c2c6047ffeec00124096376b56dbb4d756a55329363a1b27fcd659cd"),
    DRAGON_FRUIT("Dragonfruit", 1200, 1, "3cc761bcb0579763d9b8ab6b7b96fa77eb6d9605a804d838fec39e7b25f95591"),
    ;

    /** Points this fruit is worth now: apples and cherries grow with each one dug, pomegranate/durian scale the next dig. */
    fun pointValue(alreadyDug: Int, lastDug: CarnivalFruit): Int {
        val multiplier = when (lastDug) {
            POMEGRANATE -> 1.5
            DURIAN -> 0.5
            else -> 1.0
        }
        return when (this) {
            APPLE -> (multiplier * (points + 100 * alreadyDug)).toInt()
            CHERRY -> (multiplier * (points + 300 * alreadyDug)).toInt()
            else -> (multiplier * points).toInt()
        }
    }

    companion object {
        private val HASH = Regex("texture/([0-9a-f]+)")

        @JvmStatic
        fun fromName(name: String): CarnivalFruit? = entries.find { it.inGameName.equals(name.trim(), ignoreCase = true) }

        /** The fruit for a head's base64 `textures` property; null for any other head. */
        @JvmStatic
        fun fromTexture(base64: String?): CarnivalFruit? {
            val hash = textureHash(base64) ?: return null
            return entries.find { it.textureHash.isNotEmpty() && it.textureHash == hash }
        }

        /** The skin hash inside a base64 `textures` property (the timestamp and profile parts differ between servers). */
        @JvmStatic
        fun textureHash(base64: String?): String? {
            if (base64.isNullOrBlank()) return null
            val json = try {
                String(Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return HASH.find(json.lowercase(Locale.ROOT))?.groupValues?.get(1)
        }
    }
}
