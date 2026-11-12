package geiler.addons.client.party

import geiler.addons.client.dungeon.DungeonStats
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Tracks one automatic stats attempt per player for the lifetime of a party session. */
class PartyLookupAttempts {
    companion object {
        const val MANUAL_RETRY_COOLDOWN_TICKS: Long = 20L * 30L
    }

    private val core = PartyLookupAttemptsCore()

    fun beginAutomatic(partyGeneration: Long, member: PartyMember?): BeginResult =
        core.beginAutomatic(partyGeneration, member)

    fun beginManualRetry(partyGeneration: Long, member: PartyMember?, currentTick: Long): RetryResult =
        core.beginManualRetry(partyGeneration, member, currentTick)

    /** Completes only the still-current attempt; stale callbacks cannot replace a newer identity. */
    fun complete(
        handle: RequestHandle?,
        currentPartyGeneration: Long,
        stats: DungeonStats?,
        error: String?,
    ): Boolean = core.complete(handle, currentPartyGeneration, stats, error)

    fun get(partyGeneration: Long, member: PartyMember?): AttemptView? =
        core.get(partyGeneration, member)

    /** Clears state for a departed membership so a later rejoin is treated as a new member. */
    fun forgetMember(partyGeneration: Long, playerName: String?): Boolean =
        core.forgetMember(partyGeneration, playerName)

    /** Ensures one unavailable card per failed attempt, including failures hidden by a list refresh. */
    fun markUnavailableReported(partyGeneration: Long, member: PartyMember?): Boolean =
        core.markUnavailableReported(partyGeneration, member)

    fun reset(partyGeneration: Long) = core.reset(partyGeneration)

    enum class State {
        PENDING,
        SUCCESS,
        UNAVAILABLE,
    }

    enum class RetryBlockReason {
        NOT_TRACKED,
        PENDING,
        NOT_RETRYABLE,
        COOLDOWN,
    }

    @JvmRecord
    data class RequestHandle(val partyGeneration: Long, val key: String?, val token: Long) {
        override fun toString(): String =
            "RequestHandle[partyGeneration=$partyGeneration, key=$key, token=$token]"
    }

    @JvmRecord
    data class BeginResult(val handle: RequestHandle?, val attempt: AttemptView?) {
        fun started(): Boolean = handle != null

        override fun toString(): String = "BeginResult[handle=$handle, attempt=$attempt]"
    }

    @JvmRecord
    data class RetryResult(
        val handle: RequestHandle?,
        val attempt: AttemptView?,
        val blockReason: RetryBlockReason?,
        val cooldownTicksRemaining: Long,
    ) {
        fun started(): Boolean = handle != null

        override fun toString(): String =
            "RetryResult[handle=$handle, attempt=$attempt, blockReason=$blockReason, " +
                "cooldownTicksRemaining=$cooldownTicksRemaining]"
    }

    @JvmRecord
    data class AttemptView(
        val name: String?,
        val uuid: UUID?,
        val state: State?,
        val stats: DungeonStats?,
        val error: String?,
        val retryTicksRemaining: Long,
    ) {
        override fun toString(): String =
            "AttemptView[name=$name, uuid=$uuid, state=$state, stats=$stats, error=$error, " +
                "retryTicksRemaining=$retryTicksRemaining]"
    }
}
