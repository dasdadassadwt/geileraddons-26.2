package geiler.addons.client.module.impl

/** Run-end, kick and party-leave rules for Instance Requeue; pure so it can be checked without a client. */
class InstanceRequeueLogic {
    enum class Action { NONE, ANNOUNCE_KICK, SCHEDULE_REQUEUE }

    private var blocked = false

    /** Which leave set the block, for diagnostics: "self", "member" or "none". */
    var blockedBy = "none"
        private set

    /** Classifies one plain chat line. Anyone leaving the party blocks the next requeue; the run end after it consumes the block. */
    fun onChat(content: String, announceKick: Boolean, disableOnLeave: Boolean): Action {
        val line = content.trim()
        return when {
            KICK_LINES.contains(line) -> if (announceKick) Action.ANNOUNCE_KICK else Action.NONE
            isPartyLeave(line) || MEMBER_LEFT.matches(line) -> {
                if (disableOnLeave) {
                    blocked = true
                    blockedBy = if (isPartyLeave(line)) "self" else "member"
                }
                Action.NONE
            }
            line == RUN_END -> if (blocked) {
                blocked = false
                blockedBy = "none"
                Action.NONE
            } else {
                Action.SCHEDULE_REQUEUE
            }
            else -> Action.NONE
        }
    }

    /** Checked again when the delay ends, so leaving the party during the delay cancels the requeue. */
    fun canFire(): Boolean = !blocked

    fun reset() {
        blocked = false
        blockedBy = "none"
    }

    companion object {
        private const val RUN_END = "> EXTRA STATS <"
        private val KICK_LINES = setOf(
            "You are no longer allowed to access this instance!",
            "You were kicked while joining that server!",
            "Exception Connecting:ReadTimeoutException : null",
        )

        private const val PLAYER = """(?:\[[^\]]+] )?[A-Za-z0-9_]{1,16}"""

        /** Fully anchored, so a player typing the same words in a chat channel never matches. */
        private val MEMBER_LEFT = Regex(
            "$PLAYER has (?:left|been removed from) the party\\." +
                "|Kicked $PLAYER because they were offline\\." +
                "|$PLAYER was removed from your party because they disconnected\\.",
        )

        /** A line with ": " is a chat channel message, never the party notice itself. */
        private fun isPartyLeave(line: String): Boolean = !line.contains(": ") && (
            line == "You left the party." || line.startsWith("You have been kicked from the party") ||
                line.contains("The party was disbanded") || line.contains("has disbanded the party")
            )

        @JvmStatic
        fun delayTicks(seconds: Int): Int = seconds.coerceIn(0, 30) * 20
    }
}
