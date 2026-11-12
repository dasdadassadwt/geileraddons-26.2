package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import java.util.LinkedHashSet

/** Automatically detects the current room puzzle and renders world-space guidance for enabled solvers. */
class DungeonPuzzleSolversModule private constructor() : Module(
    "Puzzle Solvers",
    "Automatically detects dungeon puzzles and displays guidance for enabled solvers.",
    Category.F7,
    false,
    waterBoard,
    optimizedSolutions,
    waterBoardTracer,
    waterBoardTracerFirstColor,
    waterBoardTracerSecondColor,
    resetWaterBoard,
    tpMaze,
    tpMazeSingleCandidateColor,
    tpMazeMultipleCandidateColor,
    tpMazeVisitedColor,
    tpMazeTracer,
    tpMazeTracerColor,
    resetTpMaze,
    icePath,
    icePathColor,
    iceFill,
    iceFillColor,
    useOptimizedIcePatterns,
    resetIceFill,
    blaze,
    blazeNextLine,
    blazeLineCount,
    blazeLineWidth,
    blazeStyle,
    blazeFirstColor,
    blazeSecondColor,
    blazeThirdColor,
    blazeOtherColor,
    blazeSendComplete,
    resetBlaze,
    creeperBeams,
    creeperBeamStyle,
    creeperBeamTracer,
    creeperBeamAlpha,
    resetCreeperBeams,
    creeperBeamHitSound,
    threeWeirdos,
    weirdosStyle,
    weirdosCorrectColor,
    weirdosWrongColor,
    resetWeirdos,
    quiz,
    quizColor,
    quizDepth,
    resetQuiz,
    boulder,
    showAllBoulderClicks,
    boulderStyle,
    boulderColor,
    ticTacToe,
    ticTacToePrediction,
    puzzleTimers,
    draftPrompt,
) {
    init {
        group(
            SettingGroup.switched(
                "Water Board",
                waterBoard,
                optimizedSolutions,
                waterBoardTracer,
                waterBoardTracerFirstColor,
                waterBoardTracerSecondColor,
                resetWaterBoard,
            ),
            SettingGroup.switched(
                "TP Maze",
                tpMaze,
                tpMazeSingleCandidateColor,
                tpMazeMultipleCandidateColor,
                tpMazeVisitedColor,
                tpMazeTracer,
                tpMazeTracerColor,
                resetTpMaze,
            ),
            SettingGroup.switched("Ice Path", icePath, icePathColor),
            SettingGroup.switched("Ice Fill", iceFill, iceFillColor, useOptimizedIcePatterns, resetIceFill),
            SettingGroup.switched(
                "Blaze",
                blaze,
                blazeNextLine,
                blazeLineCount,
                blazeLineWidth,
                blazeStyle,
                blazeFirstColor,
                blazeSecondColor,
                blazeThirdColor,
                blazeOtherColor,
                blazeSendComplete,
                resetBlaze,
            ),
            SettingGroup.switched(
                "Creeper Beams",
                creeperBeams,
                creeperBeamStyle,
                creeperBeamTracer,
                creeperBeamAlpha,
                creeperBeamHitSound,
                resetCreeperBeams,
            ),
            SettingGroup.switched(
                "Three Weirdos",
                threeWeirdos,
                weirdosStyle,
                weirdosCorrectColor,
                weirdosWrongColor,
                resetWeirdos,
            ),
            SettingGroup.switched("Quiz", quiz, quizColor, quizDepth, resetQuiz),
            SettingGroup.switched("Boulder", boulder, showAllBoulderClicks, boulderStyle, boulderColor),
            SettingGroup.switched("Tic Tac Toe", ticTacToe, ticTacToePrediction),
            SettingGroup("General", puzzleTimers, draftPrompt),
        )
    }

    fun anySolverEnabled(): Boolean =
        waterBoard.value() || tpMaze.value() || icePath.value() || iceFill.value() || blaze.value() ||
            creeperBeams.value() || threeWeirdos.value() || quiz.value() || boulder.value() || ticTacToe.value()

    /** True when the parent module is on and at least one independently selectable solver is on. */
    fun hasEnabledSolvers(): Boolean = isEnabled() && anySolverEnabled()

    fun solverEnabled(id: String?): Boolean {
        if (!isEnabled()) return false
        return when (id ?: "") {
            "WATER_BOARD" -> waterBoard.value()
            "TP_MAZE" -> tpMaze.value()
            "ICE_PATH" -> icePath.value()
            "ICE_FILL" -> iceFill.value()
            "BLAZE" -> blaze.value()
            "CREEPER_BEAMS" -> creeperBeams.value()
            "THREE_WEIRDOS" -> threeWeirdos.value()
            "QUIZ" -> quiz.value()
            "BOULDER" -> boulder.value()
            "TIC_TAC_TOE" -> ticTacToe.value()
            else -> false
        }
    }

    fun enabledSolverIds(): Set<String> {
        if (!isEnabled()) return java.util.Set.of()
        val enabled = LinkedHashSet<String>()
        for (id in listOf(
            "WATER_BOARD",
            "TP_MAZE",
            "ICE_PATH",
            "ICE_FILL",
            "BLAZE",
            "CREEPER_BEAMS",
            "THREE_WEIRDOS",
            "QUIZ",
            "BOULDER",
            "TIC_TAC_TOE",
        )) {
            if (solverEnabled(id)) enabled.add(id)
        }
        return java.util.Set.copyOf(enabled)
    }

    companion object {
        @JvmField
        val waterBoard = BooleanSetting("Water Board Solver", true)

        @JvmField
        val optimizedSolutions = BooleanSetting("Optimized Solutions", false)

        @JvmField
        val waterBoardTracer = BooleanSetting("Water Tracer", true)

        @JvmField
        val waterBoardTracerFirstColor = ColorSetting("Tracer Color First", 85, 255, 85, 255)

        @JvmField
        val waterBoardTracerSecondColor = ColorSetting("Tracer Color Second", 255, 170, 0, 255)

        @JvmField
        val resetWaterBoard = ModuleAction(
            "Reset Water Board",
            "Reset tracked lever progress for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("WATER_BOARD") },
        )

        @JvmField
        val tpMaze = BooleanSetting("TP Maze Solver", true)

        @JvmField
        val tpMazeSingleCandidateColor = ColorSetting("Color for one", 85, 255, 85, 128)

        @JvmField
        val tpMazeMultipleCandidateColor = ColorSetting("Color for multiple", 255, 170, 0, 128)

        @JvmField
        val tpMazeVisitedColor = ColorSetting("Color for visited", 255, 85, 85, 128)

        @JvmField
        val tpMazeTracer = BooleanSetting("Maze Tracer", true)

        @JvmField
        val tpMazeTracerColor = ColorSetting("Tracer Color", 85, 255, 255, 255)

        @JvmField
        val resetTpMaze = ModuleAction(
            "Reset TP Maze",
            "Clear visited pads and observed links for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("TP_MAZE") },
        )

        @JvmField
        val icePath = BooleanSetting("Ice Path Solver", true)

        @JvmField
        val icePathColor = ColorSetting("Ice Path Color", 255, 85, 255, 255)

        @JvmField
        val iceFill = BooleanSetting("Ice Fill Solver", true)

        @JvmField
        val iceFillColor = ColorSetting("Ice Fill Color", 255, 85, 255, 255)

        @JvmField
        val useOptimizedIcePatterns = BooleanSetting("Use Optimized Patterns", false)

        @JvmField
        val resetIceFill = ModuleAction(
            "Reset Ice Fill",
            "Clear the detected route and progress for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("ICE_FILL") },
        )

        @JvmField
        val blaze = BooleanSetting("Blaze Solver", true)

        @JvmField
        val blazeNextLine = BooleanSetting("Blaze Solver Next Line", true)

        @JvmField
        val blazeLineCount = NumberSetting("Blaze Solver Lines", 1.0f, 10.0f, 1.0f, true)

        @JvmField
        val blazeLineWidth = NumberSetting("Blaze Solver Lines Width", 0.5f, 5.0f, 2.0f)

        @JvmField
        val blazeStyle = ChoiceSetting(
            "Blaze Style",
            "FILLED_OUTLINE",
            mapOf("FILLED" to "Filled", "FILLED_OUTLINE" to "Filled Outline", "OUTLINE" to "Outline"),
            "FILLED",
            "FILLED_OUTLINE",
            "OUTLINE",
        )

        @JvmField
        val blazeFirstColor = ColorSetting("First Color", 85, 255, 85, 191)

        @JvmField
        val blazeSecondColor = ColorSetting("Second Color", 255, 170, 0, 191)

        @JvmField
        val blazeThirdColor = ColorSetting("Third Color", 255, 85, 85, 191)

        @JvmField
        val blazeOtherColor = ColorSetting("Other Color", 255, 255, 255, 76)

        @JvmField
        val blazeSendComplete = BooleanSetting("Send Complete", false)

        @JvmField
        val resetBlaze = ModuleAction(
            "Reset Blaze",
            "Clear the detected Blaze targets for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("BLAZE") },
        )

        @JvmField
        val creeperBeams = BooleanSetting("Creeper Beams Solver", true)

        @JvmField
        val creeperBeamStyle = ChoiceSetting(
            "Beams Style",
            "FILLED_OUTLINE",
            mapOf("FILLED" to "Filled", "FILLED_OUTLINE" to "Filled Outline", "OUTLINE" to "Outline"),
            "FILLED",
            "FILLED_OUTLINE",
            "OUTLINE",
        )

        @JvmField
        val creeperBeamTracer = BooleanSetting("Beams Tracer", false)

        @JvmField
        val creeperBeamAlpha = NumberSetting("Beams Color Alpha", 0.0f, 1.0f, 0.70f)

        @JvmField
        val resetCreeperBeams = ModuleAction(
            "Reset Creeper Beams",
            "Clear endpoint progress for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("CREEPER_BEAMS") },
        )

        @JvmField
        val creeperBeamHitSound = BooleanSetting("Beam Hit Sound", true)

        @JvmField
        val threeWeirdos = BooleanSetting("Three Weirdos", true)

        @JvmField
        val weirdosStyle = ChoiceSetting(
            "Weirdos Style",
            "FILLED_OUTLINE",
            mapOf("FILLED" to "Filled", "FILLED_OUTLINE" to "Filled Outline", "OUTLINE" to "Outline"),
            "FILLED",
            "FILLED_OUTLINE",
            "OUTLINE",
        )

        @JvmField
        val weirdosCorrectColor = ColorSetting("Weirdos Correct Color", 85, 255, 85, 178)

        @JvmField
        val weirdosWrongColor = ColorSetting("Weirdos Wrong Color", 255, 85, 85, 178)

        @JvmField
        val resetWeirdos = ModuleAction(
            "Reset Three Weirdos",
            "Clear the clue results for this room.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("THREE_WEIRDOS") },
        )

        @JvmField
        val quiz = BooleanSetting("Quiz Solver", true)

        @JvmField
        val quizColor = ColorSetting("Quiz Color", 85, 255, 85, 191)

        @JvmField
        val quizDepth = BooleanSetting("Quiz Depth", false)

        @JvmField
        val resetQuiz = ModuleAction(
            "Reset Quiz",
            "Clear the active Oruo question and answer.",
            Runnable { DungeonOdinAssistModule.INSTANCE.resetPuzzle("QUIZ") },
        )

        @JvmField
        val boulder = BooleanSetting("Boulder Solver", true)

        @JvmField
        val showAllBoulderClicks = BooleanSetting("Show All Boulder Clicks", false)

        @JvmField
        val boulderStyle = ChoiceSetting(
            "Boulder Style",
            "OUTLINE",
            mapOf("FILLED" to "Filled", "FILLED_OUTLINE" to "Filled Outline", "OUTLINE" to "Outline"),
            "FILLED",
            "FILLED_OUTLINE",
            "OUTLINE",
        )

        @JvmField
        val boulderColor = ColorSetting("Boulder Color", 85, 255, 85, 255)

        @JvmField
        val ticTacToe = BooleanSetting("Tic Tac Toe Solver", true)

        @JvmField
        val ticTacToePrediction = BooleanSetting("Predict Machine Moves", false)

        @JvmField
        val puzzleTimers = BooleanSetting("Puzzle Timers", true)

        @JvmField
        val draftPrompt = BooleanSetting("Draft Prompt", true)

        @JvmField
        val INSTANCE = DungeonPuzzleSolversModule()
    }
}
