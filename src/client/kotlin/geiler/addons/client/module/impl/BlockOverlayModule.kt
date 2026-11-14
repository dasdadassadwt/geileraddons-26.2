package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext

/**
 * Replaces the vanilla block outline with a configurable outline and fill. Selection always follows the player's own
 * hit result, so it stays on the player's target even while Freecam moves the camera.
 */
class BlockOverlayModule private constructor(settings: Settings) : Module(
    "Block Overlay",
    "Draws a custom outline and fill on the block you look at, replacing the vanilla outline.",
    Category.VISUAL,
    false,
    settings.boxEntity,
    settings.wireColor,
    settings.fillColor,
    settings.wireWidth,
    settings.wirePhase,
    settings.fillPhase,
    settings.dynamicPhase,
) {
    private val boxEntity = settings.boxEntity
    private val wireColor = settings.wireColor
    private val fillColor = settings.fillColor
    private val wireWidth = settings.wireWidth
    private val wirePhase = settings.wirePhase
    private val fillPhase = settings.fillPhase
    private val dynamicPhase = settings.dynamicPhase
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 320)
    private var drawnFrames = 0L
    private var cancelledOutlines = 0L
    private var lastTarget = "none"

    override fun onEnable() {
        drawnFrames = 0L
        cancelledOutlines = 0L
        lastTarget = "none"
        diagnostics.enable(settingsLine())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE drawnFrames=$drawnFrames cancelledOutlines=$cancelledOutlines")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "enabled=true target=$lastTarget drawnFrames=$drawnFrames cancelledOutlines=$cancelledOutlines " +
                "${settingsLine()} suppressedEvents=$it"
        }
    }

    /** The mixin asks this before the vanilla block outline is submitted. */
    fun cancelsVanillaOutline(): Boolean {
        if (!isEnabled()) return false
        val cancel = shouldCancelVanillaOutline(true, Minecraft.getInstance().hitResult != null)
        if (cancel) cancelledOutlines++
        return cancel
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val hit = minecraft.hitResult ?: return
        val camera = minecraft.gameRenderer.mainCamera().position()
        val firstPerson = minecraft.options.cameraType.isFirstPerson
        val wire = wireColor.argb()
        val fill = fillColor.argb()
        val outlinePhase = BlockOverlayRules.phase(dynamicPhase.value(), firstPerson, wirePhase.value())
        val fillThrough = BlockOverlayRules.phase(dynamicPhase.value(), firstPerson, fillPhase.value())

        val boxes: List<DoubleArray> = when (hit.type) {
            HitResult.Type.BLOCK -> {
                val position = (hit as BlockHitResult).blockPos
                lastTarget = "block@${position.x},${position.y},${position.z}"
                val shape = level.getBlockState(position)
                    .getShape(EmptyBlockGetter.INSTANCE, position, CollisionContext.of(player))
                shape.toAabbs().map {
                    BlockOverlayRules.box(
                        position.x + it.minX, position.y + it.minY, position.z + it.minZ,
                        it.maxX - it.minX, it.maxY - it.minY, it.maxZ - it.minZ,
                    )
                }
            }
            HitResult.Type.ENTITY -> {
                val entity = (hit as EntityHitResult).entity
                lastTarget = "entity:${entity.type.descriptionId}"
                if (!boxEntity.value()) return
                val partialTick = minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)
                val position = entity.getPosition(partialTick)
                val width = entity.bbWidth.toDouble()
                listOf(
                    BlockOverlayRules.box(
                        position.x - width / 2.0, position.y, position.z - width / 2.0,
                        width, entity.bbHeight.toDouble(), width,
                    ),
                )
            }
            else -> {
                lastTarget = "none"
                return
            }
        }
        if (boxes.isEmpty()) return

        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val width = wireWidth.value()
        if (BlockOverlayRules.hasAlpha(fill)) {
            for (b in boxes) {
                EspRenderer.renderBox(
                    pose, buffers, b[0] - camera.x, b[1] - camera.y, b[2] - camera.z, b[3], b[4], b[5],
                    fill, 0, 0f, !fillThrough,
                )
            }
        }
        if (width > 0f && BlockOverlayRules.hasAlpha(wire)) {
            for (b in boxes) {
                EspRenderer.renderBox(
                    pose, buffers, b[0] - camera.x, b[1] - camera.y, b[2] - camera.z, b[3], b[4], b[5],
                    0, wire, width, !outlinePhase,
                )
            }
        }
        drawnFrames++
    }

    private fun settingsLine(): String =
        "entity=${boxEntity.value()} wire=${Integer.toHexString(wireColor.argb())} fill=${Integer.toHexString(fillColor.argb())} " +
            "width=${wireWidth.value()} wirePhase=${wirePhase.value()} fillPhase=${fillPhase.value()} " +
            "dynamicPhase=${dynamicPhase.value()}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Block Overlay")
        val boxEntity = spec.toggle("boxEntity")
        val wireColor = spec.color("wireColor")
        val fillColor = spec.color("fillColor")
        val wireWidth = spec.slider("wireWidth")
        val wirePhase = spec.toggle("wirePhase")
        val fillPhase = spec.toggle("fillPhase")
        val dynamicPhase = spec.toggle("dynamicPhase")
    }

    companion object {
        @JvmField
        val INSTANCE = BlockOverlayModule(Settings())

        /** Vanilla's outline is cancelled whenever the player has any hit result, as Devonian does. */
        @JvmStatic
        fun shouldCancelVanillaOutline(enabled: Boolean, hasHit: Boolean): Boolean = enabled && hasHit
    }
}

/** Pure rules of the Block Overlay, kept apart from Minecraft so offline checks can run them. */
object BlockOverlayRules {
    /** Dynamic phase draws through walls in first person and never in third person; otherwise the setting decides. */
    @JvmStatic
    fun phase(dynamic: Boolean, firstPerson: Boolean, configured: Boolean): Boolean =
        if (dynamic) firstPerson else configured

    @JvmStatic
    fun hasAlpha(argb: Int): Boolean = argb ushr 24 != 0

    /** One world-space box as {x, y, z, sizeX, sizeY, sizeZ}. */
    @JvmStatic
    fun box(x: Double, y: Double, z: Double, sizeX: Double, sizeY: Double, sizeZ: Double): DoubleArray =
        doubleArrayOf(x, y, z, sizeX, sizeY, sizeZ)
}
