package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Stops rendering other real players, near you or everywhere (Odin's Hide Players, BSD 3-Clause).
 * Written independently; NPCs (non-v4 UUIDs) and yourself are never hidden.
 */
class HidePlayersModule private constructor(private val s: Settings) : Module(
    "Hide Players",
    "Hides players in your vicinity.",
    Category.VISUAL,
    false,
    s.onlyInDungeons,
    s.hideAll,
    s.distance,
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 200)
    private var hiddenChecks = 0L

    override fun onEnable() {
        hiddenChecks = 0L
        diagnostics.enable(flags())
    }

    override fun onDisable() {
        diagnostics.event("COUNTS hiddenChecks=$hiddenChecks")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "${flags()} inDungeon=${DungeonContextTracker.inDungeon()} hiddenChecks=$hiddenChecks suppressedEvents=$it" }
    }

    /** `LevelExtractor.isEntityVisible`: true when this entity must not be drawn. Runs per entity per frame. */
    fun shouldHide(entity: Entity): Boolean {
        if (!isEnabled() || entity !is Player) return false
        val self = Minecraft.getInstance().player ?: return false
        val realPlayer = entity !== self && entity.uuid.version() == 4
        val hide = shouldHide(
            s.onlyInDungeons.value(),
            DungeonContextTracker.inDungeon(),
            s.hideAll.value(),
            entity.distanceToSqr(self),
            s.distance.value(),
            realPlayer,
        )
        if (hide && hiddenChecks < Long.MAX_VALUE) hiddenChecks++
        return hide
    }

    private fun flags(): String =
        "onlyInDungeons=${s.onlyInDungeons.value()} hideAll=${s.hideAll.value()} distance=${s.distance.value()}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Hide Players")
        val onlyInDungeons = spec.toggle("onlyInDungeons")
        val hideAll = spec.toggle("hideAll")
        val distance = spec.slider("distance")
    }

    companion object {
        @JvmField
        val INSTANCE = HidePlayersModule(Settings())

        /** Pure rule: a real player is hidden within [distance] blocks, or at any range with Hide all. */
        @JvmStatic
        fun shouldHide(
            onlyInDungeons: Boolean,
            inDungeon: Boolean,
            hideAll: Boolean,
            distanceSq: Double,
            distance: Float,
            realPlayer: Boolean,
        ): Boolean = realPlayer && (!onlyInDungeons || inDungeon) && (hideAll || distanceSq <= distance * distance)
    }
}
