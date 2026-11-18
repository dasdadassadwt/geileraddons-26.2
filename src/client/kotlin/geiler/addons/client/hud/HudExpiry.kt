package geiler.addons.client.hud

import geiler.addons.client.location.Island

/**
 * When the live HUD hides an element that is not in use: off its island, or idle (its [HudElement.activitySignature]
 * has not changed for [IDLE_NANOS]). Pure rules; [HudManager] keeps the per-element clocks.
 */
object HudExpiry {
    const val IDLE_NANOS = 30_000_000_000L

    /** True when [islands] is empty, the island is unknown (detection off or no answer yet), or it is one of them. */
    @JvmStatic
    fun onIsland(islands: Set<Island>, current: Island?): Boolean =
        islands.isEmpty() || current == null || !current.selectable() && current != Island.OTHER || current in islands

    /** True once a signature-bearing element has gone [IDLE_NANOS] without a change. */
    @JvmStatic
    fun idle(hasSignature: Boolean, lastChangeNanos: Long, nowNanos: Long): Boolean =
        hasSignature && nowNanos - lastChangeNanos > IDLE_NANOS

    /** Per-element idle clocks: restarted whenever the signature changes or the element was not showing. */
    class Clock {
        private val signatures = HashMap<String, Any?>()
        private val changed = HashMap<String, Long>()

        /** Call once per frame for each element; returns true when it should be hidden as idle. */
        fun idle(id: String, showing: Boolean, signature: Any?, nowNanos: Long): Boolean {
            if (!showing || signature == null) {
                signatures.remove(id)
                changed.remove(id)
                return false
            }
            if (!signatures.containsKey(id) || signatures[id] != signature) {
                signatures[id] = signature
                changed[id] = nowNanos
            }
            return idle(true, changed.getValue(id), nowNanos)
        }
    }
}
