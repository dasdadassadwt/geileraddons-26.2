package geiler.addons.client.dungeon

import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.Locale

/** Pure rules for Bigger Secret Hitboxes; the module decides when they apply. */
object SecretHitboxRules {
    enum class Kind { LEVER, BUTTON, CHEST, ESSENCE, REDSTONE_KEY }

    /** Owner ids Hypixel gives the secret skulls in dungeons. */
    private const val WITHER_ESSENCE_ID = "e0f3e929-869e-3dca-9504-54c666ee6f23"
    private const val REDSTONE_KEY_ID = "fed95410-aba1-39df-9b95-1d4f361eb66e"

    @JvmStatic
    fun skullKind(ownerId: String?): Kind? = when (ownerId?.lowercase(Locale.ROOT)) {
        WITHER_ESSENCE_ID -> Kind.ESSENCE
        REDSTONE_KEY_ID -> Kind.REDSTONE_KEY
        else -> null
    }

    private val PROFILE_ID = Regex(""""profileId"\s*:\s*"([0-9a-fA-F]{32})"""")

    /**
     * The kind from a head's `textures` property (base64 JSON). Hypixel may send a head without its profile id, but the
     * texture JSON still names the profile it was taken from.
     */
    @JvmStatic
    fun skullKindFromTextures(base64: String?): Kind? {
        if (base64.isNullOrEmpty()) return null
        val json = try {
            String(java.util.Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val raw = PROFILE_ID.find(json)?.groupValues?.get(1) ?: return null
        return skullKind("${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}")
    }

    /** The axis a button sticks out along: its facing on a wall, Y on a floor or ceiling. */
    @JvmStatic
    fun buttonNormal(face: AttachFace, facing: Direction): Direction.Axis =
        if (face == AttachFace.WALL) facing.axis else Direction.Axis.Y

    /** The button's vanilla depth along [normal], the whole block face on the other two axes. */
    @JvmStatic
    fun buttonShape(vanilla: VoxelShape, normal: Direction.Axis): VoxelShape {
        if (vanilla.isEmpty) return vanilla
        val b = vanilla.bounds()
        return when (normal) {
            Direction.Axis.X -> Shapes.box(b.minX, 0.0, 0.0, b.maxX, 1.0, 1.0)
            Direction.Axis.Y -> Shapes.box(0.0, b.minY, 0.0, 1.0, b.maxY, 1.0)
            Direction.Axis.Z -> Shapes.box(0.0, 0.0, b.minZ, 1.0, 1.0, b.maxZ)
        }
    }
}
