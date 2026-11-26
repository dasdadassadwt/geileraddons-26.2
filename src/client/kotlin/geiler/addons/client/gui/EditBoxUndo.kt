package geiler.addons.client.gui

/**
 * Undo history for one vanilla EditBox (see `EditBoxUndoMixin`). The box reports every value change; typed characters
 * in a row (less than [TYPING_GAP_MS] apart) form one step, everything else is its own step, and a programmatic
 * `setValue` starts a fresh history. Pure state: the mixin passes the box's value, caret and highlight in.
 */
class EditBoxUndo {
    private val model = TextFieldModel(Int.MAX_VALUE)
    private var lastChangeMs = Long.MIN_VALUE / 2

    /** True while the mixin writes an undone or redone value back into the box. */
    var restoring = false

    val text: String get() = model.text
    val cursor: Int get() = model.cursor

    /** The highlight end to restore: the anchor of the selection, or the caret when nothing was selected. */
    val highlight: Int get() = if (model.anchor >= 0) model.anchor else model.cursor

    fun changed(value: String, cursor: Int, highlight: Int, typing: Boolean, programmatic: Boolean, nowMs: Long) {
        if (restoring) return
        if (programmatic) {
            model.restart(value, cursor)
            lastChangeMs = nowMs
            return
        }
        if (!typing || nowMs - lastChangeMs > TYPING_GAP_MS) model.breakRun()
        model.observe(value, cursor, if (highlight == cursor) -1 else highlight, typing)
        lastChangeMs = nowMs
    }

    fun undo(): Boolean = model.undo()

    fun redo(): Boolean = model.redo()

    companion object {
        const val TYPING_GAP_MS = 1500L
    }
}
