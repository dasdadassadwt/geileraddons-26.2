package geiler.addons.client.module.impl

import java.util.regex.Pattern

/** Pure detection rules for No Render: particle fingerprints, dead-mob names and dungeon-only gates. */
object NoRenderRules {
    private const val HEART = "❤"
    private const val VAMPIRISM_MARK = "҉"

    private val DEAD_NAME_PATTERNS = listOf(
        Pattern.compile(".* 0$HEART"),
        Pattern.compile(".* 0/.*$HEART"),
    )

    /** A mob-death "poof": one particle, no speed, and a small non-zero offset on every axis. */
    @JvmStatic
    fun isPoofParticle(count: Int, maxSpeed: Float, xDist: Float, yDist: Float, zDist: Float): Boolean {
        if (count != 1 || maxSpeed != 0.0f) return false
        for (offset in floatArrayOf(xDist, yDist, zDist)) {
            if (offset >= 0.1f || offset <= -0.1f || offset == 0.0f) return false
        }
        return true
    }

    /** The dust colors Powder Coating uses: white, and orange (1, 0.6, 0). */
    @JvmStatic
    fun isCoatingColor(red: Float, green: Float, blue: Float): Boolean =
        (red == 1.0f && green == 1.0f && blue == 1.0f) || (red == 1.0f && green == 0.6f && blue == 0.0f)

    /** The packet shape of the coating dust: count 0, speed 1, and the color repeated as the offset. */
    @JvmStatic
    fun isCoatingPacketShape(count: Int, maxSpeed: Float, xDist: Float, yDist: Float, zDist: Float): Boolean =
        count == 0 && maxSpeed == 1.0f &&
            ((xDist == 1.0f && yDist == 1.0f && zDist == 1.0f) || (xDist == 1.0f && yDist == 0.6f && zDist == 0.0f))

    /** Whether a name-tag text is the health line of a dead mob (`... 0❤` or `... 0/...❤`). */
    @JvmStatic
    fun isDeadNameTag(plainName: String?): Boolean {
        if (plainName == null) return false
        val name = plainName.replace(VAMPIRISM_MARK, "")
        return DEAD_NAME_PATTERNS.any { it.matcher(name).matches() }
    }

    /** Dungeon-only entity rules: a Guided Sheep is the sheep at exactly 8 health. */
    @JvmStatic
    fun isGuidedSheepHealth(health: Float): Boolean = health == 8.0f

    /** Skin hash of the Soul Weaver's Gloves skull (SkyHanni's `DUNGEONS_SOUL_WEAVER`, NoFrills' Soulweaver Skulls). */
    private const val SOUL_WEAVER_SKIN = "2f24ed6875304fa4a1f0c785b2cb6a6a72563e9f3e24ea55e18178452119aa66"

    /** Whether a head's base64 `textures` property is the Soul Weaver skull skin. */
    @JvmStatic
    fun isSoulWeaverTexture(texture: String?): Boolean {
        if (texture.isNullOrEmpty()) return false
        val decoded = try {
            String(java.util.Base64.getDecoder().decode(texture), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            return false
        }
        return decoded.contains(SOUL_WEAVER_SKIN)
    }

    /** The bone-meal item stack of Bone Plating, by item id and exact display name. */
    @JvmStatic
    fun isBonePlatingDrop(isBoneMeal: Boolean, hoverName: String?): Boolean = isBoneMeal && hoverName == "Bone Meal"
}
