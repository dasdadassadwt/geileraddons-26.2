package geiler.addons.client.module

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent

/** A keyboard or mouse key and the modifier mask required to activate it. */
class ModuleKeybind(key: InputConstants.Key?, modifiers: Int) {
    private val keyValue = normalizeKey(key)
    private val modifiersValue = normalizeModifiers(keyValue, modifiers)

    /** Java record-compatible component accessor used by config and module callers. */
    fun key(): InputConstants.Key = keyValue

    /** Java record-compatible component accessor used by config and module callers. */
    fun modifiers(): Int = modifiersValue

    fun isBound(): Boolean = keyValue != InputConstants.UNKNOWN

    fun matches(event: KeyEvent?): Boolean = event != null && isBound() &&
        keyValue == InputConstants.getKey(event) &&
        modifiersValue == effectiveModifiers(event.key(), event.modifiers())

    fun matchesMouse(button: Int, eventModifiers: Int): Boolean = button in 0..7 && isBound() &&
        keyValue == InputConstants.Type.MOUSE.getOrCreate(button) &&
        modifiersValue == (eventModifiers and MATCHED_MODIFIERS)

    /** Whether another bind would fire on exactly the same press. */
    fun conflictsWith(other: ModuleKeybind?): Boolean = other != null && isBound() && other.isBound() &&
        keyValue == other.keyValue && modifiersValue == other.modifiersValue

    fun displayName(): String = describe { keyValue.displayName.string }

    /** Like [displayName] with the raw key id; safe before GLFW is initialized (config load), where displayName crashes. */
    fun diagnosticName(): String = describe { keyValue.name }

    private fun describe(keyText: () -> String): String {
        if (!isBound()) return "None"
        val result = StringBuilder()
        if ((modifiersValue and InputConstants.MOD_CONTROL) != 0) result.append("Ctrl + ")
        if ((modifiersValue and InputConstants.MOD_SHIFT) != 0) result.append("Shift + ")
        if ((modifiersValue and InputConstants.MOD_ALT) != 0) result.append("Alt + ")
        if ((modifiersValue and InputConstants.MOD_SUPER) != 0) result.append("Super + ")
        result.append(keyText())
        return result.toString()
    }

    override fun equals(other: Any?): Boolean = other is ModuleKeybind &&
        keyValue == other.keyValue && modifiersValue == other.modifiersValue

    override fun hashCode(): Int = 31 * keyValue.hashCode() + modifiersValue

    override fun toString(): String = "ModuleKeybind[key=$keyValue, modifiers=$modifiersValue]"

    companion object {
        private val MATCHED_MODIFIERS = InputConstants.MOD_SHIFT or InputConstants.MOD_CONTROL or
            InputConstants.MOD_ALT or InputConstants.MOD_SUPER

        @JvmField
        val NONE = ModuleKeybind(InputConstants.UNKNOWN, 0)

        @JvmStatic
        fun from(event: KeyEvent?): ModuleKeybind {
            if (event == null) return NONE
            return ModuleKeybind(
                InputConstants.getKey(event),
                effectiveModifiers(event.key(), event.modifiers()),
            )
        }

        @JvmStatic
        fun fromMouse(button: Int, modifiers: Int): ModuleKeybind {
            if (button !in 0..7) return NONE
            return ModuleKeybind(InputConstants.Type.MOUSE.getOrCreate(button), modifiers)
        }

        private fun normalizeKey(key: InputConstants.Key?): InputConstants.Key =
            if (key == null || key == InputConstants.UNKNOWN || key == keyForEscape()) {
                InputConstants.UNKNOWN
            } else {
                key
            }

        private fun normalizeModifiers(key: InputConstants.Key, modifiers: Int): Int =
            if (key == InputConstants.UNKNOWN) 0 else modifiers and MATCHED_MODIFIERS

        private fun keyForEscape(): InputConstants.Key =
            InputConstants.getKey(KeyEvent(InputConstants.KEY_ESCAPE, 0, 0))

        private fun effectiveModifiers(key: Int, modifiers: Int): Int {
            val ownModifier = when (key) {
                InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> InputConstants.MOD_SHIFT
                InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> InputConstants.MOD_CONTROL
                InputConstants.KEY_LALT, InputConstants.KEY_RALT -> InputConstants.MOD_ALT
                InputConstants.KEY_LSUPER, InputConstants.KEY_RSUPER -> InputConstants.MOD_SUPER
                else -> 0
            }
            return modifiers and MATCHED_MODIFIERS and ownModifier.inv()
        }
    }
}
