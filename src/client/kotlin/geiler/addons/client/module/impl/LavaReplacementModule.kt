package geiler.addons.client.module.impl

import geiler.addons.client.fishing.LavaReplacementRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import java.util.concurrent.atomic.AtomicLong

/**
 * Draws lava as water on chosen islands so a fishing bobber is easy to see, after SkyHanni's Lava Replacement. The
 * fluid model set hands lava the water model on the opaque layer; the chunks are rebuilt whenever that switches.
 * Purely visual: the lava is still lava.
 */
class LavaReplacementModule private constructor(settings: Settings) : Module(
    "Lava Replacement",
    "Draws lava as water on chosen islands so the bobber is easier to see.",
    Category.FISHING,
    false,
    settings.everywhere,
    settings.crimsonIsle,
    settings.kuudra,
    settings.dungeons,
) {
    private val everywhere = settings.everywhere
    private val crimsonIsle = settings.crimsonIsle
    private val kuudra = settings.kuudra
    private val dungeons = settings.dungeons

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 300)

    /** Read on chunk-build threads; written on the client thread. */
    @Volatile
    private var replacing = false

    /** Immutable water-model pair, swapped whole so chunk-build threads never see a half-written cache. */
    private class Cache(val water: FluidModel, val opaque: FluidModel)

    @Volatile
    private var cache: Cache? = null

    /** Lava lookups answered with the opaque water model; read by the status line, bumped from any thread. */
    private val lavaReplacements = AtomicLong()

    override fun onEnable() {
        lavaReplacements.set(0)
        diagnostics.enable(
            "everywhere=${everywhere.value()} crimson=${crimsonIsle.value()} kuudra=${kuudra.value()} " +
                "dungeons=${dungeons.value()}",
        )
    }

    override fun onDisable() {
        setReplacing(false, "module-disabled")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        setReplacing(wanted(), "settings-or-island")
        diagnostics.status {
            "enabled=true replacing=$replacing island=${HypixelModApi.currentIsland().name.lowercase()} " +
                "lavaReplacements=${lavaReplacements.get()} suppressedEvents=$it"
        }
    }

    private fun wanted(): Boolean = LavaReplacementRules.shouldReplace(
        SkyblockItems.inSkyblock(),
        HypixelModApi.currentIsland(),
        everywhere.value(),
        crimsonIsle.value(),
        kuudra.value(),
        dungeons.value(),
    )

    /** Rebuilds the loaded chunks only when the answer actually changes. */
    private fun setReplacing(value: Boolean, reason: String) {
        if (replacing == value) return
        replacing = value
        diagnostics.event("REPLACING $value reason=$reason")
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        client.levelRenderer?.invalidateCompiledGeometry(level, client.options, client.gameRenderer.mainCamera(), client.blockColors)
    }

    /** The water model on the opaque layer. Chunk-build threads call this; it allocates only when [water] changes. */
    private fun opaqueWater(water: FluidModel): FluidModel {
        lavaReplacements.incrementAndGet()
        val current = cache
        if (current != null && current.water === water) return current.opaque
        val opaque = FluidModel(
            ChunkSectionLayer.SOLID, water.stillMaterial(), water.flowingMaterial(), water.overlayMaterial(),
            water.tintSource(),
        )
        cache = Cache(water, opaque)
        return opaque
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Lava Replacement")
        val everywhere = spec.toggle("everywhere")
        val crimsonIsle = spec.toggle("crimsonIsle")
        val kuudra = spec.toggle("kuudra")
        val dungeons = spec.toggle("dungeons")
    }

    companion object {
        @JvmField
        val INSTANCE = LavaReplacementModule(Settings())

        /** First, cheapest check for the fluid model hook: a volatile read, safe on any thread. */
        @JvmStatic
        fun isReplacing(): Boolean = INSTANCE.replacing

        /** The model the hook returns for lava; call only after [isReplacing] and a lava check. */
        @JvmStatic
        fun opaqueWaterFor(water: FluidModel): FluidModel = INSTANCE.opaqueWater(water)
    }
}
