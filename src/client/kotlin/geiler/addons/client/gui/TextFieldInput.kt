package geiler.addons.client.gui

import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Keyboard glue between Minecraft key events and [TextFieldModel]: select all, copy, cut, paste, undo, redo, word
 * navigation and deletion. Every custom text input of the mod routes through [handleKey] and [handleChar].
 */
object TextFieldInput {
    enum class Outcome { IGNORED, HANDLED, CHANGED }

    private val diagnostics = ModuleDiagnostics(Category.DEV, { "Text Input" }, 6, 100L, 200)
    private var diagnosticsOn = false
    private var lastAdvanceMs = 0L

    /** Bounded, content-free trace of editing operations (no typed text is ever written). */
    @JvmStatic
    fun trace(field: String, op: String, model: TextFieldModel) =
        traceEdit(field, op, model.text.length, model.cursor, model.hasSelection())

    @JvmStatic
    fun traceEdit(field: String, op: String, length: Int, cursor: Int, selected: Boolean) {
        val now = System.currentTimeMillis()
        if (!diagnosticsOn) {
            diagnosticsOn = true
            diagnostics.enable("scope=custom text inputs")
        }
        if (now - lastAdvanceMs >= 50L) {
            lastAdvanceMs = now
            diagnostics.advance()
        }
        diagnostics.event("field=$field op=$op length=$length cursor=$cursor selected=$selected")
    }

