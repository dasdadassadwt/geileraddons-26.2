package geiler.addons.client.gui

import net.minecraft.client.gui.Font
import kotlin.math.max

data class ClickGuiTextSlice(val text: String, val start: Int, val end: Int)

enum class ClickGuiTextEditKey { BACKSPACE, DELETE, LEFT, RIGHT, HOME, END }

/** [anchor] is the fixed end of a selection; a negative value, or one equal to [cursor], means nothing is selected. */
data class ClickGuiTextEditResult @JvmOverloads constructor(
    val text: String,
    val cursor: Int,
    val selectAll: Boolean,
    val anchor: Int = -1,
)

/** Unicode-safe cursor and visible-text calculations shared by Click GUI fields. */
object ClickGuiTextEditing {
    @JvmStatic
    fun insertAtCursor(
        text: String?,
        cursor: Int,
        selectAll: Boolean,
        inserted: String?,
    ): ClickGuiTextEditResult {
        val value = if (selectAll) "" else text.orEmpty()
        val insertionCursor = if (selectAll) 0 else clampCursor(value, cursor)
        val insertion = inserted.orEmpty()
        return ClickGuiTextEditResult(
            value.substring(0, insertionCursor) + insertion + value.substring(insertionCursor),
            insertionCursor + insertion.length,
            false,
        )
    }

    @JvmStatic
    fun applyKey(
        text: String?,
        cursor: Int,
        selectAll: Boolean,
        key: ClickGuiTextEditKey,
    ): ClickGuiTextEditResult {
        val value = text.orEmpty()
        val boundedCursor = clampCursor(value, cursor)

        if (selectAll) {
            return when (key) {
                ClickGuiTextEditKey.BACKSPACE, ClickGuiTextEditKey.DELETE -> ClickGuiTextEditResult("", 0, false)
                ClickGuiTextEditKey.LEFT, ClickGuiTextEditKey.HOME -> ClickGuiTextEditResult(value, 0, false)
                ClickGuiTextEditKey.RIGHT, ClickGuiTextEditKey.END -> ClickGuiTextEditResult(value, value.length, false)
            }
        }

        return when (key) {
            ClickGuiTextEditKey.BACKSPACE -> {
                if (boundedCursor == 0) ClickGuiTextEditResult(value, boundedCursor, false)
                else {
                    val start = value.offsetByCodePoints(boundedCursor, -1)
                    ClickGuiTextEditResult(value.removeRange(start, boundedCursor), start, false)
                }
            }
            ClickGuiTextEditKey.DELETE -> {
                if (boundedCursor == value.length) ClickGuiTextEditResult(value, boundedCursor, false)
                else {
                    val end = value.offsetByCodePoints(boundedCursor, 1)
                    ClickGuiTextEditResult(value.removeRange(boundedCursor, end), boundedCursor, false)
                }
            }
            ClickGuiTextEditKey.LEFT -> ClickGuiTextEditResult(value, moveCursor(value, boundedCursor, -1), false)
            ClickGuiTextEditKey.RIGHT -> ClickGuiTextEditResult(value, moveCursor(value, boundedCursor, 1), false)
            ClickGuiTextEditKey.HOME -> ClickGuiTextEditResult(value, 0, false)
            ClickGuiTextEditKey.END -> ClickGuiTextEditResult(value, value.length, false)
        }
    }

    /** The selected character range, ordered and clamped to [text]. */
    data class Selection(val start: Int, val end: Int) {
        fun isEmpty(): Boolean = start == end
    }

    @JvmStatic
    fun selection(text: String?, cursor: Int, anchor: Int): Selection {
        val value = text.orEmpty()
        if (anchor < 0) return Selection(0, 0)
        val a = clampCursor(value, anchor)
        val c = clampCursor(value, cursor)
        return Selection(minOf(a, c), maxOf(a, c))
    }

    @JvmStatic
    fun selectedText(text: String?, cursor: Int, anchor: Int): String {
        val range = selection(text, cursor, anchor)
        return if (range.isEmpty()) "" else text.orEmpty().substring(range.start, range.end)
    }

    @JvmStatic
    fun selectAllState(text: String?): ClickGuiTextEditResult {
        val value = text.orEmpty()
        return ClickGuiTextEditResult(value, value.length, false, 0)
    }

