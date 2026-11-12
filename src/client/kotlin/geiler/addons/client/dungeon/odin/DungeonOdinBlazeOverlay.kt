package geiler.addons.client.dungeon.odin

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext

/** Loaded-only target boxes and short kill-order links for an explicitly selected Blaze puzzle. */
class DungeonOdinBlazeOverlay {
    private val logic = DungeonOdinBlazeOverlayLogic()

    /** Call only on the client render thread after the module has selected the Blaze solver. */
    fun render(context: LevelRenderContext?, settings: Settings?): Frame = logic.render(context, settings)

    fun reset() = logic.reset()

    companion object {
        @JvmStatic
        fun colorFor(settings: Settings?, index: Int): Int = DungeonOdinBlazeOverlayLogic.colorFor(settings, index)

        @JvmStatic
        fun fillFor(argb: Int, style: DungeonPuzzleVisualRenderer.BoxStyle?): Int =
            DungeonOdinBlazeOverlayLogic.fillFor(argb, style)

        @JvmStatic
        fun lastLinkIndex(settings: Settings?, targetCount: Int): Int =
            DungeonOdinBlazeOverlayLogic.lastLinkIndex(settings, targetCount)

        @JvmStatic
        fun isLastTargetTransition(previousCount: Int, currentCount: Int): Boolean =
            DungeonOdinBlazeOverlayLogic.isLastTargetTransition(previousCount, currentCount)
    }

    /** Immutable Java-callable value with the same normalization as the former compact record constructor. */
    class Settings(
        nextLine: Boolean,
        lineCount: Int,
        lineWidth: Float,
        style: DungeonPuzzleVisualRenderer.BoxStyle?,
        firstColor: Int,
        secondColor: Int,
        thirdColor: Int,
        otherColor: Int,
        sendComplete: Boolean,
    ) {
        private val nextLineValue = nextLine
        private val lineCountValue = lineCount.coerceIn(1, 10)
        private val lineWidthValue = if (lineWidth.isFinite()) lineWidth.coerceIn(0.5f, 5.0f) else 2.0f
        private val styleValue = style ?: DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE
        private val firstColorValue = firstColor
        private val secondColorValue = secondColor
        private val thirdColorValue = thirdColor
        private val otherColorValue = otherColor
        private val sendCompleteValue = sendComplete

        fun nextLine(): Boolean = nextLineValue
        fun lineCount(): Int = lineCountValue
        fun lineWidth(): Float = lineWidthValue
        fun style(): DungeonPuzzleVisualRenderer.BoxStyle = styleValue
        fun firstColor(): Int = firstColorValue
        fun secondColor(): Int = secondColorValue
        fun thirdColor(): Int = thirdColorValue
        fun otherColor(): Int = otherColorValue
        fun sendComplete(): Boolean = sendCompleteValue

        fun shouldSendComplete(frame: Frame?): Boolean = DungeonOdinBlazeOverlayLogic.shouldSendComplete(this, frame)

        override fun equals(other: Any?): Boolean = other === this || other is Settings &&
            nextLineValue == other.nextLineValue && lineCountValue == other.lineCountValue &&
            java.lang.Float.compare(lineWidthValue, other.lineWidthValue) == 0 && styleValue == other.styleValue &&
            firstColorValue == other.firstColorValue && secondColorValue == other.secondColorValue &&
            thirdColorValue == other.thirdColorValue && otherColorValue == other.otherColorValue &&
            sendCompleteValue == other.sendCompleteValue

        override fun hashCode(): Int {
            var result = java.lang.Boolean.hashCode(nextLineValue)
            result = 31 * result + java.lang.Integer.hashCode(lineCountValue)
            result = 31 * result + java.lang.Float.hashCode(lineWidthValue)
            result = 31 * result + styleValue.hashCode()
            result = 31 * result + java.lang.Integer.hashCode(firstColorValue)
            result = 31 * result + java.lang.Integer.hashCode(secondColorValue)
            result = 31 * result + java.lang.Integer.hashCode(thirdColorValue)
            result = 31 * result + java.lang.Integer.hashCode(otherColorValue)
            result = 31 * result + java.lang.Boolean.hashCode(sendCompleteValue)
            return result
        }

        override fun toString(): String =
            "Settings[nextLine=$nextLineValue, lineCount=$lineCountValue, lineWidth=$lineWidthValue, " +
                "style=$styleValue, firstColor=$firstColorValue, secondColor=$secondColorValue, " +
                "thirdColor=$thirdColorValue, otherColor=$otherColorValue, sendComplete=$sendCompleteValue]"
    }

    /** Immutable no-frame sentinel retaining the former record's accessors and value semantics. */
    class Frame(roomName: String?, targetCount: Int, completedLastTarget: Boolean) {
        private val roomNameValue = roomName
        private val targetCountValue = targetCount
        private val completedLastTargetValue = completedLastTarget

        fun roomName(): String? = roomNameValue
        fun targetCount(): Int = targetCountValue
        fun completedLastTarget(): Boolean = completedLastTargetValue

        override fun equals(other: Any?): Boolean = other === this || other is Frame &&
            roomNameValue == other.roomNameValue && targetCountValue == other.targetCountValue &&
            completedLastTargetValue == other.completedLastTargetValue

        override fun hashCode(): Int {
            var result = roomNameValue?.hashCode() ?: 0
            result = 31 * result + java.lang.Integer.hashCode(targetCountValue)
            result = 31 * result + java.lang.Boolean.hashCode(completedLastTargetValue)
            return result
        }

        override fun toString(): String =
            "Frame[roomName=$roomNameValue, targetCount=$targetCountValue, " +
                "completedLastTarget=$completedLastTargetValue]"

        companion object {
            @JvmStatic
            fun none(): Frame = Frame(null, -1, false)
        }
    }
}
