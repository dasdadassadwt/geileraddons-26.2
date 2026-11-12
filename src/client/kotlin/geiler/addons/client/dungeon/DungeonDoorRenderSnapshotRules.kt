package geiler.addons.client.dungeon

/** Position-diff rules for render-only dungeon-door snapshots. */
object DungeonDoorRenderSnapshotRules {
    /** Keys whose source or replacement changed need their containing chunk section rebuilt. */
    @JvmStatic
    fun <V> changedPositions(previous: Map<Long, V>?, next: Map<Long, V>?): Set<Long> {
        val before = previous ?: emptyMap()
        val after = next ?: emptyMap()
        val changed = HashSet(before.keys)
        after.forEach { (position, value) ->
            if (value == before[position]) changed.remove(position) else changed.add(position)
        }
        return java.util.Set.copyOf(changed)
    }

    /** A new level needs its active mesh substitutions built; the previous level has been discarded. */
    @JvmStatic
    fun <V> invalidationPositions(previous: Map<Long, V>?, next: Map<Long, V>?, sameLevel: Boolean): Set<Long> {
        val after = next ?: emptyMap()
        return if (sameLevel) changedPositions(previous, after) else java.util.Set.copyOf(after.keys)
    }
}
