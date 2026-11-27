package geiler.addons.client.combat

/** Which mobs you were seen to damage. A mark only says "you hit it", never that the server counts you for loot. */
class ShareMarks {
    private val ids = HashSet<Int>()

    fun hit(entityId: Int): Boolean = entityId in ids

    fun onAttack(entityId: Int) {
        if (ids.size < MAX) ids.add(entityId)
    }

    /** A server damage event: it counts only when you are named as its cause (melee, arrows and some magic). */
    fun onDamage(entityId: Int, causeId: Int, selfId: Int) {
        if (causeId == selfId) onAttack(entityId)
    }

    fun retain(live: Set<Int>) {
        ids.retainAll(live)
    }

    fun clear() = ids.clear()

    fun size(): Int = ids.size

    private companion object {
        const val MAX = 512
    }
}
