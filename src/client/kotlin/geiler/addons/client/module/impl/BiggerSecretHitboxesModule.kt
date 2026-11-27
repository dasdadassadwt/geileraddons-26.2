package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.SecretHitboxRules
import geiler.addons.client.dungeon.SecretHitboxRules.Kind
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumMap

/**
 * Makes dungeon secrets easier to click: levers, chests and the essence / redstone key skulls get a full-block hitbox,
 * buttons fill their face but keep their depth. Only the client's block raycast (aiming and clicking) changes; the
 * block's collision shape is untouched, so movement is the same.
 */
class BiggerSecretHitboxesModule private constructor(
    private val toggles: Map<Kind, BooleanSetting>,
) : Module(
    "Bigger Secret Hitboxes",
    "Full-block click hitboxes for dungeon levers, buttons, chests and secret skulls. Movement is not affected.",
    Category.F7,
    false,
    *toggles.values.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)
    private val counts = EnumMap<Kind, Int>(Kind::class.java)
    private var lastPos: BlockPos? = null
    private var lastKind: Kind? = null

    init {
        group(SettingGroup("Secrets", *toggles.values.toTypedArray()))
    }

    override fun onEnable() {
        counts.clear()
        lastPos = null
        diagnostics.enable("types=${enabledKinds()}")
    }

    override fun onDisable() {
        diagnostics.event("COUNTS ${countText()}")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "types=${enabledKinds()} inDungeon=${DungeonContextTracker.inDungeon()} distinctTargets=${countText()} " +
                "lastKind=$lastKind lastPos=${lastPos?.toShortString()} suppressedEvents=$it"
        }
    }

    /** `ClipContext.getBlockShape` RETURN for OUTLINE clips: the enlarged shape, or null to keep vanilla. */
    fun outlineShape(state: BlockState, level: BlockGetter, pos: BlockPos, vanilla: VoxelShape): VoxelShape? {
        if (!isEnabled() || level !is ClientLevel || !DungeonContextTracker.inDungeon()) return null
        val block = state.block
        val kind = when (block) {
            is LeverBlock -> Kind.LEVER
            is ButtonBlock -> Kind.BUTTON
            is ChestBlock -> Kind.CHEST
            is AbstractSkullBlock -> skullKind(level, pos)
            else -> null
        } ?: return null
        if (toggles[kind]?.value() != true) return null
        if (pos != lastPos) {
            lastPos = pos.immutable()
            lastKind = kind
            counts.merge(kind, 1, Int::plus)
        }
        if (kind != Kind.BUTTON) return Shapes.block()
        val normal = SecretHitboxRules.buttonNormal(
            state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE), state.getValue(HorizontalDirectionalBlock.FACING))
        return SecretHitboxRules.buttonShape(vanilla, normal)
    }

    /** By profile id, then by the profile id inside the texture JSON; unknown heads are logged once each. */
    private fun skullKind(level: ClientLevel, pos: BlockPos): Kind? {
        val profile = (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile() ?: return null
        val id = profile.id()?.toString()
        val textures = profile.properties().get("textures").firstOrNull()?.value()
        val kind = SecretHitboxRules.skullKind(id) ?: SecretHitboxRules.skullKindFromTextures(textures)
        if (kind == null && pos != lastUnknownSkull) {
            lastUnknownSkull = pos.immutable()
            diagnostics.event("SKULL_UNMATCHED id=$id hasTextures=${textures != null}")
        }
        return kind
    }

    private var lastUnknownSkull: BlockPos? = null

    private fun enabledKinds(): String = toggles.filterValues { it.value() }.keys.joinToString(",")

    private fun countText(): String = Kind.entries.joinToString(",") { "$it:${counts[it] ?: 0}" }

    companion object {
        @JvmField
        val INSTANCE = BiggerSecretHitboxesModule(linkedMapOf(
            Kind.LEVER to BooleanSetting("Levers", true),
            Kind.BUTTON to BooleanSetting("Buttons", true),
            Kind.CHEST to BooleanSetting("Chests", true),
            Kind.ESSENCE to BooleanSetting("Wither Essence", true),
            Kind.REDSTONE_KEY to BooleanSetting("Redstone Key", true),
        ))
    }
}
