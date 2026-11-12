package geiler.addons.client.chat

import java.util.PriorityQueue

/** Small due-tick queue; callers own it on the Minecraft client thread. */
class ChatReplyCommandQueue<T>(capacity: Int) {
    private val capacity: Int
    private val entries = PriorityQueue<Entry<T>> { left, right ->
        val dueOrder = java.lang.Long.compare(left.dueTick, right.dueTick)
        if (dueOrder != 0) dueOrder else java.lang.Long.compare(left.sequence, right.sequence)
    }
    private var sequence = 0L

    init {
        if (capacity < 1) throw IllegalArgumentException("capacity must be positive")
        this.capacity = capacity
    }

    fun offer(value: T?, dueTick: Long): Boolean {
        if (value == null || dueTick < 0 || entries.size >= capacity) return false
        entries.add(Entry(dueTick, sequence++, value))
        return true
    }

    fun pollReady(clientTick: Long): T? {
        val next = entries.peek() ?: return null
        if (next.dueTick > clientTick) return null
        return entries.remove().value
    }

    fun size(): Int = entries.size

    fun clear() {
        entries.clear()
    }

    private data class Entry<T>(val dueTick: Long, val sequence: Long, val value: T)
}
