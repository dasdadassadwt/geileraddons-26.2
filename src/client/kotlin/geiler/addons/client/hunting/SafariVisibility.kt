package geiler.addons.client.hunting

import geiler.addons.client.render.WorldToScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * The gate between "the client knows" and "the player could know".
 *
 * The server tracks entities and loads blocks well past what anyone can see, so reporting either raw would reveal
 * what is behind a wall. Two questions, deliberately separate: [canSee] (on screen and unobstructed) for anything
 * that announces or latches a result, and [hasLineTo] (unobstructed, any facing) for anything merely counted, since
 * a count that emptied when you turned round would read as a bug.
 */
object SafariVisibility {
    private const val MAX_RANGE = 64.0

    @JvmStatic
    fun hasLineTo(entity: Entity): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val eye = player.eyePosition
        val box = entity.boundingBox
        if (eye.distanceToSqr(box.center) > MAX_RANGE * MAX_RANGE) return false
        // Several points, not one: a small critter's centre can sit behind a lip of stone while most of it is in view.
        return samples(box).any { clear(eye, it, null) }
    }

    @JvmStatic
    fun canSee(entity: Entity): Boolean = onScreen(entity.boundingBox.center) && hasLineTo(entity)

    /** Whether the block position itself is in view. A ray stopping on that very block has reached it. */
    @JvmStatic
    fun canSee(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val centre = Vec3.atCenterOf(pos)
        if (!onScreen(centre)) return false
        val eye = player.eyePosition
        if (eye.distanceToSqr(centre) > MAX_RANGE * MAX_RANGE) return false
        return clear(eye, centre, pos)
    }

    private fun onScreen(point: Vec3): Boolean {
        val mc = Minecraft.getInstance()
        val projected = FloatArray(2)
        if (!WorldToScreen.projectInto(mc.gameRenderer.mainCamera(), point, projected)) return false
        return projected[0] in 0f..mc.window.guiScaledWidth.toFloat() && projected[1] in 0f..mc.window.guiScaledHeight.toFloat()
    }

    /** Nothing solid between [from] and [to] (other than [target] itself), and no painting in the way. */
    private fun clear(from: Vec3, to: Vec3, target: BlockPos?): Boolean {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val blocked = hit.type != HitResult.Type.MISS && !(target != null && hit.blockPos == target)
        if (blocked) return false
        // Paintings are entities, so a block ray goes straight through one. Hideonwalls sit behind them.
        val search = AABB(from, to).inflate(1.0)
        return level.getEntities(player, search) { it.type == EntityTypes.PAINTING }.none { it.boundingBox.clip(from, to).isPresent }
    }

    private fun samples(box: AABB): List<Vec3> {
        val c = box.center
        return listOf(c, Vec3(c.x, box.maxY, c.z), Vec3(c.x, box.minY + 0.05, c.z))
    }
}
