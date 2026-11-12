package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.jvm.JvmStatic

/** Routes each received chat line once to phase feedback and the optional route guide. */
class DungeonGuideChatDispatch private constructor() {
    companion object {
        @JvmStatic
        fun dispatch(
            floor: DungeonFloor?,
            queuedFloor: DungeonFloor?,
            message: String?,
            phaseOutput: BiConsumer<DungeonFloor, DungeonGuideSegments.Event>?,
            guide: Consumer<String>?,
        ) {
            if (message == null || ChatStringSemantics.isBlank(message)) return
            if (phaseOutput != null) {
                if (floor != null) {
                    val event = DungeonGuideSegments.fromChat(floor, message)
                    if (event != null) phaseOutput.accept(floor, event)
                } else if (queuedFloor != null) {
                    // Before the dungeon context is active, only a countdown is trustworthy enough
                    // to use the previously confirmed queue floor.
                    val event = DungeonGuideSegments.fromChat(queuedFloor, message)
                    if (event != null && event.segmentId == "ENTRY") phaseOutput.accept(queuedFloor, event)
                }
            }
            if (guide != null) guide.accept(message)
        }
    }
}
