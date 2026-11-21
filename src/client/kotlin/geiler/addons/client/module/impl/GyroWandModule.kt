package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonClassTab
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.Items

/** Shows the Gyrokinetic Wand's area of effect and its cooldown, after Odin's Gyro Wand. */
class GyroWandModule private constructor(private val settings: Settings) : Module(
    "Gyro Wand",
    "Shows area of effect and cooldown of the Gyrokinetic Wand.",
    Category.VISUAL,
    false,
    *settings.spec.settings.toTypedArray(),
) {
    private val burst = GyroWandRules.BurstDetector()
    private var lastUseMs = 0L
    private var cooldownTotalMs = GyroWandRules.BASE_COOLDOWN_MS
    private var lastLevel: ClientLevel? = null
    private var casts = 0L
    private var drawnFrames = 0L
    private var lastRenderOutcome = "not-rendered"
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)

    override fun onEnable() {
        casts = 0L
        drawnFrames = 0L
        reset()
        diagnostics.enable(settingsLine())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE casts=$casts drawnFrames=$drawnFrames")
        diagnostics.disable()
    }

    private fun reset() {
        lastUseMs = 0L
        burst.reset()
    }

    /** Called by the shared client tick registration: world change reset and the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        diagnostics.status {
            "enabled=true casts=$casts drawnFrames=$drawnFrames onCooldown=${onCooldown()} " +
                "lastRender=$lastRenderOutcome suppressedEvents=$it"
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun onCooldown(): Boolean = nowMs() - lastUseMs < cooldownTotalMs

    /** `ClientLevel.doAddParticle`: a burst of still witch particles is one wand cast. */
    fun onParticle(options: ParticleOptions, y: Double, xd: Double, yd: Double, zd: Double) {
        if (!isEnabled()) return
        if (!GyroWandRules.isStillBurstParticle(options.type === ParticleTypes.WITCH, xd, yd, zd) || onCooldown()) return
        val now = nowMs()
        if (burst.onParticle(now, y)) {
            lastUseMs = now
            cooldownTotalMs = GyroWandRules.cooldownMs(mageMultiplier())
            casts++
            diagnostics.event("cast total=$casts cooldownMs=$cooldownTotalMs")
        }
    }

    private fun mageMultiplier(): Double {
        if (!DungeonContextTracker.inDungeon()) return 1.0
        val client = Minecraft.getInstance()
        val rows = DungeonClassTab.parseAll(DungeonAssistModule.scoreTabLines(client))
        return DungeonClassTab.mageCooldownMultiplier(rows, client.player?.name?.string)
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        val held = player.mainHandItem
        if (!held.`is`(Items.BLAZE_ROD) || ItemBackgrounds.info(held).skyblockId != GyroWandRules.WAND_ID) {
            lastRenderOutcome = "wand-not-held"
            return
        }
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
        val position = player.getPosition(partial)
        val eye = position.add(
            0.0, GyroWandRules.eyeHeight(player.pose == Pose.SWIMMING, player.isCrouching), 0.0,
        )
        val look = player.getViewVector(partial)
        val end = eye.add(look.scale(GyroWandRules.TRACE_DISTANCE))
        val mutable = BlockPos.MutableBlockPos()
        val hit = GyroWandRules.firstNonAir(eye.x, eye.y, eye.z, end.x, end.y, end.z) { x, y, z ->
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4)
            when {
                chunk == null -> GyroWandRules.UNLOADED
                chunk.getBlockState(mutable.set(x, y, z)).isAir -> GyroWandRules.AIR
                else -> GyroWandRules.SOLID
            }
        }
        if (hit == null) {
            lastRenderOutcome = "no-target"
            return
        }
        val camera = client.gameRenderer.mainCamera().position()
        val color = if (settings.showCooldown.value() && onCooldown()) settings.cooldownColor.argb()
            else settings.color.argb()
        val depth = settings.depthCheck.value()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val cx = hit[0] + 0.5 - camera.x
        val cz = hit[2] + 0.5 - camera.z
        val baseY = hit[1] + 1.0 - camera.y
        val radius = GyroWandRules.RADIUS
        if (settings.fill.value()) {
            EspRenderer.renderFlatArea(pose, buffers, cx, baseY + GyroWandRules.HEIGHT, cz, radius.toDouble(), 0.0, false, color, depth)
        }
        EspRenderer.renderRing(pose, buffers, cx, baseY, cz, radius, color or OUTLINE_ALPHA, OUTLINE_WIDTH, depth)
        EspRenderer.renderRing(
            pose, buffers, cx, baseY + GyroWandRules.HEIGHT, cz, radius, color or OUTLINE_ALPHA, OUTLINE_WIDTH, depth,
        )
        GeilerAddonsRenderTypes.endBatches(buffers)
        drawnFrames++
        lastRenderOutcome = "drawn target=${hit[0]},${hit[1]},${hit[2]} cooldown=${onCooldown()}"
    }

    private fun settingsLine(): String =
        "color=${Integer.toHexString(settings.color.argb())} showCooldown=${settings.showCooldown.value()} " +
            "cooldownColor=${Integer.toHexString(settings.cooldownColor.argb())} depthCheck=${settings.depthCheck.value()} fill=${settings.fill.value()}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Gyro Wand")
        val color = spec.color("color")
        val showCooldown = spec.toggle("showCooldown")
        val cooldownColor = spec.color("cooldownColor")
        val depthCheck = spec.toggle("depthCheck")
        val fill = spec.toggle("fill")
    }

    companion object {
        /** The rings are always drawn at full opacity; only the disc keeps the configured alpha. */
        private const val OUTLINE_ALPHA = 0xFF000000.toInt()
        private const val OUTLINE_WIDTH = 2.0f

        @JvmField
        val INSTANCE = GyroWandModule(Settings())
    }
}
