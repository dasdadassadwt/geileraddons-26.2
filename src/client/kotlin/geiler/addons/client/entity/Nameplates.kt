package geiler.addons.client.entity

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Finds the mob a Hypixel name label belongs to. */
object Nameplates {
    /** Under this tall there is no body to draw around. Vanilla's smallest mobs clear it by 3x. */
    private const val MIN_BOX_SIZE = 0.1

    /** How far from its label a mob may sit; the name floats a little above the head. */
    private const val SEARCH_RADIUS = 3.0

    /** Whether there is enough of a hitbox here to be worth drawing a box around. */
    @JvmStatic
    fun hasBody(entity: Entity): Boolean = entity.getBoundingBox().getYsize() >= MIN_BOX_SIZE

    /** Returns [matched] if it has a body, otherwise the nearest eligible mob body. */
    @JvmStatic
    fun resolveBody(level: ClientLevel, matched: Entity): Entity? = resolveBody(level, matched, false)

    /** Resolves labels to real mob bodies; player bodies require an explicit opt-in. */
    @JvmStatic
    fun resolveBody(level: ClientLevel, matched: Entity, includePlayers: Boolean): Entity? {
        if (allowedBody(matched, includePlayers)) return matched

        val at = matched.position()
        val nearby: List<LivingEntity> = level.getEntities(
            EntityTypeTest.forClass(LivingEntity::class.java),
            AABB.ofSize(at, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2),
        ) { entity -> allowedBody(entity, includePlayers) }
        return resolveBody(matched, nearby, includePlayers)
    }

    /**
     * Resolves against an already-collected nearby list. Keeping selection separate from the world
     * query makes the player-exclusion rule explicit and gives the offline harness a deterministic
     * seam for labels, direct fake players, and misleading nearby players.
     */
    @JvmStatic
    fun resolveBody(matched: Entity, nearby: List<out LivingEntity>, includePlayers: Boolean): Entity? {
        if (allowedBody(matched, includePlayers)) return matched

        val at = matched.position()
        var best: LivingEntity? = null
        var bestDistance = Double.MAX_VALUE
        for (candidate in nearby) {
            if (!allowedBody(candidate, includePlayers)) continue
            val distance = candidate.position().distanceToSqr(at)
            // Match the world-query overload: labels only bind to bodies in the local nameplate area.
            if (distance > SEARCH_RADIUS * SEARCH_RADIUS) continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    /**
     * Resolves a marker only when one living body is clearly closer than every other candidate.
     * Dungeon mob name stands can overlap in busy rooms; a missed marker is safer than a false match.
     */
    @JvmStatic
    fun resolveUnambiguousBody(
        matched: Entity?,
        nearby: List<out LivingEntity>?,
        includePlayers: Boolean,
    ): Entity? {
        if (matched == null) return null
        if (matched !is ArmorStand && allowedBody(matched, includePlayers)) return matched
        if (nearby == null) return null

        val at = matched.position()
        var best: LivingEntity? = null
        var bestDistance = Double.MAX_VALUE
        var secondDistance = Double.MAX_VALUE
        for (candidate in nearby) {
            if (candidate === matched || candidate is ArmorStand || !allowedBody(candidate, includePlayers)) continue
            val distance = candidate.position().distanceToSqr(at)
            if (distance > SEARCH_RADIUS * SEARCH_RADIUS) continue
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                best = candidate
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        return if (best != null && (secondDistance == Double.MAX_VALUE || secondDistance - bestDistance >= 0.75)) {
            best
        } else {
            null
        }
    }

    private fun allowedBody(entity: Entity, includePlayers: Boolean): Boolean =
        hasBody(entity) && (includePlayers || entity !is Player)
}
