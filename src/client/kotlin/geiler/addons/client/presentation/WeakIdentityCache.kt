package geiler.addons.client.presentation

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Weak, identity-keyed, size-bounded cache. `WeakHashMap` hashes chat Components structurally (recursing
 * through siblings and hover events) on every lookup; identity keys make a hit O(1).
 */
class WeakIdentityCache<K : Any, V : Any>(private val maximumEntries: Int) {
    private val stale = ReferenceQueue<K>()
    private val entries = HashMap<Key<K>, V>()

    val size: Int
        get() {
            purge()
            return entries.size
        }

    operator fun get(key: K): V? {
        purge()
        return entries[Key(key, null)]
    }

    operator fun set(key: K, value: V) {
        purge()
        entries[Key(key, stale)] = value
        val iterator = entries.keys.iterator()
        while (entries.size > maximumEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    fun clear() = entries.clear()

    private fun purge() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            entries.remove(stale.poll() as? Key<K> ?: break)
        }
    }

    private class Key<K : Any>(referent: K, queue: ReferenceQueue<K>?) : WeakReference<K>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key<*>) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}
