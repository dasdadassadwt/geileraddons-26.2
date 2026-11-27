package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.profileviewer.ProfileViewerScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** `/pv [name]` opens the Profile Viewer while on; off sends `/pv` on to the server. `/ga pv` always opens it. */
class ProfileViewerModule private constructor(
    /** Garden and Museum tabs sign in to the SkyBlockPV backend (Mojang server-join check; the token stays with Mojang). */
    val gardenAndMuseum: BooleanSetting,
) : Module(
    "Profile Viewer",
    "/pv [player] (or /ga pv) shows a player's SkyBlock profiles: skills, dungeons, inventories, collections, pets, mining and slayers.",
    Category.MISCELLANEOUS,
    false,
    gardenAndMuseum,
) {
    // Logs counts and outcomes only: no player names and no profile numbers.
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 200)
    private var lookups = 0
    private var failures = 0

    override fun onEnable() {
        diagnostics.enable("command=/pv gardenAndMuseum=${gardenAndMuseum.value()}")
    }

    override fun onDisable() {
        diagnostics.event("COUNTS lookups=$lookups failures=$failures")
        diagnostics.disable()
    }

    /** Runs `/pv`; [player] null means yourself. */
    fun command(player: String?, always: Boolean): Int {
        val mc = Minecraft.getInstance()
        if (!always && !isEnabled()) {
            // Raw packet: sendCommand would run this client /pv again and recurse until StackOverflowError.
            mc.player?.connection?.send(
                net.minecraft.network.protocol.game.ServerboundChatCommandPacket(if (player == null) "pv" else "pv $player"))
            return 1
        }
        val name = player ?: mc.player?.gameProfile?.name()
        if (name == null) {
            mc.gui.hud.chat.addClientSystemMessage(Component.literal("§c[Profile Viewer] No local player. Use /pv <name>."))
            return 0
        }
        diagnostics.event("OPEN self=${player == null}")
        mc.execute { mc.gui.setScreen(ProfileViewerScreen(mc.gui.screen(), name)) }
        return 1
    }

    fun onLookup() {
        lookups++
        diagnostics.event("LOOKUP n=$lookups")
    }

    fun onResult(ok: Boolean, profiles: Int, error: String?, millis: Long) {
        if (!ok) failures++
        diagnostics.event("RESULT ok=$ok profiles=$profiles ms=$millis error=${error ?: "-"}")
    }

    /** A lookup served from the 30 s cache. */
    fun onCached(profiles: Int) {
        diagnostics.event("CACHE_HIT profiles=$profiles")
    }

    /** An item whose vanilla tooltip threw; shown with name and lore instead (bounded by the diagnostics rate limit). */
    fun onTooltipFallback(error: String) {
        diagnostics.event("TOOLTIP_FALLBACK error=$error")
    }

    /** SkyBlockPV backend sign-in and Garden or Museum results; no names, ids or tokens. */
    /** Networth counted for the viewed profile: total, per category and whether the museum was in it. */
    fun onNetworth(total: Double, categories: Map<String, Long>, museum: Boolean, millis: Long) {
        diagnostics.event("NETWORTH total=${total.toLong()} museum=$museum ms=$millis " + categories.entries.joinToString(" ") { "${it.key.replace(' ', '_')}=${it.value}" })
    }

    fun onBackend(text: String) {
        diagnostics.event(text)
    }

    /** Off-thread item build result: stacks built and how many fell back from the legacy fixer. */
    fun onItems(container: String, items: Int, fallbacks: Int) {
        Minecraft.getInstance().execute { diagnostics.event("ITEMS container=$container items=$items fallbacks=$fallbacks") }
    }

    companion object {
        @JvmField
        val INSTANCE = ProfileViewerModule(BooleanSetting("Garden and Museum", true))
    }
}
