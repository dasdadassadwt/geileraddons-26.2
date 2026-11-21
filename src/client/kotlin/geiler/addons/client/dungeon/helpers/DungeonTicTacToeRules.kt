package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Java-facing values and static entrypoints for the Kotlin Tic Tac Toe solver. */
class DungeonTicTacToeRules private constructor() {
    companion object {
        @JvmField
        val X_MAP_COLOR: Int = 114

        @JvmField
        val O_MAP_COLOR: Int = 33

        @JvmStatic
        fun cellAt(x: Int, y: Int, z: Int): Cell? = DungeonTicTacToeRulesLogic.cellAt(x, y, z)

        @JvmStatic
        fun positionForCell(cell: Cell?): Position? = DungeonTicTacToeRulesLogic.positionForCell(cell)

        @JvmStatic
        fun toWorld(roomPosition: Position?, clayPosition: Position?, rotation: DungeonRoomRotation.Rotation?): Position? =
            DungeonTicTacToeRulesLogic.toWorld(roomPosition, clayPosition, rotation)

        @JvmStatic
        fun toRoom(worldPosition: Position?, clayPosition: Position?, rotation: DungeonRoomRotation.Rotation?): Position? =
            DungeonTicTacToeRulesLogic.toRoom(worldPosition, clayPosition, rotation)

        @JvmStatic
        fun worldPositionForCell(
            cell: Cell?,
            clayPosition: Position?,
            rotation: DungeonRoomRotation.Rotation?,
        ): Position? = DungeonTicTacToeRulesLogic.worldPositionForCell(cell, clayPosition, rotation)

        @JvmStatic
        fun analyze(pieces: List<Piece?>?): Analysis = DungeonTicTacToeRulesLogic.analyze(pieces)

        @JvmStatic
        fun predict(pieces: List<Piece?>?, analysis: Analysis?): Prediction =
            DungeonTicTacToeRulesLogic.predict(pieces, analysis)
    }

    enum class Status { READY, REJECTED, FINISHED, NOT_O_TURN }
    enum class Rejection {
        NONE, UNAVAILABLE, OUT_OF_RANGE, DUPLICATE_SQUARE, UNKNOWN_MARK, ILLEGAL_COUNTS,
        BOTH_WINNERS, ILLEGAL_WINNER_TURN, FINISHED, NOT_O_TURN,
    }
    enum class Turn { X, O, DONE, UNKNOWN }
    enum class PredictionStatus { READY, UNAVAILABLE, AMBIGUOUS_X_REPLY, TERMINAL_AFTER_O, TERMINAL_AFTER_X }

    @JvmRecord
    data class Cell(val row: Int, val column: Int) {
        fun inBounds(): Boolean = row >= 0 && row < 3 && column >= 0 && column < 3

        override fun toString(): String = "Cell[row=$row, column=$column]"
    }

    @JvmRecord
    data class Position(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "Position[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Piece(val x: Int, val y: Int, val z: Int, val mapColor: Int) {
        override fun toString(): String = "Piece[x=$x, y=$y, z=$z, mapColor=$mapColor]"
    }

    /** A value class because its former compact record constructor normalizes all components. */
    class Analysis(
        status: Status?,
        rejection: Rejection?,
        observedPieces: Int,
        xCount: Int,
        oCount: Int,
        turn: Turn?,
        suggestedCell: Cell?,
    ) {
        private val statusValue = status ?: Status.REJECTED
        private val rejectionValue = rejection ?: Rejection.UNAVAILABLE
        private val observedPiecesValue = observedPieces.coerceAtLeast(0)
        private val xCountValue = xCount.coerceAtLeast(0)
        private val oCountValue = oCount.coerceAtLeast(0)
        private val turnValue = turn ?: Turn.UNKNOWN
        private val suggestedCellValue = suggestedCell?.takeIf(Cell::inBounds)

        fun status(): Status = statusValue
        fun rejection(): Rejection = rejectionValue
        fun observedPieces(): Int = observedPiecesValue
        fun xCount(): Int = xCountValue
        fun oCount(): Int = oCountValue
        fun turn(): Turn = turnValue
        fun suggestedCell(): Cell? = suggestedCellValue

        override fun equals(other: Any?): Boolean = other is Analysis && statusValue == other.statusValue &&
            rejectionValue == other.rejectionValue && observedPiecesValue == other.observedPiecesValue &&
            xCountValue == other.xCountValue && oCountValue == other.oCountValue && turnValue == other.turnValue &&
            suggestedCellValue == other.suggestedCellValue

        override fun hashCode(): Int {
            var result = statusValue.hashCode()
            result = 31 * result + rejectionValue.hashCode()
            result = 31 * result + observedPiecesValue
            result = 31 * result + xCountValue
            result = 31 * result + oCountValue
            result = 31 * result + turnValue.hashCode()
            result = 31 * result + (suggestedCellValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Analysis[status=$statusValue, rejection=$rejectionValue, observedPieces=$observedPiecesValue, xCount=$xCountValue, oCount=$oCountValue, turn=$turnValue, suggestedCell=$suggestedCellValue]"
    }

    /** A value class because its former compact record constructor normalizes nullable cells/status. */
    class Prediction(status: PredictionStatus?, xReply: Cell?, nextOMove: Cell?) {
        private val statusValue = status ?: PredictionStatus.UNAVAILABLE
        private val xReplyValue = xReply?.takeIf(Cell::inBounds)
        private val nextOMoveValue = nextOMove?.takeIf(Cell::inBounds)

        fun status(): PredictionStatus = statusValue
        fun xReply(): Cell? = xReplyValue
        fun nextOMove(): Cell? = nextOMoveValue

        override fun equals(other: Any?): Boolean = other is Prediction && statusValue == other.statusValue &&
            xReplyValue == other.xReplyValue && nextOMoveValue == other.nextOMoveValue

        override fun hashCode(): Int {
            var result = statusValue.hashCode()
            result = 31 * result + (xReplyValue?.hashCode() ?: 0)
            result = 31 * result + (nextOMoveValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Prediction[status=$statusValue, xReply=$xReplyValue, nextOMove=$nextOMoveValue]"
    }
}
