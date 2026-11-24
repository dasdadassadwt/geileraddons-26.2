package geiler.addons.client.module.impl

import geiler.addons.client.dojo.DojoRules
import geiler.addons.client.dojo.DojoRules.Test
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.performance.PingTracker
import geiler.addons.client.pricing.SkyblockItemIds
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Crimson Isle Dojo test helpers, after Skyblocker's `skyblock/crimson/dojo` (LGPL-3.0). Force: hit timers and red
 * boxes on negative zombies. Stamina: wall holes, green when coming at you. Mastery: block order and time to red.
 * Discipline: boxes on the zombies the held sword can hit. Swiftness: the newest wool block. Control: where to aim at
 * the Wither Skeleton. Tenacity: fireball paths and landing blocks. Skyblocker makes zombies glow; this mod boxes them.
 */
class DojoHelperModule private constructor(private val s: Settings) : Module(
    "Dojo Helper",
    "Skyblocker's Crimson Isle Dojo helpers: Force, Stamina, Mastery, Discipline, Swiftness, Control and Tenacity.",
    Category.MISCELLANEOUS,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var inArena = false
    private var test = Test.NONE
    private var tickCount = 0L

    private val forceZombies = HashMap<Zombie, Long>()
    private val masteryOrder = ArrayList<BlockPos>()
    private val masteryEnd = HashMap<BlockPos, Long>()
    private var swiftnessBlock: BlockPos? = null
    private var controlTarget: WitherSkeleton? = null
    private var controlLastPos: Vec3? = null
    private var controlLastUpdate = 0L
    private var controlOffset: Vec3? = null
    private var controlLastOffset: Vec3? = null
    private val fireballs = HashMap<ArmorStand, Vec3>()
    private val fireballOffsets = HashMap<ArmorStand, Vec3>()
    private var staminaBase: BlockPos? = null
    private var staminaHoles: List<AABB> = emptyList()
    private var staminaDirections: Map<AABB, DojoRules.HoleDirection> = emptyMap()

    override fun onEnable() {
        diagnostics.enable(Test.entries.drop(1).joinToString(" ") { "${it.title.lowercase(Locale.ROOT)}=${enabled(it)}" })
    }

    override fun onDisable() {
        reset()
        inArena = false
        diagnostics.disable()
    }

    private fun enabled(t: Test): Boolean = when (t) {
        Test.NONE -> false
        Test.FORCE -> s.force.value()
        Test.STAMINA -> s.stamina.value()
        Test.MASTERY -> s.mastery.value()
        Test.DISCIPLINE -> s.discipline.value()
        Test.SWIFTNESS -> s.swiftness.value()
        Test.CONTROL -> s.control.value()
        Test.TENACITY -> s.tenacity.value()
    }

    private fun onCrimson() = HypixelModApi.currentIsland() == Island.CRIMSON_ISLE

    private fun live(): Boolean = isEnabled() && inArena && onCrimson()

    private fun reset() {
        test = Test.NONE
        forceZombies.clear()
        masteryOrder.clear()
        masteryEnd.clear()
        swiftnessBlock = null
        controlTarget = null
        controlLastPos = null
        controlOffset = null
        controlLastOffset = null
        fireballs.clear()
        fireballOffsets.clear()
        staminaBase = null
        staminaHoles = emptyList()
        staminaDirections = emptyMap()
    }

    fun onWorldChanged() {
        if (!isEnabled()) return
        inArena = false
        reset()
    }

    fun onChatMessage(message: String) {
        if (!isEnabled() || !onCrimson()) return
        val text = message.trim()
        if (text == DojoRules.START_MESSAGE) {
            inArena = true
            diagnostics.event("ARENA_ENTER")
            return
        }
        if (!inArena) return
        if (DojoRules.finished(text)) {
            diagnostics.event("TEST_END test=$test")
            inArena = false
            reset()
            return
        }
        if (test != Test.NONE) return
        val announced = DojoRules.testFrom(text) ?: return
        test = if (enabled(announced)) announced else Test.NONE
        diagnostics.event("TEST_START announced=$announced active=$test")
    }

    private fun pingMs(): Long {
        PingTracker.latestMs(System.nanoTime())?.let { return it.toLong() }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0L
        return mc.connection?.getPlayerInfo(player.uuid)?.latency?.toLong() ?: 0L
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        tickCount++
        if (!live()) return
        // Skyblocker updates Stamina and Control every 3 ticks.
        if (tickCount % 3L == 0L) when (test) {
            Test.STAMINA -> updateStamina()
            Test.CONTROL -> updateControl()
            else -> {}
        }
        if (tickCount % 20L == 0L) {
            forceZombies.keys.removeIf { it.isRemoved }
            fireballs.keys.removeIf { it.isRemoved }
            fireballOffsets.keys.removeIf { it !in fireballs }
        }
        diagnostics.statusOnChange {
            "inArena=$inArena test=$test forceZombies=${forceZombies.size} mastery=${masteryOrder.size} swiftness=${swiftnessBlock != null} " +
                "control=${controlTarget != null} fireballs=${fireballs.size} staminaBase=${staminaBase != null} holes=${staminaHoles.size}"
        }
    }

    fun onEntityLoad(entity: Entity) {
        if (!live()) return
        val player = Minecraft.getInstance().player ?: return
        // Within 50 blocks and 5 vertically, as Skyblocker checks.
        if (!entity.closerThan(player, 50.0, 5.0)) return
        when (test) {
            Test.FORCE -> if (entity is Zombie) forceZombies[entity] = System.currentTimeMillis() + DojoRules.FORCE_ZOMBIE_LIFETIME_MS
            Test.CONTROL -> if (entity is WitherSkeleton && controlTarget == null) controlTarget = entity
            Test.TENACITY -> if (entity is ArmorStand) fireballs[entity] = entity.position()
            else -> {}
        }
    }

    fun onEntityUnload(entity: Entity) {
        if (!live()) return
        if (entity is Zombie) forceZombies.remove(entity)
        if (entity is ArmorStand) {
            fireballs.remove(entity)
            fireballOffsets.remove(entity)
        }
    }

    fun onAttack(entity: Entity) {
        if (live() && test == Test.FORCE && entity is Zombie && forceZombies.containsKey(entity)) {
            forceZombies[entity] = System.currentTimeMillis() + DojoRules.FORCE_ZOMBIE_LIFETIME_MS
        }
    }

    fun onBlockChanged(pos: BlockPos, new: BlockState) {
        if (!live()) return
        when (test) {
            Test.MASTERY -> {
                if (new.`is`(Blocks.WOOL.lime())) {
                    val player = Minecraft.getInstance().player ?: return
                    val at = pos.immutable()
                    masteryOrder.add(at)
                    masteryEnd[at] = DojoRules.masteryEndMs(System.currentTimeMillis(), pingMs(), player.position().distanceTo(Vec3.atCenterOf(at)))
                }
                if (new.isAir) {
                    masteryOrder.remove(pos)
                    masteryEnd.remove(pos)
                }
            }
            Test.SWIFTNESS -> if (new.`is`(Blocks.WOOL.lime())) swiftnessBlock = pos.immutable()
            else -> {}
        }
    }

    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!live() || test != Test.TENACITY || packet.particle.type != ParticleTypes.FLAME) return
        val at = Vec3(packet.x, packet.y, packet.z)
        val nearest = fireballs.keys.minByOrNull { it.position().distanceTo(at) }?.takeIf { it.position().distanceTo(at) < 50.0 } ?: return
        fireballOffsets[nearest] = at.subtract(nearest.position())
    }

    private fun updateControl() {
        val target = controlTarget ?: return
        controlLastPos?.let {
            controlLastOffset = controlOffset
            controlOffset = DojoRules.controlOffset(it, target.position(), pingMs())
        }
        controlLastPos = target.position()
        controlLastUpdate = System.currentTimeMillis()
    }

    private fun updateStamina() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val base = staminaBase
        if (base == null) {
            val p = player.blockPosition()
            for (x in p.x - 10 until p.x + 10) for (y in p.y - 5 until p.y) for (z in p.z - 10 until p.z + 10) {
                if (level.getBlockState(BlockPos(x, y, z)).`is`(Blocks.CHISELED_STONE_BRICKS)) {
                    staminaBase = BlockPos(x, y, z)
                    return
                }
            }
            return
        }
        val bottom = ArrayList<BlockPos>()
        for (x in base.x - 15 until base.x + 15) for (z in base.z - 15 until base.z + 15) {
            val pos = BlockPos(x, base.y + 1, z)
            if (!level.getBlockState(pos).isAir) bottom.add(pos)
        }
        val air = { x: Int, y: Int, z: Int -> level.getBlockState(BlockPos(x, y, z)).isAir }
        val holes = DojoRules.walls(bottom).flatMap { DojoRules.holes(it, air) }
        val last = staminaHoles
        val lastDirections = staminaDirections
        staminaDirections = holes.associateWith { hole ->
            val direction = DojoRules.direction(hole, last)
            if (direction == DojoRules.HoleDirection.UNCHANGED) lastDirections[hole] ?: DojoRules.HoleDirection.NEW else direction
        }
        staminaHoles = holes
    }

    // ---- drawing ----

    fun render(context: LevelRenderContext) {
        if (!live() || test == Test.NONE) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val camera = mc.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        fun box(b: AABB, fill: Int, line: Int, width: Float, depth: Boolean) = EspRenderer.renderBox(pose, buffers,
            b.minX - camera.x, b.minY - camera.y, b.minZ - camera.z, b.xsize, b.ysize, b.zsize, fill, line, width, depth)
        fun line(a: Vec3, b: Vec3, color: Int, width: Float) = EspRenderer.renderLine(pose, buffers,
            a.x - camera.x, a.y - camera.y, a.z - camera.z, b.x - camera.x, b.y - camera.y, b.z - camera.z, color, width)
        when (test) {
            Test.FORCE -> forEachTagged(Zombie::class.java) { zombie, tag ->
                if (DojoRules.forceNegative(tag)) box(ProjectedLabelRenderer.entityBox(zombie), 0x30FF0000, 0xFFFF0000.toInt(), 2f, false)
            }
            Test.DISCIPLINE -> {
                val held = player.mainHandItem.get(DataComponents.CUSTOM_DATA)?.copyTag()?.let { SkyblockItemIds.id(it) } ?: ""
                val color = DojoRules.disciplineColor(held)
                if (color != 0) forEachTagged(Zombie::class.java) { zombie, tag ->
                    if (DojoRules.disciplineMatches(held, tag)) box(ProjectedLabelRenderer.entityBox(zombie), color and 0x30FFFFFF, color, 2f, false)
                }
            }
            Test.SWIFTNESS -> swiftnessBlock?.let { box(AABB(it), 0x8000FF00.toInt(), 0, 0f, false) }
            Test.MASTERY -> {
                masteryOrder.firstOrNull()?.let { first ->
                    val ends = BlockEspTracerGeometry.endpoints(camera, mc.gameRenderer.mainCamera().forwardVector(), Vec3.atCenterOf(first), 0.5)
                    EspRenderer.renderLine(pose, buffers, ends.start.x, ends.start.y, ends.start.z, ends.end.x, ends.end.y, ends.end.z,
                        0xFFC0C0C0.toInt(), 2f)
                }
                if (masteryOrder.size >= 2) line(Vec3.atCenterOf(masteryOrder[0]), Vec3.atCenterOf(masteryOrder[1]), 0xFFC0C0C0.toInt(), 2f)
            }
            Test.STAMINA -> {
                val p = player.blockPosition()
                for (hole in staminaHoles) {
                    val incoming = DojoRules.incoming(hole, staminaDirections[hole], p.x, p.z)
                    box(hole, if (incoming) 0x4D00FF00 else 0x4DFFA300, 0, 0f, false)
                }
            }
            Test.CONTROL -> controlAim()?.let { aim ->
                val target = AABB(aim.add(-0.5, -0.5, -0.5), aim.add(0.5, 0.5, 0.5))
                val eye = player.getEyePosition(partial())
                val looking = target.clip(eye, eye.add(player.getViewVector(partial()).scale(30.0))).isPresent
                box(target, 0, if (looking) 0xFF00FF00.toInt() else 0xFFC0C0C0.toInt(), 3f, false)
            }
            Test.TENACITY -> for ((fireball, start) in fireballs) {
                val offset = fireballOffsets[fireball] ?: Vec3.ZERO
                val from = start.add(offset)
                val delta = fireball.position().add(offset).subtract(from)
                if (delta.length() <= 0.02) continue
                val to = from.add(delta.scale(100.0))
                line(from, to, 0xFFFF0000.toInt(), 3f)
                val hit = mc.level?.clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, fireball))
                if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) box(AABB(hit.blockPos), 0x80FF0000.toInt(), 0, 0f, true)
            }
            Test.NONE -> {}
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Timers as world labels through the shared projected-label renderer. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!live()) return
        val mc = Minecraft.getInstance()
        if (mc.gui.hud.isHidden) return
        val camera = mc.gameRenderer.mainCamera()
        val now = System.currentTimeMillis()
        when (test) {
            Test.FORCE -> for ((zombie, end) in forceZombies) {
                if (zombie.isRemoved) continue
                val seconds = maxOf((end - now) / 1000f, 0f)
                val color = when { seconds > 1 -> 0xFF55FF55.toInt(); seconds > 0 -> 0xFFFFFF55.toInt(); else -> 0xFFFF5555.toInt() }
                ProjectedLabelRenderer.drawFloatingText(graphics, camera, mc.font, zombie.getEyePosition(partial()),
                    String.format(Locale.ROOT, "%.1f", seconds), color, 1.5f)
            }
            Test.MASTERY -> for (pos in masteryOrder) {
                val seconds = maxOf(((masteryEnd[pos] ?: now) - now) / 1000f, 0f)
                ProjectedLabelRenderer.drawFloatingText(graphics, camera, mc.font, Vec3.atCenterOf(pos.above()),
                    String.format(Locale.ROOT, "%.2f", seconds), 0xFFFFFFFF.toInt(), 2.0f)
            }
            else -> {}
        }
    }

    private fun controlAim(): Vec3? {
        val target = controlTarget ?: return null
        val offset = controlOffset ?: return null
        val lastOffset = controlLastOffset ?: return null
        val progress = (System.currentTimeMillis() - controlLastUpdate) / 150.0
        return target.getEyePosition(partial()).add(offset.scale(progress)).add(lastOffset.scale(1 - progress))
    }

    private fun partial(): Float = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)

    /** Each tracked mob with the plain name of the tag armor stand floating over it. */
    private fun <T : Entity> forEachTagged(type: Class<T>, action: (T, String) -> Unit) {
        val level = Minecraft.getInstance().level ?: return
        val player = Minecraft.getInstance().player ?: return
        for (mob in level.getEntitiesOfClass(type, player.boundingBox.inflate(50.0, 5.0, 50.0))) {
            val tag = level.getEntitiesOfClass(ArmorStand::class.java, mob.boundingBox.inflate(0.3, 1.5, 0.3).move(0.0, 0.5, 0.0))
                .firstNotNullOfOrNull { stand -> stand.customName?.string?.let { ChatText.plain(it).trim() }?.takeIf { it.isNotEmpty() } }
                ?: mob.customName?.string?.let { ChatText.plain(it).trim() } ?: continue
            action(mob, tag)
        }
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Dojo Helper")
        val force = spec.toggle("force")
        val stamina = spec.toggle("stamina")
        val mastery = spec.toggle("mastery")
        val discipline = spec.toggle("discipline")
        val swiftness = spec.toggle("swiftness")
        val control = spec.toggle("control")
        val tenacity = spec.toggle("tenacity")
    }

    companion object {
        @JvmField
        val INSTANCE = DojoHelperModule(Settings())
    }
}
