package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Exact quest lifecycle messages used to recover Slayer state when scoreboard updates are late. */
class SlayerChatParser private constructor() {
    enum class Event { NONE, QUEST_STARTED, QUEST_COMPLETE, QUEST_FAILED }

    companion object {
        @JvmStatic
        fun parse(rawMessage: String): Event {
            val message = ChatText.stripForMatch(rawMessage).lowercase(Locale.ROOT).trim { it <= ' ' }
            if (message == "slayer quest started!") return Event.QUEST_STARTED
            if (message == "slayer quest complete!") return Event.QUEST_COMPLETE
            if (message == "slayer quest failed!") return Event.QUEST_FAILED
            return Event.NONE
        }
    }
}
