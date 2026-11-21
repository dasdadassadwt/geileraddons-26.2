package geiler.addons.client.dungeon.helpers

import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Optional
import geiler.addons.client.chat.ChatStringSemantics

/** Correlates a flexible party-chat mention with an observed teammate teleport near the local player. */
class SpiritLeapIncomingTracker {
    @JvmRecord
    data class Position(val playerName: String?, val x: Double, val y: Double, val z: Double) {
        override fun toString() = "Position[playerName=$playerName, x=$x, y=$y, z=$z]"
    }

    private data class PendingMention(val incoming: SpiritLeapIncomingRules.Incoming, val observedAtNanos: Long)

    private val mentions = HashMap<String, PendingMention>()
    private val recentArrivals = HashMap<String, Long>()
    private var previousPartyPositions: Map<String, Position> = emptyMap()
    private var previousLocalPosition: Position? = null

    fun clear() {
        mentions.clear()
        recentArrivals.clear()
        clearPositions()
    }

    private fun clearPositions() {
        previousPartyPositions = emptyMap()
        previousLocalPosition = null
    }

    private fun expire(nowNanos: Long) {
        mentions.entries.removeIf { !withinWindow(nowNanos, it.value.observedAtNanos) }
        recentArrivals.entries.removeIf { !withinWindow(nowNanos, it.value) }
    }

    private fun isArrival(previousLocal: Position, previousPlayer: Position?, currentLocal: Position, currentPlayer: Position): Boolean =
        valid(previousPlayer) && distanceSquared(previousPlayer!!, previousLocal) > MIN_START_DISTANCE_SQUARED &&
            distanceSquared(currentPlayer, currentLocal) <= MAX_ARRIVAL_DISTANCE_SQUARED &&
            distanceSquared(previousPlayer, currentPlayer) >= MIN_PLAYER_MOVEMENT_SQUARED

    private fun distanceSquared(first: Position, second: Position): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        val dz = first.z - second.z
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        const val CORRELATION_WINDOW_NANOS = 4_000_000_000L
        private const val MIN_START_DISTANCE_SQUARED = 100.0
        private const val MAX_ARRIVAL_DISTANCE_SQUARED = 36.0
        private const val MIN_PLAYER_MOVEMENT_SQUARED = 64.0
        private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME

        private fun valid(incoming: SpiritLeapIncomingRules.Incoming?): Boolean =
            incoming != null && validName(incoming.teammateName) && validName(incoming.targetName)

        private fun valid(position: Position?): Boolean = position != null && validName(position.playerName) &&
            java.lang.Double.isFinite(position.x) && java.lang.Double.isFinite(position.y) &&
            java.lang.Double.isFinite(position.z)

        private fun validName(name: String?): Boolean = name != null && USERNAME.matcher(name).matches()

        private fun withinWindow(nowNanos: Long, eventNanos: Long) =
            nowNanos >= eventNanos && nowNanos - eventNanos <= CORRELATION_WINDOW_NANOS

        private fun normalize(value: String?) = value?.lowercase(Locale.ROOT).orEmpty()

    }
}