    @JvmStatic
    fun deleteSelection(text: String?, cursor: Int, anchor: Int): ClickGuiTextEditResult {
        val value = text.orEmpty()
        val range = selection(value, cursor, anchor)
        if (range.isEmpty()) return ClickGuiTextEditResult(value, clampCursor(value, cursor), false, -1)
        return ClickGuiTextEditResult(value.removeRange(range.start, range.end), range.start, false, -1)
    }

    /** Replaces the selection (or inserts at the caret), keeping the result within [maxLength] characters. */
    @JvmStatic
    fun insertReplacingSelection(
        text: String?,
        cursor: Int,
        anchor: Int,
        inserted: String?,
        maxLength: Int,
    ): ClickGuiTextEditResult {
        val base = deleteSelection(text, cursor, anchor)
        var insertion = inserted.orEmpty()
        val room = max(0, maxLength - base.text.length)
        if (insertion.length > room) {
            insertion = insertion.substring(0, room)
            if (insertion.isNotEmpty() && Character.isHighSurrogate(insertion.last())) insertion = insertion.dropLast(1)
        }
        val at = base.cursor
        return ClickGuiTextEditResult(
            base.text.substring(0, at) + insertion + base.text.substring(at), at + insertion.length, false, -1,
        )
    }

    /** Applies a navigation or delete key; shift extends the selection from the previous caret. */
    @JvmStatic
    fun applyKeyWithSelection(
        text: String?,
        cursor: Int,
        anchor: Int,
        key: ClickGuiTextEditKey,
        shift: Boolean,
    ): ClickGuiTextEditResult {
        val value = text.orEmpty()
        val range = selection(value, cursor, anchor)
        val bounded = clampCursor(value, cursor)
        if (key == ClickGuiTextEditKey.BACKSPACE || key == ClickGuiTextEditKey.DELETE) {
            if (!range.isEmpty()) return deleteSelection(value, cursor, anchor)
            val edit = applyKey(value, bounded, false, key)
            return ClickGuiTextEditResult(edit.text, edit.cursor, false, -1)
        }
        val moved = if (!shift && !range.isEmpty()) {
            when (key) {
                ClickGuiTextEditKey.LEFT -> range.start
                ClickGuiTextEditKey.RIGHT -> range.end
                ClickGuiTextEditKey.HOME -> 0
                else -> value.length
            }
        } else applyKey(value, bounded, false, key).cursor
        if (!shift) return ClickGuiTextEditResult(value, moved, false, -1)
        val keptAnchor = if (anchor >= 0) clampCursor(value, anchor) else bounded
        return ClickGuiTextEditResult(value, moved, false, if (keptAnchor == moved) -1 else keptAnchor)
    }

    /** One-line clipboard text: breaks and tabs become spaces, other control characters are dropped. */
    @JvmStatic
    fun sanitizePaste(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val spaced = raw.replace(Regex("\\r\\n|[\\r\\n\\t]"), " ")
        return buildString(spaced.length) { for (ch in spaced) if (!Character.isISOControl(ch)) append(ch) }
    }

    /** 0 = whitespace, 1 = letters, digits and underscore, 2 = everything else. */
    private fun wordClass(codePoint: Int): Int = when {
        Character.isWhitespace(codePoint) -> 0
        Character.isLetterOrDigit(codePoint) || codePoint == '_'.code -> 1
        else -> 2
    }

    /** Start of the word before [cursor]: skips spaces, then one run of the same character class. */
    @JvmStatic
    fun wordLeft(text: String?, cursor: Int): Int {
        val value = text.orEmpty()
        var i = clampCursor(value, cursor)
        while (i > 0 && wordClass(value.codePointBefore(i)) == 0) i -= Character.charCount(value.codePointBefore(i))
        if (i == 0) return 0
        val kind = wordClass(value.codePointBefore(i))
        while (i > 0 && wordClass(value.codePointBefore(i)) == kind) i -= Character.charCount(value.codePointBefore(i))
        return i
    }

