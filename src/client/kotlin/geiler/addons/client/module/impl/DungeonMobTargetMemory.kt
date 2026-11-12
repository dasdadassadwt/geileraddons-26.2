package geiler.addons.client.module.impl

import geiler.addons.client.module.impl.DungeonMobTargetMemory.Group
import geiler.addons.client.module.impl.DungeonMobTargetMemory.Presence
import geiler.addons.client.module.impl.DungeonMobTargetMemory.Target
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.Function

/** Target-group retention policy shared by the public API and state machine. */
internal object DungeonMobTargetMemoryRules {
    @JvmStatic
    fun isPersistent(group: DungeonMobTargetMemory.Group?): Boolean =
        group == DungeonMobTargetMemory.Group.STARRED || group == DungeonMobTargetMemory.Group.MINIBOSS
}

/** Bounded dungeon-run target snapshots retained through missed scans until removal or reset. */
internal class DungeonMobTargetMemoryState {

    private data class Key(val uuid: UUID, val group: Group)
    private val targets = LinkedHashMap<Key, Target>()
    private var compatibilityRoomKey: String? = null

    /** Stores current matches and keeps starred/miniboss identities through scan gaps and chunk unloads. */
    fun update(
        currentlyMatched: Collection<Target?>?,
        gameTick: Long,
        presenceOf: Function<Target, Presence?>?,
        refreshLoaded: Function<Target, Target?>?,
    ): List<Target> {
        if (currentlyMatched != null) for (seen in currentlyMatched) {
            if (seen == null) continue
            val key = Key(seen.uuid(), seen.group())
            targets.remove(key)
            targets[key] = seen
        }
        trimToLimit()

        val retained = ArrayList<Target>()
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = entry.value
            if (currentlyMatched?.any { seen -> seen != null
                    && seen.uuid() == target.uuid() && seen.group() == target.group() } == true
            ) continue
            val presence = presenceOf?.apply(target) ?: Presence.LOADED_GONE
            if (presence == Presence.UNLOADED) {
                if (DungeonMobTargetMemoryRules.isPersistent(target.group())) retained.add(target) else iterator.remove()
                continue
            }
            if (presence == Presence.LOADED_ALIVE) {
                val refreshed = refreshLoaded?.apply(target)
                if (refreshed != null && refreshed.uuid() == target.uuid() && refreshed.group() == target.group()) {
                    entry.setValue(refreshed)
                    retained.add(refreshed)
                    continue
                }
                if (DungeonMobTargetMemoryRules.isPersistent(target.group())) {
                    retained.add(target)
                    continue
                }
            }
            iterator.remove()
        }
        return java.util.List.copyOf(retained)
    }

    /** Compatibility path for older room-scoped checks; runtime tracking uses the UUID-persistent overload. */
    @Deprecated("Compatibility overload retained for room-scoped callers")
    fun update(
        currentlyMatched: Collection<Target?>?,
        currentRoom: String?,
        gameTick: Int,
        presenceOf: Function<Target, Presence?>?,
        refreshLoaded: Function<Target, Target?>?,
    ): List<Target> {
        if (currentRoom == null || currentRoom.isBlank()) {
            clear()
            return emptyList()
        }
        if (currentRoom != compatibilityRoomKey) {
            targets.clear()
            compatibilityRoomKey = currentRoom
        }
        if (currentlyMatched != null) for (seen in currentlyMatched) {
            if (seen == null || currentRoom != seen.roomKey()) continue
            val key = Key(seen.uuid(), seen.group())
            targets.remove(key)
            targets[key] = seen
        }
        trimToLimit()
        val retained = ArrayList<Target>()
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = entry.value
            if (currentRoom != target.roomKey()) {
                iterator.remove()
                continue
            }
            if (currentlyMatched?.any { seen -> seen != null
                    && seen.uuid() == target.uuid() && seen.group() == target.group() } == true
            ) continue
            val presence = presenceOf?.apply(target) ?: Presence.LOADED_GONE
            if (presence == Presence.UNLOADED) {
                iterator.remove()
                continue
            }
            if (presence == Presence.LOADED_ALIVE) {
                val refreshed = refreshLoaded?.apply(target)
                if (refreshed != null && currentRoom == refreshed.roomKey()
                    && refreshed.uuid() == target.uuid() && refreshed.group() == target.group()
                ) {
                    entry.setValue(refreshed)
                    retained.add(refreshed)
                    continue
                }
            }
            iterator.remove()
        }
        return java.util.List.copyOf(retained)
    }

    private fun trimToLimit() {
        while (targets.size > DungeonMobTargetMemory.MAX_TARGETS) {
            val oldest = targets.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    fun clearGroup(group: Group?) {
        if (group != null) targets.keys.removeIf { key -> key.group == group }
    }

    fun removeEntityId(entityId: Int) {
        targets.entries.removeIf { entry -> entry.value.entityId() == entityId }
    }

    fun clearTransientGroups() {
        targets.keys.removeIf { key -> !DungeonMobTargetMemoryRules.isPersistent(key.group) }
    }

    fun clear() {
        targets.clear()
        compatibilityRoomKey = null
    }

    fun size(): Int = targets.size
}
