package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.mixin.DisplayBlockDisplayAccessor
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FogType

/** Hides selected overlays, HUD bars, entities and particles; the switches and detection follow NoFrills' No Render. */
class NoRenderModule private constructor(private val settings: Settings) : Module(
    "No Render",
    "Hides chosen overlays, HUD bars, entities and particles.",
    Category.VISUAL,
    false,
    *settings.all.toTypedArray(),
) {
    /** One switch per hidden thing; [id] is the setting id in `module-settings.json`. */
    enum class Flag(val id: String) {
        FIRE_OVERLAY("fireOverlay"), FOOD_BAR("foodBar"), ARMOR_BAR("armorBar"), BOSS_BAR("bossBar"),
        EFFECT_DISPLAY("effectDisplay"), RECIPE_BOOK("recipeBook"), DEAD_ENTITIES("deadEntities"),
        DEAD_POOF("deadPoof"), LIGHTNING("lightning"), FALLING_BLOCKS("fallingBlocks"), ENTITY_FIRE("entityFire"),
        MAGE_BEAM("mageBeam"), POWDER_COATING("powderCoating"), GUIDED_SHEEP("guidedSheep"),
        BONE_PLATING("bonePlating"), TREE_BITS("treeBits"), NAUSEA("nausea"), EXP_ORBS("expOrbs"),
        STUCK_ARROWS("stuckArrows"), BLINDNESS("blindness"), DARKNESS("darkness"),
        UNDERWATER_FOG("underwaterFog"), LAVA_FOG("lavaFog"), AIR_FOG("airFog"), DISTANCE_FOG("distanceFog"),
        CLOUDS("clouds"), SOULWEAVER_SKULLS("soulweaverSkulls"), NIGHT_VISION("nightVision"),
    }

    private val hits = LongArray(Flag.values().size)
    private var lastFogType: FogType? = null
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    override fun onEnable() {
        hits.fill(0L)
        lastFogType = null
        diagnostics.enable(enabledFlags())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE ${hitSummary()}")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        // Darkness (the Warden's pulse) is a client-side effect: dropping it locally sends nothing to the server.
        val player = net.minecraft.client.Minecraft.getInstance().player
        if (player != null && on(Flag.DARKNESS) && player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
            player.removeEffect(net.minecraft.world.effect.MobEffects.DARKNESS)
            hit(Flag.DARKNESS)
        }
        // Night vision brightens the world and turns the Nether fog bright red; also client-side only.
        if (player != null && on(Flag.NIGHT_VISION) && player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) {
            player.removeEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)
            hit(Flag.NIGHT_VISION)
        }
        diagnostics.status { "enabled=true flags=[${enabledFlags()}] hits=[${hitSummary()}] suppressedEvents=$it" }
    }

    /** True when the module is on and [flag] is switched on; counts the hit for diagnostics. */
    fun active(flag: Flag): Boolean {
        if (!isEnabled() || !settings.byFlag.getValue(flag).value()) return false
        val index = flag.ordinal
        if (hits[index] < Long.MAX_VALUE) hits[index]++
        return true
    }

    /** True when the fog of [type] must be pushed out to the render distance (the old No Fog module). */
    fun shouldClearFog(type: FogType): Boolean {
        if (!isEnabled()) return false
        return shouldClearFog(type, on(Flag.UNDERWATER_FOG), on(Flag.LAVA_FOG), on(Flag.AIR_FOG))
    }

    /** Pushes the enabled fog parts of one frame out of sight, after vanilla has filled [data] in. */
    fun applyFog(type: FogType, data: FogData) {
        if (!isEnabled()) return
        var cleared = false
        if (shouldClearFog(type, on(Flag.UNDERWATER_FOG), on(Flag.LAVA_FOG), on(Flag.AIR_FOG))) {
            data.environmentalStart = FAR_START
            data.environmentalEnd = FAR_END
            data.skyEnd = FAR_END
            data.cloudEnd = FAR_END
            cleared = true
            hit(fogFlag(type))
        }
        if (on(Flag.DISTANCE_FOG)) {
            data.renderDistanceStart = FAR_START
            data.renderDistanceEnd = FAR_END
            data.skyEnd = FAR_END
            data.cloudEnd = FAR_END
            cleared = true
            hit(Flag.DISTANCE_FOG)
        }
        if (!cleared) return
        if (type != lastFogType) diagnostics.event("clearFog type=$type distance=${on(Flag.DISTANCE_FOG)}")
        lastFogType = type
    }

    /** `LevelExtractor.extractVisibleEntities`: whether [entity] must not be rendered. */
    fun shouldCancelRender(entity: Entity): Boolean {
        if (!isEnabled()) return false
        if (on(Flag.DEAD_ENTITIES) && entity is LivingEntity && !entity.isAlive) return hit(Flag.DEAD_ENTITIES)
        if (on(Flag.FALLING_BLOCKS) && entity is FallingBlockEntity && !inIsland(Island.THE_RIFT)) {
            return hit(Flag.FALLING_BLOCKS)
        }
        if (on(Flag.TREE_BITS) && entity is Display.BlockDisplay &&
            isWoodOrLeaves((entity as DisplayBlockDisplayAccessor).`geileraddons$getBlockState`())
        ) {
            if (inIsland(Island.GALATEA) || inIsland(Island.HUB) || inIsland(Island.THE_PARK)) return hit(Flag.TREE_BITS)
        }
        if (on(Flag.LIGHTNING) && entity is LightningBolt) return hit(Flag.LIGHTNING)
        if (on(Flag.EXP_ORBS) && entity is ExperienceOrb) return hit(Flag.EXP_ORBS)
        if (on(Flag.SOULWEAVER_SKULLS) && entity is ArmorStand && DungeonContextTracker.inDungeon()) {
            val head = entity.getItemBySlot(EquipmentSlot.HEAD)
            if (head.`is`(Items.PLAYER_HEAD) && NoRenderRules.isSoulWeaverTexture(headTexture(head))) return hit(Flag.SOULWEAVER_SKULLS)
        }
        if (on(Flag.GUIDED_SHEEP) && entity is Sheep && NoRenderRules.isGuidedSheepHealth(entity.health) &&
            DungeonContextTracker.inDungeon()
        ) return hit(Flag.GUIDED_SHEEP)
        if (on(Flag.BONE_PLATING) && entity is ItemEntity) {
            val stack = entity.item
            if (NoRenderRules.isBonePlatingDrop(stack.`is`(Items.BONE_MEAL), stack.hoverName.string) &&
                DungeonContextTracker.inDungeon()
            ) return hit(Flag.BONE_PLATING)
        }
        return false
    }

    /** `handleParticleEvent` after the thread check: true cancels the packet's particles. */
    fun shouldCancelParticlePacket(packet: ClientboundLevelParticlesPacket): Boolean {
        if (!isEnabled()) return false
        val type = packet.particle.type
        if (on(Flag.DEAD_POOF) && type === ParticleTypes.POOF &&
            NoRenderRules.isPoofParticle(packet.count, packet.maxSpeed, packet.xDist, packet.yDist, packet.zDist)
        ) return hit(Flag.DEAD_POOF)
        if (on(Flag.MAGE_BEAM) && type === ParticleTypes.FIREWORK && DungeonContextTracker.inDungeon()) {
            return hit(Flag.MAGE_BEAM)
        }
        if (on(Flag.POWDER_COATING)) {
            val options = packet.particle
            if (options is DustParticleOptions) {
                val color = options.color
                if (NoRenderRules.isCoatingColor(color.x, color.y, color.z) &&
                    NoRenderRules.isCoatingPacketShape(
                        packet.count, packet.maxSpeed, packet.xDist, packet.yDist, packet.zDist,
                    )
                ) return hit(Flag.POWDER_COATING)
            }
        }
        return false
    }

    /** `handleSetEntityData`: hides the name tag of a dead mob's armor stand. */
    fun onEntityNamed(entity: Entity, plainName: String) {
        if (!on(Flag.DEAD_ENTITIES) || !NoRenderRules.isDeadNameTag(plainName)) return
        entity.isCustomNameVisible = false
        hit(Flag.DEAD_ENTITIES)
    }

    /** The base64 `textures` property of a player head, or an empty string. */
    private fun headTexture(stack: net.minecraft.world.item.ItemStack): String {
        val profile = stack.get(net.minecraft.core.component.DataComponents.PROFILE) ?: return ""
        return profile.partialProfile().properties().get("textures").asSequence()
            .mapNotNull { it?.value() }
            .firstOrNull() ?: ""
    }

    private fun on(flag: Flag): Boolean = settings.byFlag.getValue(flag).value()

    private fun hit(flag: Flag): Boolean {
        val index = flag.ordinal
        if (hits[index] < Long.MAX_VALUE) hits[index]++
        return true
    }

    private fun inIsland(island: Island): Boolean = HypixelModApi.currentIsland() == island

    private fun isWoodOrLeaves(state: BlockState): Boolean {
        val block = state.block
        if (block is LeavesBlock) return true
        if (block is RotatedPillarBlock) {
            val sound = state.soundType
            return sound == SoundType.WOOD || sound == SoundType.CHERRY_WOOD ||
                sound == SoundType.BAMBOO_WOOD || sound == SoundType.NETHER_WOOD
        }
        return false
    }

    private fun enabledFlags(): String =
        Flag.values().filter { on(it) }.joinToString(",") { it.id }.ifEmpty { "none" }

    private fun hitSummary(): String =
        Flag.values().filter { hits[it.ordinal] > 0L }.joinToString(",") { "${it.id}=${hits[it.ordinal]}" }
            .ifEmpty { "none" }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("No Render")
        val byFlag: Map<Flag, BooleanSetting> = Flag.values().associateWith { spec.toggle(it.id) }
        val all = spec.settings
    }

    companion object {
        @JvmField
        val INSTANCE = NoRenderModule(Settings())

        /** Water and lava follow their own toggle; every other fog type is air fog. */
        @JvmStatic
        fun shouldClearFog(type: FogType, underwater: Boolean, lava: Boolean, air: Boolean): Boolean = when (type) {
            FogType.WATER -> underwater
            FogType.LAVA -> lava
            else -> air
        }

        private fun fogFlag(type: FogType): Flag = when (type) {
            FogType.WATER -> Flag.UNDERWATER_FOG
            FogType.LAVA -> Flag.LAVA_FOG
            else -> Flag.AIR_FOG
        }

        /** Start and end far past any render distance, so the fog is out of sight. */
        const val FAR_START = 1.0E6f
        const val FAR_END = 2.0E6f
    }
}
