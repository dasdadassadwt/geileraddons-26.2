package geiler.addons.client.module.impl

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.module.ModuleKeybind

/** Zoom used its own "Zoom Key" setting; it now holds on the module keybind, so the old key is carried over once. */
object ZoomKeybindMigration {
    /**
     * The bind to use, or null to keep what is there: a saved module bind always wins, an old key that does not
     * parse is ignored, and an old "unbound" key leaves the module unbound.
     */
    @JvmStatic
    fun resolve(savedModuleBind: Boolean, oldKey: String?): ModuleKeybind? {
        if (savedModuleBind || oldKey.isNullOrBlank()) return null
        return try {
            ModuleKeybind(InputConstants.getKey(oldKey), 0)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Whether [bind] is held now: its key is down and exactly its modifiers are down. */
    @JvmStatic
    fun held(bind: ModuleKeybind, keyDown: Boolean, shift: Boolean, control: Boolean, alt: Boolean, superKey: Boolean): Boolean {
        if (!bind.isBound() || !keyDown) return false
        var mods = 0
        if (shift) mods = mods or InputConstants.MOD_SHIFT
        if (control) mods = mods or InputConstants.MOD_CONTROL
        if (alt) mods = mods or InputConstants.MOD_ALT
        if (superKey) mods = mods or InputConstants.MOD_SUPER
        return ModuleKeybind(bind.key(), mods) == bind
    }
}
