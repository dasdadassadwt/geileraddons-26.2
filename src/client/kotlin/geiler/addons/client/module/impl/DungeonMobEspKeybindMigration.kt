package geiler.addons.client.module.impl

import geiler.addons.client.module.ModuleKeybind

/** Resolves the two retired Dungeon Mob ESP hotkeys into their combined module setting. */
object DungeonMobEspKeybindMigration {
    @JvmRecord
    data class Resolution(val keybind: ModuleKeybind, val migrated: Boolean, val conflict: Boolean)

    @JvmStatic
    fun resolve(
        currentBindPresent: Boolean,
        current: ModuleKeybind?,
        starredLegacy: ModuleKeybind?,
        minibossLegacy: ModuleKeybind?,
    ): Resolution {
        if (currentBindPresent) {
            return Resolution(current ?: ModuleKeybind.NONE, false, false)
        }

        val starredBound = starredLegacy?.isBound() == true
        val minibossBound = minibossLegacy?.isBound() == true
        if (starredBound && minibossBound && !starredLegacy!!.conflictsWith(minibossLegacy!!)) {
            return Resolution(ModuleKeybind.NONE, true, true)
        }
        if (!starredBound && !minibossBound) {
            return Resolution(ModuleKeybind.NONE, false, false)
        }
        return Resolution(if (starredBound) starredLegacy!! else minibossLegacy!!, true, false)
    }
}
