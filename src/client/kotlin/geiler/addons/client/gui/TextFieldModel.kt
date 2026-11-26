package geiler.addons.client.gui

import geiler.addons.client.gui.ClickGuiTextEditing.clampCursor

/**
 * One single-line text field with a real selection and undo history. Pure state, no Minecraft types: every custom
 * text input of the mod edits through this class so they all behave the same.
 *
 * [anchor] is the fixed end of a selection (negative = none). Typing coalesces into one undo step; paste, cut and
 * deletions are separate steps; moving the caret ends the current typing run.
 */
class TextFieldModel @JvmOverloads constructor(
    var maxLength: Int = 256,
    var filter: ((String) -> String)? = null,
    private val historyCapacity: Int = 100,
) {
    private data class Snap(val text: String, val cursor: Int, val anchor: Int)

    var text: String = ""
        private set
    var cursor: Int = 0
        private set
    var anchor: Int = -1
        private set

    private var history = BoundedUndoHistory(historyCapacity, Snap("", 0, -1))
    private var coalescing = false
    private var dragAnchor = -1

    private fun snap() = Snap(text, cursor, anchor)

    fun hasSelection(): Boolean = !ClickGuiTextEditing.selection(text, cursor, anchor).isEmpty()

    fun selection(): ClickGuiTextEditing.Selection = ClickGuiTextEditing.selection(text, cursor, anchor)

    /** Replaces the content from outside (a different field, a reset): caret at the end, history cleared. */
    fun reset(value: String) = load(value.take(maxLength))

    private fun load(value: String) {
        text = value
        cursor = text.length
        anchor = -1
        dragAnchor = -1
        coalescing = false
        history = BoundedUndoHistory(historyCapacity, snap())
    }

    /**
     * Syncs the model with a field whose text, caret and selection live elsewhere. The undo history survives while
     * the text matches; when the field's text changed behind the model's back, the history starts over.
     */
    fun adopt(value: String, newCursor: Int, newAnchor: Int) {
        if (value != text) load(value)
        if (clampCursor(text, newCursor) != cursor) coalescing = false
        cursor = clampCursor(text, newCursor)
        anchor = if (newAnchor >= 0 && newAnchor != cursor) clampCursor(text, newAnchor) else -1
        history.resetCurrent(snap())
    }

    /** Records an edit made elsewhere (a vanilla EditBox) as the new state; [coalesce] merges it into a typing run. */
    fun observe(value: String, newCursor: Int, newAnchor: Int, coalesce: Boolean): Boolean =
        commit(ClickGuiTextEditResult(value, newCursor, false, newAnchor), coalesce)

    /** The text was set from code: take it as the new baseline and forget the history. */
    fun restart(value: String, newCursor: Int) {
        load(value)
        cursor = clampCursor(text, newCursor)
        history = BoundedUndoHistory(historyCapacity, snap())
    }

    /** Ends the current typing run so the next typed character starts a new undo step. */
    fun breakRun() {
        coalescing = false
    }

    /** Replaces the whole content as one undoable edit (for example Escape clearing a search). */
    fun replaceAll(value: String): Boolean {
        val next = value.take(maxLength)
        return commit(ClickGuiTextEditResult(next, next.length, false, -1), false)
    }

    fun selectAll() {
        val all = ClickGuiTextEditing.selectAllState(text)
        cursor = all.cursor
        anchor = if (text.isEmpty()) -1 else all.anchor
        endRun()
    }

    fun copy(): String = ClickGuiTextEditing.selectedText(text, cursor, anchor)

    /** Removes and returns the selection, or null when nothing is selected. */
    fun cut(): String? {
        if (!hasSelection()) return null
        val picked = copy()
        commit(ClickGuiTextEditing.deleteSelection(text, cursor, anchor), false)
        return picked
    }

    fun paste(raw: String?): Boolean = insert(ClickGuiTextEditing.sanitizePaste(raw), false)

    /** Inserts typed characters; consecutive typing is one undo step. */
    fun type(chars: String): Boolean = insert(chars, true)

    private fun insert(chars: String, coalesce: Boolean): Boolean {
        val inserted = filter?.invoke(chars) ?: chars
        if (inserted.isEmpty()) return false
        val edit = ClickGuiTextEditing.insertReplacingSelection(text, cursor, anchor, inserted, maxLength)
        return commit(edit, coalesce && !hasSelection())
    }

    /** Navigation and deletion. Returns true when the text changed. */
    fun key(key: ClickGuiTextEditKey, ctrl: Boolean, shift: Boolean): Boolean {
        val deleting = key == ClickGuiTextEditKey.BACKSPACE || key == ClickGuiTextEditKey.DELETE
        if (!ctrl || key == ClickGuiTextEditKey.HOME || key == ClickGuiTextEditKey.END) {
            val edit = ClickGuiTextEditing.applyKeyWithSelection(text, cursor, anchor, key, shift)
            if (deleting) return commit(edit, false)
            move(edit.cursor, edit.anchor)
            return false
        }
        if (deleting) {
            if (hasSelection()) return commit(ClickGuiTextEditing.deleteSelection(text, cursor, anchor), false)
            val backwards = key == ClickGuiTextEditKey.BACKSPACE
            val from = clampCursor(text, cursor)
            val to = if (backwards) ClickGuiTextEditing.wordLeft(text, from) else ClickGuiTextEditing.wordRight(text, from)
            if (to == from) return false
            val start = minOf(from, to)
            val end = maxOf(from, to)
            return commit(ClickGuiTextEditResult(text.removeRange(start, end), start, false, -1), false)
        }
        val from = clampCursor(text, cursor)
        val target = if (key == ClickGuiTextEditKey.LEFT) ClickGuiTextEditing.wordLeft(text, from)
        else ClickGuiTextEditing.wordRight(text, from)
        val keep = if (shift) (if (anchor >= 0) clampCursor(text, anchor) else from) else -1
        move(target, if (keep == target) -1 else keep)
        return false
    }

    /** [count]: 1 places the caret (shift extends), 2 selects the word, 3 or more selects everything. */
    fun click(index: Int, count: Int, shift: Boolean) {
        val at = clampCursor(text, index)
        when {
            count >= 3 -> {
                selectAll()
                dragAnchor = 0
            }
            count == 2 -> {
                val word = ClickGuiTextEditing.wordRangeAt(text, at)
                move(word.end, if (word.isEmpty()) -1 else word.start)
                dragAnchor = word.start
            }
            shift -> {
                val keep = if (anchor >= 0) clampCursor(text, anchor) else clampCursor(text, cursor)
                move(at, if (keep == at) -1 else keep)
                dragAnchor = keep
            }
            else -> {
                move(at, -1)
                dragAnchor = at
            }
        }
    }

    /** Extends the selection from the click that started the drag. */
    fun drag(index: Int) {
        val at = clampCursor(text, index)
        val from = if (dragAnchor >= 0) dragAnchor else clampCursor(text, cursor)
        move(at, if (from == at) -1 else from)
    }

    fun undo(): Boolean = restore(history.undo())

    fun redo(): Boolean = restore(history.redo())

    private fun restore(snap: Snap?): Boolean {
        if (snap == null) return false
        text = snap.text
        cursor = snap.cursor
        anchor = snap.anchor
        coalescing = false
        return true
    }

    private fun move(newCursor: Int, newAnchor: Int) {
        cursor = clampCursor(text, newCursor)
        anchor = if (newAnchor >= 0) clampCursor(text, newAnchor) else -1
        endRun()
    }

    private fun endRun() {
        coalescing = false
        history.resetCurrent(snap())
    }

    private fun commit(edit: ClickGuiTextEditResult, coalesce: Boolean): Boolean {
        val changed = edit.text != text
        text = edit.text
        cursor = clampCursor(text, edit.cursor)
        anchor = if (edit.anchor >= 0 && edit.anchor != cursor) clampCursor(text, edit.anchor) else -1
        if (!changed) {
            endRun()
            return false
        }
        if (coalesce && coalescing) history.resetCurrent(snap()) else history.record(snap())
        coalescing = coalesce
        return true
    }

    /** Counts quick clicks at nearly the same spot: 1 = single, 2 = double, 3 = triple (capped). */
    class ClickCounter @JvmOverloads constructor(
        private val windowMs: Long = 400L,
        private val slop: Int = 4,
    ) {
        private var count = 0
        private var lastMs = Long.MIN_VALUE / 2
        private var lastX = 0
        private var lastY = 0

        fun register(nowMs: Long, x: Int, y: Int): Int {
            val near = kotlin.math.abs(x - lastX) <= slop && kotlin.math.abs(y - lastY) <= slop
            count = if (nowMs - lastMs <= windowMs && near) minOf(3, count + 1) else 1
            lastMs = nowMs
            lastX = x
            lastY = y
            return count
        }

        fun reset() {
            count = 0
            lastMs = Long.MIN_VALUE / 2
        }
    }
}
