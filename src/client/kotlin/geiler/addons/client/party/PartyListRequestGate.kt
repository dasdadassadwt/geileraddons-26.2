package geiler.addons.client.party

/** Limits `/p list` to one bootstrap plus one delayed recovery per party session. */
class PartyListRequestGate {
    private var generation = Long.MIN_VALUE
    private var lastRequestTick = Long.MIN_VALUE
    private var requestCount = 0

    fun shouldRequest(partyGeneration: Long, currentTick: Long, stableListKnown: Boolean): Boolean {
        syncGeneration(partyGeneration)
        if (stableListKnown || requestCount >= MAX_REQUESTS_PER_SESSION) return false
        if (lastRequestTick != Long.MIN_VALUE && currentTick - lastRequestTick < RECOVERY_COOLDOWN_TICKS) return false
        lastRequestTick = currentTick
        requestCount++
        return true
    }

    fun requestCount(partyGeneration: Long): Int {
        syncGeneration(partyGeneration)
        return requestCount
    }

    private fun syncGeneration(partyGeneration: Long) {
        if (generation == partyGeneration) return
        generation = partyGeneration
        lastRequestTick = Long.MIN_VALUE
        requestCount = 0
    }

    companion object {
        const val RECOVERY_COOLDOWN_TICKS: Long = 20L * 5L

        const val MAX_REQUESTS_PER_SESSION: Int = 2
    }
}
