package geiler.addons.client.dungeon.odin

/**
 * Server-tick clock for run splits. Negative-ID ping packets are the fallback signal; once a world-time
 * packet arrives, elapsed ticks follow the server's own game-time delta (extrapolated by client ticks
 * between the once-per-second packets) because the ping stream overcounts (~23 per second in live logs).
 */
class DungeonServerTickCounter {
    private var pingTicks = 0L
    private var anchorGameTime = -1L
    private var baseCount = 0L
    private var baseGameTime = 0L
    private var clientTicksSinceAnchor = 0L
    private var highWater = 0L

    /** Returns true when a negative packet ID was accepted, including after saturation. */
    fun observePingId(packetId: Long): Boolean {
        if (!Companion.isServerTickPacket(packetId)) return false
        if (pingTicks < Long.MAX_VALUE) pingTicks++
        return true
    }

    /** Anchors on the packet's server game time; the first packet keeps the running count continuous. */
    fun observeGameTime(gameTime: Long) {
        if (anchorGameTime < 0L) {
            baseCount = pingTicks
            baseGameTime = gameTime
        } else if (gameTime < anchorGameTime || gameTime - anchorGameTime > MAX_PACKET_GAP_TICKS) {
            // World-time went backwards or leapt (world swap into a world with another clock): rebase so the count
            // stays monotonic and a swap cannot add millions of ticks to every timer.
            baseCount = count()
            baseGameTime = gameTime
        }
        anchorGameTime = gameTime
        clientTicksSinceAnchor = 0L
    }

    /** Extrapolates between world-time packets, capped at one packet interval. */
    fun onClientTick() {
        if (anchorGameTime >= 0L && clientTicksSinceAnchor < MAX_EXTRAPOLATION_TICKS) clientTicksSinceAnchor++
    }

    fun count(): Long {
        val value = if (anchorGameTime < 0L) pingTicks
        else baseCount + (anchorGameTime - baseGameTime) + clientTicksSinceAnchor
        if (value > highWater) highWater = value
        return highWater
    }

    fun reset() {
        pingTicks = 0L
        anchorGameTime = -1L
        baseCount = 0L
        baseGameTime = 0L
        clientTicksSinceAnchor = 0L
        highWater = 0L
    }

    companion object {
        private const val MAX_EXTRAPOLATION_TICKS = 20L
        private const val MAX_PACKET_GAP_TICKS = 6_000L

        @JvmStatic
        fun isServerTickPacket(packetId: Long): Boolean = packetId < 0L
    }
}
