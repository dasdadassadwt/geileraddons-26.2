package geiler.addons.client.party

import geiler.addons.client.dungeon.DungeonStats
import java.util.HashMap
import java.util.Locale
import java.util.UUID

/** Stores lookup attempts and retry state for the Party Finder Stats module. */
class PartyLookupAttemptsCore {
    private var generation = Long.MIN_VALUE
    private var nextToken = 0L
    private val entries = HashMap<String, Entry>()

    fun beginAutomatic(
        partyGeneration: Long,
        member: PartyMember?,
    ): PartyLookupAttempts.BeginResult {
        val currentMember = member ?: return PartyLookupAttempts.BeginResult(null, null)
        val name = currentMember.name() ?: return PartyLookupAttempts.BeginResult(null, null)
        if (isJavaBlank(name)) {
            return PartyLookupAttempts.BeginResult(null, null)
        }
        syncGeneration(partyGeneration)
        val key = key(name)
        val existing = entries[key]
        if (existing != null && compatible(existing.uuid, currentMember.uuid())) {
            if (existing.uuid == null && currentMember.uuid() != null) existing.uuid = currentMember.uuid()
            return PartyLookupAttempts.BeginResult(null, view(existing, 0L))
        }
        val entry = Entry(name, currentMember.uuid(), ++nextToken)
        entries[key] = entry
        return PartyLookupAttempts.BeginResult(
            PartyLookupAttempts.RequestHandle(partyGeneration, key, entry.token),
            view(entry, 0L),
        )
    }

    fun beginManualRetry(
        partyGeneration: Long,
        member: PartyMember?,
        currentTick: Long,
    ): PartyLookupAttempts.RetryResult {
        val currentMember = member ?: return PartyLookupAttempts.RetryResult(
            null,
            null,
            PartyLookupAttempts.RetryBlockReason.NOT_TRACKED,
            0L,
        )
        val name = currentMember.name()
        if (isJavaBlank(name)) {
            return PartyLookupAttempts.RetryResult(
                null,
                null,
                PartyLookupAttempts.RetryBlockReason.NOT_TRACKED,
                0L,
            )
        }
        syncGeneration(partyGeneration)
        val key = key(name!!)
        val entry = entries[key]
        if (entry == null || !compatible(entry.uuid, currentMember.uuid())) {
            return PartyLookupAttempts.RetryResult(
                null,
                null,
                PartyLookupAttempts.RetryBlockReason.NOT_TRACKED,
                0L,
            )
        }
        if (entry.state == PartyLookupAttempts.State.PENDING) {
            return PartyLookupAttempts.RetryResult(
                null,
                view(entry, 0L),
                PartyLookupAttempts.RetryBlockReason.PENDING,
                0L,
            )
        }
        if (entry.state == PartyLookupAttempts.State.SUCCESS && (entry.stats == null || entry.stats!!.cacheable())) {
            return PartyLookupAttempts.RetryResult(
                null,
                view(entry, 0L),
                PartyLookupAttempts.RetryBlockReason.NOT_RETRYABLE,
                0L,
            )
        }
        if (entry.lastManualRetryTick != Long.MIN_VALUE) {
            val elapsed = maxOf(0L, currentTick - entry.lastManualRetryTick)
            val remaining = PartyLookupAttempts.MANUAL_RETRY_COOLDOWN_TICKS - elapsed
            if (remaining > 0L) {
                return PartyLookupAttempts.RetryResult(
                    null,
                    view(entry, remaining),
                    PartyLookupAttempts.RetryBlockReason.COOLDOWN,
                    remaining,
                )
            }
        }
        entry.state = PartyLookupAttempts.State.PENDING
        entry.stats = null
        entry.error = null
        entry.unavailableReported = false
        entry.lastManualRetryTick = currentTick
        entry.token = ++nextToken
        return PartyLookupAttempts.RetryResult(
            PartyLookupAttempts.RequestHandle(partyGeneration, key, entry.token),
            view(entry, 0L),
            null,
            0L,
        )
    }

    /** Completes only the still-current attempt; stale callbacks cannot replace a newer identity. */
    fun complete(
        handle: PartyLookupAttempts.RequestHandle?,
        currentPartyGeneration: Long,
        stats: DungeonStats?,
        error: String?,
    ): Boolean {
        if (handle == null) return false
        syncGeneration(currentPartyGeneration)
        if (handle.partyGeneration != generation) return false
        val key = handle.key ?: return false
        val entry = entries[key]
        if (entry == null || entry.token != handle.token || entry.state != PartyLookupAttempts.State.PENDING) {
            return false
        }
        if (stats != null) {
            entry.state = PartyLookupAttempts.State.SUCCESS
            entry.stats = stats
            if (stats.uuid() != null) entry.uuid = stats.uuid()
            entry.error = null
        } else {
            entry.state = PartyLookupAttempts.State.UNAVAILABLE
            entry.stats = null
            entry.error = if (isJavaBlank(error)) "unavailable" else error
            entry.unavailableReported = false
        }
        return true
    }

    fun get(partyGeneration: Long, member: PartyMember?): PartyLookupAttempts.AttemptView? {
        val currentMember = member ?: return null
        val name = currentMember.name() ?: return null
        syncGeneration(partyGeneration)
        val entry = entries[key(name)]
        if (entry == null || !compatible(entry.uuid, currentMember.uuid())) return null
        if (entry.uuid == null && currentMember.uuid() != null) entry.uuid = currentMember.uuid()
        return view(entry, 0L)
    }

    /** Clears state for a departed membership so a later rejoin is treated as a new member. */
    fun forgetMember(partyGeneration: Long, playerName: String?): Boolean {
        if (isJavaBlank(playerName)) return false
        syncGeneration(partyGeneration)
        return entries.remove(key(playerName!!)) != null
    }

    /** Ensures one unavailable card per failed attempt, including failures hidden by a list refresh. */
    fun markUnavailableReported(partyGeneration: Long, member: PartyMember?): Boolean {
        val currentMember = member ?: return false
        val name = currentMember.name() ?: return false
        syncGeneration(partyGeneration)
        val entry = entries[key(name)]
        if (entry == null || entry.state != PartyLookupAttempts.State.UNAVAILABLE ||
            !compatible(entry.uuid, currentMember.uuid()) || entry.unavailableReported
        ) return false
        entry.unavailableReported = true
        return true
    }

    fun reset(partyGeneration: Long) {
        generation = partyGeneration
        nextToken = 0L
        entries.clear()
    }

    private fun syncGeneration(partyGeneration: Long) {
        if (generation != partyGeneration) reset(partyGeneration)
    }

    private fun view(entry: Entry, retryTicks: Long): PartyLookupAttempts.AttemptView =
        PartyLookupAttempts.AttemptView(
            entry.name,
            entry.uuid,
            entry.state,
            entry.stats,
            entry.error,
            retryTicks,
        )

    private fun compatible(stored: UUID?, current: UUID?): Boolean =
        stored == null || current == null || stored == current

    private fun key(name: String): String = name.trim { it <= '\u0020' }.lowercase(Locale.ROOT)

    private fun isJavaBlank(value: String?): Boolean =
        value == null || value.codePoints().allMatch { Character.isWhitespace(it) }

    private class Entry(
        val name: String,
        var uuid: UUID?,
        var token: Long,
        var state: PartyLookupAttempts.State = PartyLookupAttempts.State.PENDING,
        var stats: DungeonStats? = null,
        var error: String? = null,
        var lastManualRetryTick: Long = Long.MIN_VALUE,
        var unavailableReported: Boolean = false,
    )
}