    /** Start of the next word after [cursor]: skips the current run, then the spaces behind it. */
    @JvmStatic
    fun wordRight(text: String?, cursor: Int): Int {
        val value = text.orEmpty()
        var i = clampCursor(value, cursor)
        if (i < value.length) {
            val kind = wordClass(value.codePointAt(i))
            if (kind != 0) while (i < value.length && wordClass(value.codePointAt(i)) == kind) i += Character.charCount(value.codePointAt(i))
        }
        while (i < value.length && wordClass(value.codePointAt(i)) == 0) i += Character.charCount(value.codePointAt(i))
        return i
    }

    /** The run of same-class characters under [index] (or just before it at the end of the text). */
    @JvmStatic
    fun wordRangeAt(text: String?, index: Int): Selection {
        val value = text.orEmpty()
        if (value.isEmpty()) return Selection(0, 0)
        val at = clampCursor(value, index)
        val probe = if (at < value.length) at else value.offsetByCodePoints(at, -1)
        val kind = wordClass(value.codePointAt(probe))
        var start = probe
        while (start > 0 && wordClass(value.codePointBefore(start)) == kind) start -= Character.charCount(value.codePointBefore(start))
        var end = probe
        while (end < value.length && wordClass(value.codePointAt(end)) == kind) end += Character.charCount(value.codePointAt(end))
        return Selection(start, end)
    }

    /** A drag keeps the anchor it started with, otherwise it starts at the caret placed by the first click. */
    @JvmStatic
    fun dragAnchor(clickCursor: Int, existingAnchor: Int): Int = if (existingAnchor >= 0) existingAnchor else clickCursor

    @JvmStatic
    fun clampCursor(text: String?, cursor: Int): Int {
        val value = text ?: return 0
        val bounded = cursor.coerceIn(0, value.length)
        if (
            bounded > 0 && bounded < value.length &&
            Character.isLowSurrogate(value[bounded]) && Character.isHighSurrogate(value[bounded - 1])
        ) {
            return bounded - 1
        }
        return bounded
    }

    @JvmStatic
    fun moveCursor(text: String?, cursor: Int, direction: Int): Int {
        val value = text ?: return 0
        val bounded = clampCursor(value, cursor)
        return when {
            direction < 0 && bounded > 0 -> value.offsetByCodePoints(bounded, -1)
            direction > 0 && bounded < value.length -> value.offsetByCodePoints(bounded, 1)
            else -> bounded
        }
    }

    @JvmStatic
    fun visibleSlice(font: Font, text: String?, cursor: Int, width: Int): ClickGuiTextSlice {
        if (text.isNullOrEmpty() || width <= 0) return ClickGuiTextSlice("", 0, 0)
        val boundedCursor = clampCursor(text, cursor)
        var start = boundedCursor
        val leftBudget = max(1, if (boundedCursor == text.length) width else width / 2)
        while (start > 0) {
            val previous = text.offsetByCodePoints(start, -1)
            if (font.width(text.substring(previous, boundedCursor)) > leftBudget) break
            start = previous
        }
        var end = boundedCursor
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            if (font.width(text.substring(start, next)) > width) break
            end = next
        }
        return ClickGuiTextSlice(text.substring(start, end), start, end)
    }

    @JvmStatic
    fun cursorAtMouse(
        font: Font,
        text: String?,
        mouseX: Double,
        textX: Int,
        width: Int,
        currentCursor: Int,
    ): Int = cursorAtMouse(font, text, mouseX, textX, width, currentCursor, 1.0f)

    @JvmStatic
    fun cursorAtMouse(
        font: Font,
        text: String?,
        mouseX: Double,
        textX: Int,
        width: Int,
        currentCursor: Int,
        textScale: Float,
    ): Int {
        val value = text.orEmpty()
        if (value.isEmpty()) return 0
        val scale = max(0.01f, textScale)
        val slice = visibleSlice(font, value, currentCursor, Math.max(1, Math.round(width / scale)))
        val relativeX = textOffsetForMouse(mouseX, textX, scale)
        var cursor = slice.start
        var previousWidth = 0
        while (cursor < slice.end) {
            val next = value.offsetByCodePoints(cursor, 1)
            val nextWidth = font.width(value.substring(slice.start, next))
            if (relativeX < (previousWidth + nextWidth) / 2.0) return cursor
            cursor = next
            previousWidth = nextWidth
        }
        return slice.end
    }

    @JvmStatic
    fun textOffsetForMouse(mouseX: Double, textX: Int, textScale: Float): Double =
        (mouseX - textX) / max(0.01f, textScale)
}
