package geiler.addons.client.gui

import net.minecraft.client.gui.Font
import kotlin.math.max

data class ClickGuiTextSlice(val text: String, val start: Int, val end: Int)

enum class ClickGuiTextEditKey { BACKSPACE, DELETE, LEFT, RIGHT, HOME, END }

data class ClickGuiTextEditResult(val text: String, val cursor: Int, val selectAll: Boolean)

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
