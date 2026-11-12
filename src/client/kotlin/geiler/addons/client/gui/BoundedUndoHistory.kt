package geiler.addons.client.gui

import java.util.ArrayDeque
import java.util.Deque

/** Small state history for screen-local undoable edits. */
class BoundedUndoHistory<T : Any>(capacity: Int, initial: T?) {
    private val capacity: Int
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var current: T

    init {
        if (capacity < 1) throw IllegalArgumentException("History capacity must be positive")
        this.capacity = capacity
        current = initial ?: throw NullPointerException("initial")
    }

    fun canUndo(): Boolean = !undo.isEmpty()

    fun canRedo(): Boolean = !redo.isEmpty()

    fun undoSize(): Int = undo.size

    /** Records a committed state, ignoring no-op snapshots and invalidating the redo branch. */
    fun record(next: T?): Boolean {
        val nonNullNext = next ?: throw NullPointerException("next")
        if (current == nonNullNext) return false
        push(undo, current)
        current = nonNullNext
        redo.clear()
        return true
    }

    fun undo(): T? {
        if (undo.isEmpty()) return null
        push(redo, current)
        current = undo.removeLast()
        return current
    }

    fun redo(): T? {
        if (redo.isEmpty()) return null
        push(undo, current)
        current = redo.removeLast()
        return current
    }

    /** Reconciles the tracked state after restoring a snapshot without creating a history entry. */
    fun resetCurrent(restored: T?) {
        current = restored ?: throw NullPointerException("restored")
    }

    private fun push(stack: Deque<T>, value: T) {
        stack.addLast(value)
        while (stack.size > capacity) stack.removeFirst()
    }
}
