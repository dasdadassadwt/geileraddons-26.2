package geiler.addons.client.farming

import kotlin.jvm.JvmRecord

/** Slayer quest-sidebar lifecycle state machine; Java-facing Update record and idle-snapshot constant. */
class SlayerQuestSidebarLifecycle {
    private var consecutiveIdleSnapshots = 0
    private var questActive = false
    private var completionReported = false

    fun observe(state: SlayerScoreboardParser.State?): SlayerQuestSidebarLifecycle.Update {
        when (state ?: SlayerScoreboardParser.State.INCOMPLETE) {
            SlayerScoreboardParser.State.QUEST_ACTIVE,
            SlayerScoreboardParser.State.BOSS_ACTIVE -> {
                if (!completionReported) questActive = true
                consecutiveIdleSnapshots = 0
            }

            SlayerScoreboardParser.State.BOSS_SLAIN -> {
                val firstCompletion = !completionReported
                completionReported = true
                questActive = false
                consecutiveIdleSnapshots = 0
                return SlayerQuestSidebarLifecycle.Update(false, firstCompletion, false)
            }

            SlayerScoreboardParser.State.NO_QUEST -> {
                consecutiveIdleSnapshots = Math.min(
                    SlayerQuestSidebarLifecycle.STABLE_IDLE_SNAPSHOTS,
                    consecutiveIdleSnapshots + 1,
                )
                if (consecutiveIdleSnapshots >= SlayerQuestSidebarLifecycle.STABLE_IDLE_SNAPSHOTS) {
                    val failed = questActive
                    questActive = false
                    return SlayerQuestSidebarLifecycle.Update(false, false, failed)
                }
            }

            SlayerScoreboardParser.State.INCOMPLETE -> consecutiveIdleSnapshots = 0
        }
        return SlayerQuestSidebarLifecycle.Update(questActive, false, false)
    }

    fun onQuestStarted(): SlayerQuestSidebarLifecycle.Update {
        completionReported = false
        questActive = true
        consecutiveIdleSnapshots = 0
        return SlayerQuestSidebarLifecycle.Update(true, false, false)
    }

    fun onQuestCompleted(): SlayerQuestSidebarLifecycle.Update {
        val firstCompletion = questActive && !completionReported
        completionReported = completionReported || firstCompletion
        questActive = false
        consecutiveIdleSnapshots = 0
        return SlayerQuestSidebarLifecycle.Update(false, firstCompletion, false)
    }

    fun onQuestFailed(): SlayerQuestSidebarLifecycle.Update {
        val failed = questActive
        completionReported = false
        questActive = false
        consecutiveIdleSnapshots = 0
        return SlayerQuestSidebarLifecycle.Update(false, false, failed)
    }

    fun reset() {
        consecutiveIdleSnapshots = 0
        questActive = false
        completionReported = false
    }

    fun consecutiveIdleSnapshots(): Int = consecutiveIdleSnapshots

    fun questActive(): Boolean = questActive

    @JvmRecord
    data class Update(val questActive: Boolean, val bossSlain: Boolean, val questFailed: Boolean) {
        override fun toString(): String =
            "Update[questActive=$questActive, bossSlain=$bossSlain, questFailed=$questFailed]"
    }

    companion object {
        const val STABLE_IDLE_SNAPSHOTS: Int = 2
    }
}
