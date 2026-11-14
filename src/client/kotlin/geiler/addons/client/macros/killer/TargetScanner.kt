package geiler.addons.client.macros.killer

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.UUID
import java.util.function.Predicate

/**
 * Finds what to kill, and remembers what to leave alone for a while.
 *
 * The leaving-alone matters as much as the finding. A mob that was just hit in one-tap mode is still alive on the client
 * until the server says otherwise, and one that cannot be reached will still be the nearest thing on the next scan - in
 * both cases picking it again immediately is an infinite loop. The timers are wall-clock rather than tick counts, so they
 * mean the same thing however the game is running.
 */
class TargetScanner {
    private val ignoreUntil = HashMap<UUID, Long>()

    /**
     * Everything worth attacking right now, nearest first. The cap keeps the multi-target search honest: every candidate
     * costs a distance check on every node it expands. `allowed` is the caller's own veto, e.g. for anything that stands
     * outside the area it is working in.
     */
    fun candidates(
        player: LocalPlayer, level: ClientLevel, types: List<String>, radius: Double, limit: Int, allowed: Predicate<Entity>,
    ): List<Entity> {
        forgetExpired()
        if (types.isEmpty()) return emptyList()

        val found = ArrayList(
            level.getEntities(player, player.boundingBox.inflate(radius)) { entity ->
                isValid(level, entity, types) && allowed.test(entity) && !isIgnored(entity)
            },
        )
        found.sortWith(Comparator.comparingDouble { player.distanceToSqr(it) })
        foundAt[radius] = found.size
        if (found.size <= limit) return java.util.List.copyOf(found)
        // The nearest two thirds and the rest spread out to the farthest: a field of mobs close by must not hide every far one.
        val near = limit * 2 / 3
        return ScanCut.pick(found.size, near, limit - near).map { found[it] }
    }

    private val foundAt = HashMap<Double, Int>()

    /** How many mobs the last scan of this radius found, before the cut to the limit. */
    fun lastFound(radius: Double): Int = foundAt[radius] ?: 0

    /**
     * Whether this entity is still something this macro should be attacking: a creature - never a person playing, and
     * never a name tag - that is on the kill list, by what it is or by what it is called.
     */
    fun isValid(level: ClientLevel, entity: Entity, entries: List<String>): Boolean {
        if (!MobNames.isCreature(entity)) return false
        val living = entity as LivingEntity
        if (entity.isRemoved || !living.isAlive || living.isDeadOrDying) return false
        // A reference kept across a chunk reload or a respawn can outlive the entity it points at.
        if (level.getEntity(entity.id) !== entity) return false
        // Only a thing that is not a player has a type worth listing: a hundred creatures are "a player".
        val type = if (entity is Player) null else BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        for (entry in entries) {
            if (MobNames.isNameEntry(entry)) {
                if (MobNames.matches(level, entity, entry.substring(MobNames.PREFIX.length))) return true
            } else if (entry == type) {
                return true
            }
        }
        return false
    }

    fun ignore(entity: Entity, millis: Long) {
        ignoreUntil[entity.uuid] = Util.getMillis() + millis
    }

    /** How many times in a row each mob could not be got to. */
    private val streaks = HashMap<UUID, Int>()

    /** Leaves a mob that could not be got to alone for longer each time it happens again (see [IgnoreRule]); a kill starts it over. */
    fun ignoreUnreachable(entity: Entity): Long {
        val streak = streaks.getOrDefault(entity.uuid, 0)
        streaks[entity.uuid] = streak + 1
        val millis = IgnoreRule.millis(streak)
        ignore(entity, millis)
        return millis
    }

    fun forgetStreak(entity: Entity) {
        streaks.remove(entity.uuid)
    }

    fun isIgnored(entity: Entity): Boolean {
        val until = ignoreUntil[entity.uuid]
        return until != null && until > Util.getMillis()
    }

    fun clear() {
        ignoreUntil.clear()
        streaks.clear()
    }

    private fun forgetExpired() {
        val now = Util.getMillis()
        ignoreUntil.values.removeIf { it <= now }
    }
}