    @JvmStatic
    fun handleKey(model: TextFieldModel, event: KeyEvent, field: String): Outcome {
        val key = event.key()
        val ctrl = event.hasControlDown()
        val shift = (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0
        val minecraft = Minecraft.getInstance()
        if (ctrl && !shift) {
            when (key) {
                GLFW.GLFW_KEY_A -> { model.selectAll(); trace(field, "select-all", model); return Outcome.HANDLED }
                GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_INSERT -> {
                    val picked = model.copy()
                    if (picked.isNotEmpty()) minecraft.keyboardHandler.clipboard = picked
                    trace(field, "copy", model)
                    return Outcome.HANDLED
                }
                GLFW.GLFW_KEY_X -> return cut(model, field)
                GLFW.GLFW_KEY_V -> return paste(model, field)
                GLFW.GLFW_KEY_Z -> return history(model, field, redo = false)
                GLFW.GLFW_KEY_Y -> return history(model, field, redo = true)
            }
        }
        if (ctrl && shift && key == GLFW.GLFW_KEY_Z) return history(model, field, redo = true)
        if (shift && !ctrl) {
            if (key == GLFW.GLFW_KEY_DELETE) return cut(model, field)
            if (key == GLFW.GLFW_KEY_INSERT) return paste(model, field)
        }
        val edit = editKey(key) ?: return Outcome.IGNORED
        val changed = model.key(edit, ctrl, shift)
        if (changed) trace(field, if (ctrl) "delete-word" else "delete", model)
        return if (changed) Outcome.CHANGED else Outcome.HANDLED
    }

    @JvmStatic
    fun handleChar(model: TextFieldModel, event: CharacterEvent, field: String): Outcome {
        if (!event.isAllowedChatCharacter) return Outcome.IGNORED
        return if (model.type(event.codepointAsString())) Outcome.CHANGED else Outcome.HANDLED
    }

    /** Click counter shared by every field; one physical mouse cannot click two fields at once. */
    private val clicks = TextFieldModel.ClickCounter()

    /** Click number for a press at ([x], [y]) now: 1 = single, 2 = double, 3 = triple. */
    @JvmStatic
    fun clickCount(x: Int, y: Int): Int = clicks.register(System.currentTimeMillis(), x, y)

    /** Where a click of [count] (1 caret, 2 word, 3 all) at [clickCursor] leaves the caret and selection of [text]. */
    @JvmStatic
    fun caretPlace(text: String, clickCursor: Int, count: Int): ClickGuiTextEditResult {
        val probe = TextFieldModel(Int.MAX_VALUE)
        probe.reset(text)
        probe.click(clickCursor, count, false)
        return ClickGuiTextEditResult(probe.text, probe.cursor, false, probe.anchor)
    }

    /**
     * Mouse press inside a field's text area: caret, word (double) or everything (triple); shift extends.
     * [textX] and [width] are the text origin and the visible width in GUI pixels.
     */
    @JvmStatic
    fun mousePressed(model: TextFieldModel, font: net.minecraft.client.gui.Font, mouseX: Double, mouseY: Double,
        textX: Int, width: Int, shift: Boolean, maxCount: Int = 3) {
        val count = minOf(maxCount, clicks.register(System.currentTimeMillis(), mouseX.toInt(), mouseY.toInt()))
        model.click(ClickGuiTextEditing.cursorAtMouse(font, model.text, mouseX, textX, width, model.cursor), count, shift)
    }

    @JvmStatic
    fun mouseDragged(model: TextFieldModel, font: net.minecraft.client.gui.Font, mouseX: Double, textX: Int, width: Int) {
        model.drag(ClickGuiTextEditing.cursorAtMouse(font, model.text, mouseX, textX, width, model.cursor))
    }

    /** Draws selection, text and (while [focused]) a blinking caret. Returns the x just after the drawn text. */
    @JvmStatic
    fun draw(graphics: net.minecraft.client.gui.GuiGraphicsExtractor, font: net.minecraft.client.gui.Font,
        model: TextFieldModel, x: Int, y: Int, width: Int, textColor: Int, selectionColor: Int, focused: Boolean,
        shadow: Boolean = false): Int {
        val slice = ClickGuiTextEditing.visibleSlice(font, model.text, if (focused) model.cursor else model.text.length, width)
        val top = y - 1
        val bottom = y + font.lineHeight
        if (focused) {
            val range = model.selection()
            if (!range.isEmpty() && slice.text.isNotEmpty()) {
                val from = ClickGuiTextEditing.clampCursor(slice.text, range.start - slice.start)
                val to = ClickGuiTextEditing.clampCursor(slice.text, range.end - slice.start)
                if (to > from) graphics.fill(x + font.width(slice.text.substring(0, from)), top,
                    x + font.width(slice.text.substring(0, to)), bottom, selectionColor)
            }
        }
        graphics.text(font, slice.text, x, y, textColor, shadow)
        if (focused && (System.currentTimeMillis() / 500L) % 2 == 0L) {
            val inSlice = ClickGuiTextEditing.clampCursor(slice.text, model.cursor - slice.start)
            val caretX = x + font.width(slice.text.substring(0, inSlice))
            graphics.fill(caretX, top, caretX + 1, bottom, textColor)
        }
        return x + font.width(slice.text)
    }

    private fun cut(model: TextFieldModel, field: String): Outcome {
        val picked = model.cut() ?: return Outcome.HANDLED
        Minecraft.getInstance().keyboardHandler.clipboard = picked
        trace(field, "cut", model)
        return Outcome.CHANGED
    }

    private fun paste(model: TextFieldModel, field: String): Outcome {
        val changed = model.paste(Minecraft.getInstance().keyboardHandler.clipboard)
        trace(field, if (changed) "paste" else "paste-empty", model)
        return if (changed) Outcome.CHANGED else Outcome.HANDLED
    }

    private fun history(model: TextFieldModel, field: String, redo: Boolean): Outcome {
        val moved = if (redo) model.redo() else model.undo()
        trace(field, (if (redo) "redo" else "undo") + (if (moved) "" else "-empty"), model)
        return if (moved) Outcome.CHANGED else Outcome.HANDLED
    }

    private fun editKey(key: Int): ClickGuiTextEditKey? = when (key) {
        GLFW.GLFW_KEY_BACKSPACE -> ClickGuiTextEditKey.BACKSPACE
        GLFW.GLFW_KEY_DELETE -> ClickGuiTextEditKey.DELETE
        GLFW.GLFW_KEY_LEFT -> ClickGuiTextEditKey.LEFT
        GLFW.GLFW_KEY_RIGHT -> ClickGuiTextEditKey.RIGHT
        GLFW.GLFW_KEY_HOME -> ClickGuiTextEditKey.HOME
        GLFW.GLFW_KEY_END -> ClickGuiTextEditKey.END
        else -> null
    }
}
