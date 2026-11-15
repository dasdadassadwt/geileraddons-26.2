package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import java.util.Locale
import java.util.WeakHashMap
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.BossEvent

/**
 * Shows the boss health as a percentage after the name in dungeon boss fights, e.g. `Maxor - 25.0%`
 * (Devonian's Boss Bar Health, GPL-3.0: behavior only, written independently).
 */
class BossBarPercentModule private constructor() : Module(
    "Boss Bar Percent",
    "Shows the boss health percentage next to the boss bar name in dungeon boss fights.",
    Category.F7,
    false,
) {
    /** What we last wrote to a bar, so the original name is restored and a server rename is never overwritten. */
    private class Entry(val original: Component, val applied: Component, val text: String)

    private val entries = WeakHashMap<BossEvent, Entry>()
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 200)
    private var updates = 0L
    private var lastText = "none"

    override fun onEnable() {
        updates = 0L
        lastText = "none"
        diagnostics.enable("scope=dungeon_boss")
    }

    override fun onDisable() {
        restoreAll()
        diagnostics.event("COUNTS updates=$updates lastPercent=$lastText")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "inDungeon=${DungeonContextTracker.inDungeon()} bossPhase=${!DungeonContextTracker.isMobEspPhase()} " +
                "bars=${entries.size} updates=$updates lastPercent=$lastText suppressedEvents=$it"
        }
    }

    /** `BossHealthOverlay.extractBar` HEAD, once per bar per frame; allocates only when the shown percent changes. */
    fun apply(event: BossEvent?) {
        if (event == null) return
        val entry = entries[event]
        val current = event.name
        val ours = entry != null && entry.applied === current
        val active = shouldApply(isEnabled(), DungeonContextTracker.inDungeon(), !DungeonContextTracker.isMobEspPhase())
        if (!active) {
            if (ours) {
                event.setName(entry!!.original)
                entries.remove(event)
            } else if (entry != null) {
                entries.remove(event)
            }
            return
        }
        val text = format(event.progress)
        if (ours && entry!!.text == text) return
        val original = if (ours) entry!!.original else current
        val applied = withPercent(original, text)
        event.setName(applied)
        entries[event] = Entry(original, applied, text)
        if (updates < Long.MAX_VALUE) updates++
        if (text != lastText) {
            lastText = text
            diagnostics.event("PERCENT bar=${original.string.take(40)} percent=$text")
        }
    }

    private fun restoreAll() {
        for ((event, entry) in entries.entries.toList()) {
            if (event.name === entry.applied) event.setName(entry.original)
        }
        entries.clear()
    }

    companion object {
        @JvmField
        val INSTANCE = BossBarPercentModule()

        @JvmStatic
        fun shouldApply(enabled: Boolean, inDungeon: Boolean, pastClearPhase: Boolean): Boolean =
            enabled && inDungeon && pastClearPhase

        /** Progress 0..1 as `25.0%`; out-of-range values are clamped. */
        @JvmStatic
        fun format(progress: Float): String =
            String.format(Locale.ROOT, "%.1f%%", progress.coerceIn(0f, 1f) * 100f)

        /** A copy of [name] with a gray dash and a light-purple percent appended; [name] is not modified. */
        @JvmStatic
        fun withPercent(name: Component, percent: String): Component =
            name.copy()
                .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(percent).withStyle(ChatFormatting.LIGHT_PURPLE))
    }
}
