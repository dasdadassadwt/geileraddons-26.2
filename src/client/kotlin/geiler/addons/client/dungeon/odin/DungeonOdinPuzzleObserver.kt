package geiler.addons.client.dungeon.odin

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Java-facing value and method boundary for the Kotlin puzzle observer core. */
class DungeonOdinPuzzleObserver {
    private val core = DungeonOdinPuzzleObserverCore()

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?): Guidance? =
        core.observe(client, clientTick, selection, blazeRoomPosition)

    fun observe(
        client: Minecraft?,
        clientTick: Long,
        selection: String?,
        blazeRoomPosition: String?,
        waterBoardStrategy: String?,
    ): Guidance? = core.observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy)

    fun observe(
        client: Minecraft?,
        clientTick: Long,
        selection: String?,
        blazeRoomPosition: String?,
        waterBoardStrategy: String?,
        iceFillStrategy: IceFillStrategy?,
    ): Guidance? = core.observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy, iceFillStrategy)

    fun observe(
        client: Minecraft?,
        clientTick: Long,
        selection: String?,
        blazeRoomPosition: String?,
        waterBoardStrategy: String?,
        iceFillStrategy: IceFillStrategy?,
        creeperBeamGuidanceEnabled: Boolean,
    ): Guidance? = core.observe(
        client,
        clientTick,
        selection,
        blazeRoomPosition,
        waterBoardStrategy,
        iceFillStrategy,
        creeperBeamGuidanceEnabled,
    )

    fun observe(
        client: Minecraft?,
        clientTick: Long,
        selection: String?,
        blazeRoomPosition: String?,
        waterBoardStrategy: String?,
        iceFillStrategy: IceFillStrategy?,
        creeperBeamGuidanceEnabled: Boolean,
        enabledSolvers: Set<String>?,
    ): Guidance? = core.observe(
        client,
        clientTick,
        selection,
        blazeRoomPosition,
        waterBoardStrategy,
        iceFillStrategy,
        creeperBeamGuidanceEnabled,
        enabledSolvers,
    )

    fun observe(
        client: Minecraft?,
        clientTick: Long,
        selection: String?,
        blazeRoomPosition: String?,
        waterBoardStrategy: String?,
        iceFillStrategy: IceFillStrategy?,
        creeperBeamGuidanceEnabled: Boolean,
        enabledSolvers: Set<String>?,
        ticTacToePredictionEnabled: Boolean,
    ): Guidance? = core.observe(
        client,
        clientTick,
        selection,
        blazeRoomPosition,
        waterBoardStrategy,
        iceFillStrategy,
        creeperBeamGuidanceEnabled,
        enabledSolvers,
        ticTacToePredictionEnabled,
    )

    fun onChat(client: Minecraft?, rawMessage: String?) = core.onChat(client, rawMessage)

    fun onChat(client: Minecraft?, rawMessage: String?, weirdosEnabled: Boolean, quizEnabled: Boolean): Boolean =
        core.onChat(client, rawMessage, weirdosEnabled, quizEnabled)

    fun visualSnapshot(client: Minecraft?): PuzzleVisualSnapshot = core.visualSnapshot(client)
    fun currentPuzzleRoomDisplayName(client: Minecraft?): String? = core.currentPuzzleRoomDisplayName(client)
    fun currentPuzzleRoomIdentity(client: Minecraft?): String? = core.currentPuzzleRoomIdentity(client)
    fun currentPuzzleRoomMapIdentity(client: Minecraft?): String? = core.currentPuzzleRoomMapIdentity(client)
    fun tpMazeDiagnosticSummary(client: Minecraft?): String? = core.tpMazeDiagnosticSummary(client)
    fun ticTacToeVisualTargets(client: Minecraft?): TicTacToeVisualTargets = core.ticTacToeVisualTargets(client)
    fun ticTacToeDiagnosticSummary(): String? = core.ticTacToeDiagnosticSummary()
    fun tickQuizTimer(observedServerGameTime: Long) = core.tickQuizTimer(observedServerGameTime)
    fun quizTimerSnapshot(): QuizTimerSnapshot = core.quizTimerSnapshot()
    fun quizDiagnosticSummary(): String? = core.quizDiagnosticSummary()
    fun styleQuizChatOption(component: Component?): Component? = core.styleQuizChatOption(component)
    fun boulderDiagnosticSummary(): String? = core.boulderDiagnosticSummary()
    fun waterBoardDiagnosticSummary(): String? = core.waterBoardDiagnosticSummary()

    fun sourceRoomRelativePosition(client: Minecraft?, worldPosition: BlockPos?): BlockPos? =
        core.sourceRoomRelativePosition(client, worldPosition)

    fun reset() = core.reset()
    fun resetSolver(solverId: String?) = core.resetSolver(solverId)
    fun clearCreeperBeamEndpointConfirmation() = core.clearCreeperBeamEndpointConfirmation()

    fun isConfirmedCreeperBeamsEndpoint(client: Minecraft?, position: BlockPos?): Boolean =
        core.isConfirmedCreeperBeamsEndpoint(client, position)

    fun onCreeperBeamBlockUpdate(
        client: Minecraft?,
        position: BlockPos?,
        oldState: BlockState?,
        newState: BlockState?,
    ): Boolean = core.onCreeperBeamBlockUpdate(client, position, oldState, newState)

    fun observeTpMazeCorrection(
        client: Minecraft?,
        after: Vec3?,
        yaw: Float,
        pitch: Float,
        playerBoundsBefore: AABB?,
    ): Boolean = core.observeTpMazeCorrection(client, after, yaw, pitch, playerBoundsBefore)

    fun observeBoulderClick(client: Minecraft?, position: BlockPos?): Boolean = core.observeBoulderClick(client, position)
    fun observeWaterBoardClick(client: Minecraft?, position: BlockPos?): Boolean = core.observeWaterBoardClick(client, position)
    fun boulderCurrentTarget(client: Minecraft?): DungeonBoulderRules.Target? = core.boulderCurrentTarget(client)
    fun boulderTargets(client: Minecraft?, showAll: Boolean): List<DungeonBoulderRules.Target> =
        core.boulderTargets(client, showAll)

    fun tpMazeRenderSegments(
        client: Minecraft?,
        showObservedLinks: Boolean,
        showSuggestedTracer: Boolean,
        tracerStart: Vec3?,
    ): List<DungeonTpMazeRenderGeometry.Segment> =
        core.tpMazeRenderSegments(client, showObservedLinks, showSuggestedTracer, tracerStart)

    fun icePathDiagnosticSummary(): String? = core.icePathDiagnosticSummary()
    fun icePathRenderPath(client: Minecraft?): IcePathRenderPath = core.icePathRenderPath(client)
    fun iceRenderPath(client: Minecraft?): IceRenderPath = core.iceRenderPath(client)
    fun tpMazePadVisuals(client: Minecraft?): List<TpMazePadVisual> = core.tpMazePadVisuals(client)

    companion object {
        @JvmStatic
        fun parseExplicitNpcClue(rawMessage: String?, confirmedNpcNames: Set<String?>?): ClueLine? {
            val clue = DungeonOdinPuzzleObserverLogic.parseExplicitNpcClue(rawMessage, confirmedNpcNames)
                ?: return null
            return ClueLine(clue.speaker, clue.statement)
        }

        @JvmStatic
        fun planIceFill(
            observedCells: Set<DungeonPuzzleLogic.Cell?>?,
            playerCell: DungeonPuzzleLogic.Cell?,
            nodeBudget: Int,
        ): IcePlan = DungeonOdinPuzzleObserverCore().planIceFill(observedCells, playerCell, nodeBudget)
    }

    enum class IceFillStrategy {
        EASY,
        HARD,
        ;

        companion object {
            @JvmStatic
            fun fromSetting(value: String?): IceFillStrategy =
                if (value?.equals("HARD", ignoreCase = true) == true) HARD else EASY
        }
    }

    enum class ScanStatus(private val idValue: String) {
        COMPLETE("complete"),
        INCOMPLETE("incomplete"),
        PARTIAL("partial"),
        ;

        fun id(): String = idValue
    }

    /** Normalizes the former record's compact constructor and retains its public accessor surface. */
    class Guidance(name: String?, lines: List<String>?, scanStatus: ScanStatus?, scanReason: String?) {
        private val nameValue = name ?: "Puzzle"
        private val linesValue = immutableList(lines)
        private val scanStatusValue = scanStatus ?: ScanStatus.INCOMPLETE
        private val scanReasonValue = scanReason ?: "No scan reason was provided."

        constructor(name: String?, lines: List<String>?) : this(
            name,
            lines,
            ScanStatus.COMPLETE,
            "Confirmed observations produced puzzle guidance.",
        )

        fun name(): String = nameValue
        fun lines(): List<String> = linesValue
        fun scanStatus(): ScanStatus = scanStatusValue
        fun scanReason(): String = scanReasonValue

        override fun equals(other: Any?): Boolean = other === this || other is Guidance &&
            nameValue == other.nameValue && linesValue == other.linesValue &&
            scanStatusValue == other.scanStatusValue && scanReasonValue == other.scanReasonValue

        override fun hashCode(): Int = recordHash(nameValue, linesValue, scanStatusValue, scanReasonValue)
        override fun toString(): String = recordText(
            "Guidance",
            "name" to nameValue,
            "lines" to linesValue,
            "scanStatus" to scanStatusValue,
            "scanReason" to scanReasonValue,
        )
    }

    class WorldBlock(x: Int, y: Int, z: Int) {
        private val xValue = x
        private val yValue = y
        private val zValue = z

        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue

        override fun equals(other: Any?): Boolean = other === this || other is WorldBlock &&
            xValue == other.xValue && yValue == other.yValue && zValue == other.zValue

        override fun hashCode(): Int = recordHash(xValue, yValue, zValue)
        override fun toString(): String = recordText("WorldBlock", "x" to xValue, "y" to yValue, "z" to zValue)
    }

    class TicTacToeVisualTargets(currentO: WorldBlock?, predictedX: WorldBlock?, nextO: WorldBlock?) {
        private val currentOValue = currentO
        private val predictedXValue = predictedX
        private val nextOValue = nextO

        fun currentO(): WorldBlock? = currentOValue
        fun predictedX(): WorldBlock? = predictedXValue
        fun nextO(): WorldBlock? = nextOValue

        override fun equals(other: Any?): Boolean = other === this || other is TicTacToeVisualTargets &&
            currentOValue == other.currentOValue && predictedXValue == other.predictedXValue && nextOValue == other.nextOValue

        override fun hashCode(): Int = recordHash(currentOValue, predictedXValue, nextOValue)
        override fun toString(): String = recordText(
            "TicTacToeVisualTargets",
            "currentO" to currentOValue,
            "predictedX" to predictedXValue,
            "nextO" to nextOValue,
        )

        companion object {
            @JvmStatic
            fun empty(): TicTacToeVisualTargets = TicTacToeVisualTargets(null, null, null)
        }
    }

    class WaterBoardLabel(position: WorldBlock?, text: String?, color: Int) {
        private val positionValue = position
        private val textValue = text
        private val colorValue = color

        fun position(): WorldBlock? = positionValue
        fun text(): String? = textValue
        fun color(): Int = colorValue

        override fun equals(other: Any?): Boolean = other === this || other is WaterBoardLabel &&
            positionValue == other.positionValue && textValue == other.textValue && colorValue == other.colorValue

        override fun hashCode(): Int = recordHash(positionValue, textValue, colorValue)
        override fun toString(): String = recordText(
            "WaterBoardLabel",
            "position" to positionValue,
            "text" to textValue,
            "color" to colorValue,
        )
    }

    class WaterBoardVisual(
        orderedUpcomingLevers: List<WorldBlock>?,
        scheduleLabels: List<WaterBoardLabel>?,
        routeConfirmed: Boolean,
    ) {
        private val orderedUpcomingLeversValue = immutableList(orderedUpcomingLevers)
        private val scheduleLabelsValue = immutableList(scheduleLabels)
        private val routeConfirmedValue = routeConfirmed

        constructor(orderedUpcomingLevers: List<WorldBlock>?, routeConfirmed: Boolean) :
            this(orderedUpcomingLevers, null, routeConfirmed)

        fun orderedUpcomingLevers(): List<WorldBlock> = orderedUpcomingLeversValue
        fun scheduleLabels(): List<WaterBoardLabel> = scheduleLabelsValue
        fun routeConfirmed(): Boolean = routeConfirmedValue

        override fun equals(other: Any?): Boolean = other === this || other is WaterBoardVisual &&
            orderedUpcomingLeversValue == other.orderedUpcomingLeversValue &&
            scheduleLabelsValue == other.scheduleLabelsValue && routeConfirmedValue == other.routeConfirmedValue

        override fun hashCode(): Int = recordHash(orderedUpcomingLeversValue, scheduleLabelsValue, routeConfirmedValue)
        override fun toString(): String = recordText(
            "WaterBoardVisual",
            "orderedUpcomingLevers" to orderedUpcomingLeversValue,
            "scheduleLabels" to scheduleLabelsValue,
            "routeConfirmed" to routeConfirmedValue,
        )

        companion object {
            @JvmStatic
            fun empty(): WaterBoardVisual = WaterBoardVisual(emptyList(), emptyList(), false)
        }
    }

    class CreeperPairVisual(first: WorldBlock?, second: WorldBlock?, progressed: Boolean, catalogIndex: Int) {
        private val firstValue = first
        private val secondValue = second
        private val progressedValue = progressed
        private val catalogIndexValue = catalogIndex

        fun first(): WorldBlock? = firstValue
        fun second(): WorldBlock? = secondValue
        fun progressed(): Boolean = progressedValue
        fun catalogIndex(): Int = catalogIndexValue

        override fun equals(other: Any?): Boolean = other === this || other is CreeperPairVisual &&
            firstValue == other.firstValue && secondValue == other.secondValue &&
            progressedValue == other.progressedValue && catalogIndexValue == other.catalogIndexValue

        override fun hashCode(): Int = recordHash(firstValue, secondValue, progressedValue, catalogIndexValue)
        override fun toString(): String = recordText(
            "CreeperPairVisual",
            "first" to firstValue,
            "second" to secondValue,
            "progressed" to progressedValue,
            "catalogIndex" to catalogIndexValue,
        )
    }

    class WeirdoNpcVisual(position: WorldBlock?, name: String?, correctChest: Boolean) {
        private val positionValue = position
        private val nameValue = name ?: ""
        private val correctChestValue = correctChest

        fun position(): WorldBlock? = positionValue
        fun name(): String = nameValue
        fun correctChest(): Boolean = correctChestValue

        override fun equals(other: Any?): Boolean = other === this || other is WeirdoNpcVisual &&
            positionValue == other.positionValue && nameValue == other.nameValue && correctChestValue == other.correctChestValue

        override fun hashCode(): Int = recordHash(positionValue, nameValue, correctChestValue)
        override fun toString(): String = recordText(
            "WeirdoNpcVisual",
            "position" to positionValue,
            "name" to nameValue,
            "correctChest" to correctChestValue,
        )
    }

    class QuizOptionVisual(marker: String?, answer: String?, position: WorldBlock?, correct: Boolean) {
        private val markerValue = marker ?: ""
        private val answerValue = answer ?: ""
        private val positionValue = position
        private val correctValue = correct

        fun marker(): String = markerValue
        fun answer(): String = answerValue
        fun position(): WorldBlock? = positionValue
        fun correct(): Boolean = correctValue

        override fun equals(other: Any?): Boolean = other === this || other is QuizOptionVisual &&
            markerValue == other.markerValue && answerValue == other.answerValue &&
            positionValue == other.positionValue && correctValue == other.correctValue

        override fun hashCode(): Int = recordHash(markerValue, answerValue, positionValue, correctValue)
        override fun toString(): String = recordText(
            "QuizOptionVisual",
            "marker" to markerValue,
            "answer" to answerValue,
            "position" to positionValue,
            "correct" to correctValue,
        )
    }

    class QuizTimerSnapshot(remainingTicks: Int, maximumTicks: Int, stage: Int) {
        private val remainingTicksValue = maxOf(0, remainingTicks)
        private val maximumTicksValue = maxOf(0, maximumTicks)
        private val stageValue = stage.coerceIn(0, 3)

        fun remainingTicks(): Int = remainingTicksValue
        fun maximumTicks(): Int = maximumTicksValue
        fun stage(): Int = stageValue

        override fun equals(other: Any?): Boolean = other === this || other is QuizTimerSnapshot &&
            remainingTicksValue == other.remainingTicksValue && maximumTicksValue == other.maximumTicksValue &&
            stageValue == other.stageValue

        override fun hashCode(): Int = recordHash(remainingTicksValue, maximumTicksValue, stageValue)
        override fun toString(): String = recordText(
            "QuizTimerSnapshot",
            "remainingTicks" to remainingTicksValue,
            "maximumTicks" to maximumTicksValue,
            "stage" to stageValue,
        )
    }

    class PuzzleVisualSnapshot(
        waterBoard: WaterBoardVisual?,
        creeperPairs: List<CreeperPairVisual>?,
        weirdos: List<WeirdoNpcVisual>?,
        quizOptions: List<QuizOptionVisual>?,
    ) {
        private val waterBoardValue = waterBoard ?: WaterBoardVisual.empty()
        private val creeperPairsValue = immutableList(creeperPairs)
        private val weirdosValue = immutableList(weirdos)
        private val quizOptionsValue = immutableList(quizOptions)

        fun waterBoard(): WaterBoardVisual = waterBoardValue
        fun creeperPairs(): List<CreeperPairVisual> = creeperPairsValue
        fun weirdos(): List<WeirdoNpcVisual> = weirdosValue
        fun quizOptions(): List<QuizOptionVisual> = quizOptionsValue

        override fun equals(other: Any?): Boolean = other === this || other is PuzzleVisualSnapshot &&
            waterBoardValue == other.waterBoardValue && creeperPairsValue == other.creeperPairsValue &&
            weirdosValue == other.weirdosValue && quizOptionsValue == other.quizOptionsValue

        override fun hashCode(): Int = recordHash(waterBoardValue, creeperPairsValue, weirdosValue, quizOptionsValue)
        override fun toString(): String = recordText(
            "PuzzleVisualSnapshot",
            "waterBoard" to waterBoardValue,
            "creeperPairs" to creeperPairsValue,
            "weirdos" to weirdosValue,
            "quizOptions" to quizOptionsValue,
        )

        companion object {
            @JvmStatic
            fun empty(): PuzzleVisualSnapshot = PuzzleVisualSnapshot(WaterBoardVisual.empty(), emptyList(), emptyList(), emptyList())
        }
    }

    class IceRenderPath(points: List<IceFillRenderGeometry.Point>?, progress: Int) {
        private val pointsValue = immutableList(points)
        private val progressValue = maxOf(0, progress)

        fun points(): List<IceFillRenderGeometry.Point> = pointsValue
        fun progress(): Int = progressValue

        override fun equals(other: Any?): Boolean = other === this || other is IceRenderPath &&
            pointsValue == other.pointsValue && progressValue == other.progressValue

        override fun hashCode(): Int = recordHash(pointsValue, progressValue)
        override fun toString(): String = recordText("IceRenderPath", "points" to pointsValue, "progress" to progressValue)
    }

    class IcePathRenderPath(points: List<DungeonIcePathSolver.Point>?) {
        private val pointsValue = immutableList(points)

        fun points(): List<DungeonIcePathSolver.Point> = pointsValue

        override fun equals(other: Any?): Boolean = other === this || other is IcePathRenderPath && pointsValue == other.pointsValue
        override fun hashCode(): Int = recordHash(pointsValue)
        override fun toString(): String = recordText("IcePathRenderPath", "points" to pointsValue)
    }

    class IcePlan(route: List<DungeonPuzzleLogic.Cell>?, reason: String?) {
        private val routeValue = immutableList(route)
        private val reasonValue = reason ?: ""

        fun route(): List<DungeonPuzzleLogic.Cell> = routeValue
        fun reason(): String = reasonValue

        override fun equals(other: Any?): Boolean = other === this || other is IcePlan &&
            routeValue == other.routeValue && reasonValue == other.reasonValue

        override fun hashCode(): Int = recordHash(routeValue, reasonValue)
        override fun toString(): String = recordText("IcePlan", "route" to routeValue, "reason" to reasonValue)
    }

    class ClueLine(speaker: String?, statement: String?) {
        private val speakerValue = speaker
        private val statementValue = statement

        fun speaker(): String? = speakerValue
        fun statement(): String? = statementValue

        override fun equals(other: Any?): Boolean = other === this || other is ClueLine &&
            speakerValue == other.speakerValue && statementValue == other.statementValue

        override fun hashCode(): Int = recordHash(speakerValue, statementValue)
        override fun toString(): String = recordText("ClueLine", "speaker" to speakerValue, "statement" to statementValue)
    }

    class TpMazePadVisual(
        id: Int,
        position: DungeonTpMazeRules.WorldPosition?,
        visited: Boolean,
        candidate: Boolean,
        remainingCandidates: Int,
    ) {
        private val idValue = id
        private val positionValue = position
        private val visitedValue = visited
        private val candidateValue = candidate
        private val remainingCandidatesValue = remainingCandidates

        fun id(): Int = idValue
        fun position(): DungeonTpMazeRules.WorldPosition? = positionValue
        fun visited(): Boolean = visitedValue
        fun candidate(): Boolean = candidateValue
        fun remainingCandidates(): Int = remainingCandidatesValue

        override fun equals(other: Any?): Boolean = other === this || other is TpMazePadVisual &&
            idValue == other.idValue && positionValue == other.positionValue && visitedValue == other.visitedValue &&
            candidateValue == other.candidateValue && remainingCandidatesValue == other.remainingCandidatesValue

        override fun hashCode(): Int =
            recordHash(idValue, positionValue, visitedValue, candidateValue, remainingCandidatesValue)

        override fun toString(): String = recordText(
            "TpMazePadVisual",
            "id" to idValue,
            "position" to positionValue,
            "visited" to visitedValue,
            "candidate" to candidateValue,
            "remainingCandidates" to remainingCandidatesValue,
        )
    }
}

private fun <T> immutableList(values: List<T>?): List<T> =
    if (values == null) emptyList() else java.util.List.copyOf(values)

private fun recordHash(vararg values: Any?): Int = java.util.Arrays.hashCode(values)

private fun recordText(type: String, vararg fields: Pair<String, Any?>): String =
    fields.joinToString(prefix = "$type[", postfix = "]") { (name, value) -> "$name=$value" }
