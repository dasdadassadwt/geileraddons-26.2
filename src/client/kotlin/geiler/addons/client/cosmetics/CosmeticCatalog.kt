package geiler.addons.client.cosmetics

/**
 * Where a cosmetic sits on the player; a player wears at most one style per slot. The defaults are the
 * owner's tuned placement (size, height and depth in pixels, animation speed), used by the menu and by
 * hosted pieces that leave a field out.
 */
enum class CosmeticSlot(
    val id: String,
    val label: String,
    val defaultSize: Float,
    val defaultUp: Float,
    val defaultBack: Float,
    val defaultSpeed: Float,
) {
    WINGS("wings", "Wings", 0.5f, -0.15f, -2.2f, 0.45f),
    HEAD("head", "Head", 1f, -1.9f, 0f, 1f),
    HALO("halo", "Halo", 1.15f, 0.25f, 0f, 0.7f),
    TAIL("tail", "Tail", 0.95f, 0f, 0f, 0.1f),
    AURA("aura", "Aura", 1.25f, 3.85f, -1.9f, 1f),
    HAT("hat", "Hat", 1f, 0f, 0f, 1f),
    FACE("face", "Face", 1f, 0f, 0f, 1f),
    BACK("back", "Back", 1f, 0f, 0f, 1f),
    PET("pet", "Pet", 1f, 0f, 0f, 1f),
    SKIRT("skirt", "Skirt", 1f, 0f, 0f, 1f),
    /** Effects left behind in the world where the player walks (size scales the sprites, Depth shifts them back). */
    TRAIL("trail", "Trail", 1f, 0f, 0f, 1f),
    /** Replaces the vanilla cape; follows its physics. Size scales it, Height and Depth move the hinge. */
    CAPE("cape", "Cape", 1f, 0f, 0f, 1f),
    ;

    fun styles(): List<CosmeticStyle> = CosmeticStyle.entries.filter { it.slot == this }

    companion object {
        @JvmStatic
        fun byId(id: String?): CosmeticSlot? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every cosmetic model. Colors are tints: the voxel art is shaded grey, so any color works.
 * [color] tints the main parts, [color2] the second parts (devil wing membrane, inner ear, tail tip...).
 */
enum class CosmeticStyle(
    val slot: CosmeticSlot,
    val id: String,
    val label: String,
    val color: Int,
    val color2: Int,
) {
    ANGEL_WINGS(CosmeticSlot.WINGS, "angel", "Angel Wings", 0xFFFFFF, 0xC9D4EA),
    DEVIL_WINGS(CosmeticSlot.WINGS, "devil", "Devil Wings", 0x2B2326, 0xB0202A),
    DRAGON_WINGS(CosmeticSlot.WINGS, "dragon", "Dragon Wings", 0xA3171E, 0x2E2428),
    FAIRY_WINGS(CosmeticSlot.WINGS, "fairy", "Fairy Wings", 0xE8789C, 0xF4EFFA),
    PHOENIX_WINGS(CosmeticSlot.WINGS, "phoenix", "Phoenix Wings", 0xE5481B, 0xFFC83A),
    MECHANICAL_WINGS(CosmeticSlot.WINGS, "mechanical", "Mechanical Wings", 0xC4202A, 0x25232A),
    CRYSTAL_WINGS(CosmeticSlot.WINGS, "crystal", "Crystal Wings", 0xD42A36, 0x1E1A1F),
    DEMON_HORNS(CosmeticSlot.HEAD, "demon_horns", "Demon Horns", 0xB0141E, 0x5C0A12),
    CUTE_HORNS(CosmeticSlot.HEAD, "cute_horns", "Cute Horns", 0xEDE7DF, 0xA79D94),
    RAM_HORNS(CosmeticSlot.HEAD, "ram_horns", "Ram Horns", 0xD9C7A3, 0x8C7A5C),
    CAT_EARS(CosmeticSlot.HEAD, "cat_ears", "Cat Ears", 0x1E1C21, 0xF29BB2),
    UNICORN_HORN(CosmeticSlot.HEAD, "unicorn_horn", "Unicorn Horn", 0xC41E2A, 0xE8B84A),
    HALO(CosmeticSlot.HALO, "halo", "Halo", 0xFFD447, 0xC98A12),
    STRAIGHT_TAIL(CosmeticSlot.TAIL, "straight", "Straight Tail", 0x2C2B31, 0xF3F3F3),
    CURLED_TAIL(CosmeticSlot.TAIL, "curled", "Curled Tail", 0x2C2B31, 0xF3F3F3),
    FLUFFY_TAIL(CosmeticSlot.TAIL, "fluffy", "Fluffy Tail", 0x2C2B31, 0xF3F3F3),
    DRAGON_TAIL(CosmeticSlot.TAIL, "dragon", "Dragon Tail", 0xA3171E, 0x241C20),
    FOX_TAIL(CosmeticSlot.TAIL, "fox", "Fox Tail", 0xE0702A, 0xF6EFE6),
    DEVIL_TAIL(CosmeticSlot.TAIL, "devil", "Devil Tail", 0xC01A22, 0x1B1A1E),
    GLOW_AURA(CosmeticSlot.AURA, "glow", "Glow Aura", 0x7FD8FF, 0xC9A6FF),
    STINK_AURA(CosmeticSlot.AURA, "stink", "Stink Aura", 0x7BC043, 0x1A1A1A),
    BUNNY_EARS(CosmeticSlot.HEAD, "bunny_ears", "Bunny Ears", 0xF2EFEA, 0xF2A7B8),
    FOX_EARS(CosmeticSlot.HEAD, "fox_ears", "Fox Ears", 0xD9622B, 0x1E1A1C),
    DEER_ANTLERS(CosmeticSlot.HEAD, "deer_antlers", "Deer Antlers", 0x8B5A33, 0x2A1B12),
    TOP_HAT(CosmeticSlot.HAT, "top_hat", "Top Hat", 0xA3161E, 0x25252B),
    ROYAL_CROWN(CosmeticSlot.HAT, "royal_crown", "Royal Crown", 0xF0BF2D, 0x9C1B24),
    WITCH_HAT(CosmeticSlot.HAT, "witch_hat", "Witch Hat", 0x8B1C2A, 0x24202B),
    PROPELLER_BEANIE(CosmeticSlot.HAT, "propeller_beanie", "Propeller Beanie", 0xC41E24, 0x1F1B22),
    FLOWER_CROWN(CosmeticSlot.HAT, "flower_crown", "Flower Crown", 0xE04A5A, 0x3E8B3A),
    HEADPHONES(CosmeticSlot.HAT, "headphones", "Over-Ear Headphones", 0xC8222B, 0x1D1C21),
    SUNGLASSES(CosmeticSlot.FACE, "sunglasses", "Pixel Sunglasses", 0xD1242D, 0x14141A),
    FOX_MASK(CosmeticSlot.FACE, "fox_mask", "Fox Mask", 0xC8202A, 0xF4F1EC),
    PLAGUE_MASK(CosmeticSlot.FACE, "plague_mask", "Plague Doctor Mask", 0xCC2A2A, 0x2A2A30),
    BANDANA_MASK(CosmeticSlot.FACE, "bandana", "Bandana", 0xC8202A, 0x1B1B1F),
    BACKPACK(CosmeticSlot.BACK, "backpack", "Adventure Backpack", 0x9A6238, 0x1B1B1F),
    KATANA(CosmeticSlot.BACK, "katana", "Katana", 0xB3202A, 0x17171B),
    JETPACK(CosmeticSlot.BACK, "jetpack", "Jetpack", 0x8A9099, 0x1F2126),
    TURTLE_SHELL(CosmeticSlot.BACK, "turtle_shell", "Turtle Shell", 0x2F8A3E, 0x14301A),
    ELECTRIC_GUITAR(CosmeticSlot.BACK, "guitar", "Electric Guitar", 0xB3202A, 0x17171B),
    STORM_CLOUD(CosmeticSlot.AURA, "storm_cloud", "Storm Cloud", 0x8E96A3, 0x4A5262),
    FLAME_RING(CosmeticSlot.AURA, "flame_ring", "Flame Ring", 0xFF7A1A, 0xFFD84A),
    HEART_AURA(CosmeticSlot.AURA, "heart_aura", "Heart Aura", 0xFF3B5C, 0xFF9DBB),
    BABY_DRAGON(CosmeticSlot.PET, "baby_dragon", "Baby Dragon", 0xC0392B, 0x2A2024),
    GHOST(CosmeticSlot.PET, "ghost", "Ghost", 0xF2F2F8, 0xF59AB0),
    BEE(CosmeticSlot.PET, "bee", "Bee", 0xF6C324, 0x24201F),
    SLIME_CUBE(CosmeticSlot.PET, "slime_cube", "Slime Cube", 0x6BCF5B, 0x2F8F3A),
    AXOLOTL(CosmeticSlot.PET, "axolotl", "Axolotl", 0xF4A6C8, 0xB5305F),
    OWL(CosmeticSlot.PET, "owl", "Owl", 0x8B5E3C, 0x3B2A1E),
    MUSIC_AURA(CosmeticSlot.AURA, "music_notes", "Music Note Aura", 0xD42A33, 0x1C1B20),
    FROST_AURA(CosmeticSlot.AURA, "frost", "Frost Aura", 0x9FD8F5, 0xFFFFFF),
    BLOSSOM_AURA(CosmeticSlot.AURA, "cherry_blossom", "Cherry Blossom Aura", 0xF7A8C4, 0xDF6F98),
    PLEATED_SKIRT(CosmeticSlot.SKIRT, "pleated", "Pleated Skirt", 0xC21E24, 0x1C1A1F),
    RUFFLED_SKIRT(CosmeticSlot.SKIRT, "ruffled", "Ruffled Tiered Skirt", 0xC21E24, 0x1C1A1F),
    LONG_SKIRT(CosmeticSlot.SKIRT, "long", "Long Flowing Skirt", 0xB3202B, 0x1C1A1F),
    TUTU(CosmeticSlot.SKIRT, "tutu", "Tutu", 0xF28CB8, 0x3A2A4A),
    KILT(CosmeticSlot.SKIRT, "kilt", "Kilt", 0xC21E24, 0x1C1A1F),
    MAID_SKIRT(CosmeticSlot.SKIRT, "maid", "Maid Skirt", 0xC21E24, 0x1C1A1F),
    FLAME_FOOTPRINTS(CosmeticSlot.TRAIL, "flame_footprints", "Flame Footprints", 0xF2480F, 0xFFC62A),
    FLOWER_STEPS(CosmeticSlot.TRAIL, "flower_steps", "Flower Steps", 0xD8242A, 0x2F7A33),
    SPARKLE_TRAIL(CosmeticSlot.TRAIL, "sparkle_trail", "Sparkle Trail", 0xE8202A, 0xFFFFFF),
    RAINBOW_RIBBON(CosmeticSlot.TRAIL, "rainbow_ribbon", "Rainbow Ribbon", 0xE8262B, 0x9B30E8),
    BUBBLE_TRAIL(CosmeticSlot.TRAIL, "bubble_trail", "Bubble Trail", 0x9FC8F0, 0xEAF5FF),
    ENDER_SPARKS(CosmeticSlot.TRAIL, "ender_sparks", "Ender Sparks", 0xA838E8, 0x1A1220),
    DRAGON_SCALE_CAPE(CosmeticSlot.CAPE, "dragon_scale", "Dragon Scale Cape", 0xC8121C, 0x0D0A0A),
    STARFIELD_CAPE(CosmeticSlot.CAPE, "starfield", "Starfield Cape", 0xE0242E, 0x0C0C12),
    ROYAL_CAPE(CosmeticSlot.CAPE, "royal", "Royal Cape", 0xB5121C, 0x1A1414),
    ;

    companion object {
        @JvmStatic
        fun byId(slot: CosmeticSlot, id: String?): CosmeticStyle? =
            entries.firstOrNull { it.slot == slot && it.id == id }

        @JvmStatic
        fun byLabel(label: String?): CosmeticStyle? = entries.firstOrNull { it.label == label }
    }
}

/** One worn cosmetic with its tints (RGB), size multiplier, pixel offsets and animation speed. */
@JvmRecord
data class CosmeticPiece(
    val style: CosmeticStyle,
    val color: Int,
    val color2: Int,
    val size: Float,
    val up: Float,
    val back: Float,
    val speed: Float,
) {
    companion object {
        const val MIN_SIZE = 0.25f
        const val MAX_SIZE = 2.5f
        const val MAX_OFFSET = 12f
        const val MAX_SPEED = 4f

        @JvmStatic
        fun of(style: CosmeticStyle): CosmeticPiece = style.slot.let {
            CosmeticPiece(style, style.color, style.color2, it.defaultSize, it.defaultUp, it.defaultBack, it.defaultSpeed)
        }
    }
}

/**
 * Body shape on the skin itself: [chest] adds a bust (0 = none, 1 = largest), [waist] narrows the waist
 * (0 = straight, 1 = narrowest). Both are 0..1.
 */
@JvmRecord
data class BodyShape(val chest: Float, val waist: Float) {
    fun isFlat(): Boolean = chest <= 0.001f && waist <= 0.001f
}

/** What one player wears: one piece per slot, plus an optional body shape. */
class CosmeticLoadout private constructor(
    private val pieces: Map<CosmeticSlot, CosmeticPiece>,
    private val bodyShape: BodyShape?,
) {
    fun piece(slot: CosmeticSlot): CosmeticPiece? = pieces[slot]

    fun pieces(): Collection<CosmeticPiece> = pieces.values

    /** The body shape, or null when the body is unchanged. */
    fun body(): BodyShape? = bodyShape

    fun isEmpty(): Boolean = pieces.isEmpty() && bodyShape == null

    override fun equals(other: Any?): Boolean =
        other is CosmeticLoadout && other.pieces == pieces && other.bodyShape == bodyShape

    override fun hashCode(): Int = pieces.hashCode() * 31 + (bodyShape?.hashCode() ?: 0)

    override fun toString(): String =
        pieces.values.joinToString(",", "[", "]") { it.style.id } + (bodyShape?.let { " body=${it.chest}/${it.waist}" } ?: "")

    companion object {
        @JvmField
        val EMPTY = CosmeticLoadout(emptyMap(), null)

        @JvmStatic
        @JvmOverloads
        fun of(pieces: Collection<CosmeticPiece>, body: BodyShape? = null): CosmeticLoadout {
            val shape = body?.takeUnless { it.isFlat() }
            if (pieces.isEmpty() && shape == null) return EMPTY
            val map = java.util.EnumMap<CosmeticSlot, CosmeticPiece>(CosmeticSlot::class.java)
            for (piece in pieces) map[piece.style.slot] = piece
            return CosmeticLoadout(map, shape)
        }
    }
}
