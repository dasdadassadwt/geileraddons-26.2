package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.misc.GhostRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.monster.Creeper

/**
 * Ghost Vision (NoFrills): the Dwarven Mines ghosts are creepers below Y 100. They are boxed and their charged swirl,
 * which hides them, is removed.
 */
class GhostVisionModule private constructor() : Module(
    "Ghost Vision",
    "Makes the ghosts of the Dwarven Mines easier to see.",
    Category.VISUAL,
    false,
    *spec.settings.toTypedArray(),
) {
    private val style = spec.choice("style")
    private val fillColor = spec.color("fillColor")
    private val outlineColor = spec.color("outlineColor")
    private val hideCharge = spec.toggle("hideCharge")

    private var ghosts: Set<Int> = emptySet()
    private var drawn = 0
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 240)

    override fun onEnable() {
        ghosts = emptySet()
        diagnostics.enable("style=${style.value()} hideCharge=${hideCharge.value()}")
    }

    override fun onDisable() {
        ghosts = emptySet()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val level = client.level
        if (level == null || client.player == null) {
            ghosts = emptySet()
            return
        }
        diagnostics.advance()
        val island = HypixelModApi.currentIsland()
        val found = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            val creeper = entity as? Creeper ?: continue
            if (GhostRules.isGhost(island, true, creeper.y)) found.add(creeper.id)
        }
        if (found.size != ghosts.size) diagnostics.event("GHOSTS count=${found.size}")
        ghosts = found
        diagnostics.status { "enabled=true island=${island.label()} ghosts=${ghosts.size} drawn=$drawn suppressedEvents=$it" }
    }

    /** Called from the Creeper mixin: a ghost is never drawn charged. */
    fun suppressCharge(creeper: Creeper): Boolean = isEnabled() && hideCharge.value() && creeper.id in ghosts

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || ghosts.isEmpty()) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val buffers = WorldRenderBuffer.current()
        val mode = style.value()
        val fill = mode == "FILLED" || mode == "FILLED_OUTLINE"
        val outline = mode == "OUTLINE" || mode == "FILLED_OUTLINE"
        for (id in ghosts) {
            val creeper = level.getEntity(id) as? Creeper ?: continue
            if (!creeper.isAlive) continue
            val box = creeper.boundingBox
            val dx = Mth.lerp(partial.toDouble(), creeper.xOld, creeper.x) - creeper.x
            val dy = Mth.lerp(partial.toDouble(), creeper.yOld, creeper.y) - creeper.y
            val dz = Mth.lerp(partial.toDouble(), creeper.zOld, creeper.z) - creeper.z
            EspRenderer.renderBox(
                context.poseStack(), buffers,
                box.minX + dx - camera.x, box.minY + dy - camera.y, box.minZ + dz - camera.z,
                box.xsize, box.ysize, box.zsize,
                if (fill) fillColor.argb() else 0, if (outline) outlineColor.argb() else 0, 2.5f, true,
            )
            drawn++
        }
        if (drawn > 0) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Ghost Vision")

        @JvmField
        val INSTANCE = GhostVisionModule()
    }
}
