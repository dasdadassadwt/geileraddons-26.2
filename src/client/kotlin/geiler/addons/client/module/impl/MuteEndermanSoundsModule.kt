package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import java.util.EnumMap

/** Drops chosen enderman sounds at the sound engine, so sounds the client starts itself (the stare) are covered too. */
class MuteEndermanSoundsModule private constructor(
    private val sounds: ModuleSettingsSpec.Spec,
    private val islands: Map<Island, BooleanSetting>,
) : Module(
    "Mute Enderman Sounds",
    "Mutes the enderman sounds you pick, on the islands you pick.",
    Category.MISCELLANEOUS,
    false,
    *(sounds.settings + islands.values).toTypedArray<Setting>(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 200)
    private var muted = 0L

    private val bySound: Map<String, BooleanSetting> =
        SOUND_KEYS.associate { "minecraft:entity.enderman.$it" to sounds.toggle(it) }

    init {
        group(
            *sounds.groups.toTypedArray(),
            SettingGroup.folded("Islands", *islands.values.toTypedArray<Setting>()),
        )
    }

    override fun onEnable() {
        muted = 0L
        diagnostics.enable("sounds=${enabledSounds()} islandsOff=${islandsOff()}")
    }

    override fun onDisable() {
        diagnostics.event("COUNTS muted=$muted")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "sounds=${enabledSounds()} islandsOff=${islandsOff()} island=${HypixelModApi.currentIsland()} " +
                "muted=$muted suppressedEvents=$it"
        }
    }

    fun soundToggle(soundId: String): BooleanSetting? = bySound[soundId]

    fun islandToggle(island: Island): BooleanSetting? = islands[island]

    /** Islands without a switch (unknown, or not on Hypixel) count as allowed. */
    fun islandAllowed(island: Island): Boolean = islands[island]?.value() ?: true

    fun shouldMute(soundId: String, island: Island): Boolean =
        isEnabled() && bySound[soundId]?.value() == true && islandAllowed(island)

    /** `SoundEngine.play` HEAD. */
    fun onPlay(soundId: String): Boolean {
        if (!isEnabled() || soundId !in bySound) return false
        if (!shouldMute(soundId, HypixelModApi.currentIsland())) return false
        muted++
        diagnostics.event("MUTE sound=$soundId")
        return true
    }

    private fun enabledSounds(): String = bySound.filterValues { it.value() }.keys.joinToString(",") { it.substringAfterLast('.') }

    private fun islandsOff(): String = islands.filterValues { !it.value() }.keys.joinToString(",")

    companion object {
        private val SOUND_KEYS = listOf("ambient", "scream", "stare", "teleport", "hurt", "death")

        @JvmField
        val INSTANCE = MuteEndermanSoundsModule(
            ModuleSettingsSpec.of("Mute Enderman Sounds"),
            EnumMap<Island, BooleanSetting>(Island::class.java).apply {
                for (island in Island.values()) {
                    if (island.selectable()) put(island, BooleanSetting(island.label(), true))
                }
            },
        )
    }
}
