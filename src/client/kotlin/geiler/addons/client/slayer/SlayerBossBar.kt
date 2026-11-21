package geiler.addons.client.slayer

import geiler.addons.client.mixin.BossHealthOverlayAccessor
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.LerpingBossEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.BossEvent

/**
 * The Slayer boss's health as a vanilla boss bar in the top-center stack, client side only: one [LerpingBossEvent]
 * with a fixed id lives in the overlay's map while a fight runs. No music, screen darkening or fog.
 */
object SlayerBossBar {
    private val ID = UUID.fromString("6f0a4d2e-6a53-4c1b-9d0e-5e1a7e0b53a1")
    private var event: LerpingBossEvent? = null

    /** Whether the bar is currently in the overlay. */
    fun shown(): Boolean = event != null

    /** Adds the bar when absent, then sets its text, fill, color and notches. Returns true on the call that added it. */
    fun show(title: Component, progress: Float, color: BossEvent.BossBarColor, notches: SlayerBossPhases.Notches): Boolean {
        val map = events() ?: return false
        var added = false
        var bar = event
        if (bar == null || map[ID] !== bar) {
            bar = LerpingBossEvent(ID, title, progress, color, overlay(notches), false, false, false)
            map[ID] = bar
            event = bar
            added = true
        }
        bar.name = title
        bar.progress = progress
        bar.color = color
        bar.overlay = overlay(notches)
        return added
    }

    /** Removes the bar; returns true when it was shown. */
    fun hide(): Boolean {
        val bar = event ?: return false
        event = null
        val map = events() ?: return true
        if (map[ID] === bar) map.remove(ID)
        return true
    }

    private fun events(): MutableMap<UUID, LerpingBossEvent>? =
        (Minecraft.getInstance().gui.hud.bossOverlay as? BossHealthOverlayAccessor)?.`geileraddons$events`()

    private fun overlay(notches: SlayerBossPhases.Notches): BossEvent.BossBarOverlay = when (notches) {
        SlayerBossPhases.Notches.NONE -> BossEvent.BossBarOverlay.PROGRESS
        SlayerBossPhases.Notches.SIX -> BossEvent.BossBarOverlay.NOTCHED_6
        SlayerBossPhases.Notches.TWENTY -> BossEvent.BossBarOverlay.NOTCHED_20
    }
}
