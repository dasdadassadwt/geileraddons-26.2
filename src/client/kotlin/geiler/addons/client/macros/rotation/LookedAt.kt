package geiler.addons.client.macros.rotation

import geiler.addons.client.macros.killer.MobNames
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

/**
 * The creature under the crosshair, however far away. The game's own pick only reaches as far as a player can hit - three
 * blocks - which is no use for pointing at a mob down a tunnel to put its type on a list.
 */
object LookedAt {
    /**
     * The nearest creature on the line of sight within `range`, with nothing solid in front of it, or null. People playing
     * and name tags are looked through; a creature a server has made out of a player entity is not.
     */
    @JvmStatic
    fun creature(client: Minecraft, range: Double): LivingEntity? {
        val player = client.player
        val level = client.level
        if (player == null || level == null) return null

        val eye = player.eyePosition
        val look = player.getViewVector(1.0f)
        val end = eye.add(look.scale(range))

        // Anything behind the first block the line meets is out of sight, whatever its box says.
        val block = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val limit = if (block.type == HitResult.Type.MISS) range * range else eye.distanceToSqr(block.location)

        var best: LivingEntity? = null
        var bestDistance = limit
        val search = player.boundingBox.expandTowards(look.scale(range)).inflate(1.0)
        for (entity in level.getEntities(player, search) { MobNames.isCreature(it) }) {
            val box = entity.boundingBox.inflate(entity.pickRadius.toDouble())
            val hit = box.clip(eye, end)
            if (hit.isEmpty) continue
            val distance = eye.distanceToSqr(hit.get())
            if (distance <= bestDistance) {
                bestDistance = distance
                best = entity as LivingEntity
            }
        }
        return best
    }
}
