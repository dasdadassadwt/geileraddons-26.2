package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.Locale
import java.util.UUID

/** Player-defined Guide stage and the signal that activates it. */
class DungeonGuideCustomSegment {
    enum class Trigger { MANUAL, CHAT_EVENT, ENTER_RADIUS, AFTER_SECONDS }

    @JvmField var id: String = "CUSTOM_${UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.ROOT)}"
    @JvmField var floor: String = "F1"
    @JvmField var label: String = "Custom stage"
    @JvmField var trigger: Trigger? = Trigger.MANUAL
    @JvmField var eventText: String = ""
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var z: Int = 0
    @JvmField var radius: Float = 2.0f
    @JvmField var seconds: Float = 0.0f
    @JvmField var afterBuiltInIndex: Int = 1

    constructor()

    fun sanitize() {
        // Gson can assign null to this public field for malformed legacy data, as it could
        // for the former Java field. Keep that recovery path despite the non-null default.
        if (java.util.Objects.isNull(id) || !Regex("CUSTOM_[A-Z0-9_]{1,25}").matches(id)) {
            id = "CUSTOM_${UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.ROOT)}"
        }
        floor = DungeonFloor.parse(floor)?.displayName() ?: "F1"
        label = clean(label, 48)
        if (ChatStringSemantics.isBlank(label)) label = "Custom stage"
        if (trigger == null) trigger = Trigger.MANUAL
        eventText = clean(eventText, 128)
        radius = if (radius.isFinite()) radius.coerceIn(0.5f, 64.0f) else 2.0f
        seconds = if (seconds.isFinite()) seconds.coerceIn(0.0f, 3600.0f) else 0.0f
        val maxIndex = DungeonGuideSegments.builtIn(DungeonFloor.parse(floor)).size - 1
        afterBuiltInIndex = afterBuiltInIndex.coerceIn(0, maxIndex)
    }

    private fun clean(value: String?, limit: Int): String {
        if (value == null) return ""
        val cleaned = ChatStringSemantics.trim(value.replace(Regex("[\\p{Cntrl}]"), ""))
        return if (cleaned.length <= limit) cleaned else cleaned.substring(0, limit)
    }
}
