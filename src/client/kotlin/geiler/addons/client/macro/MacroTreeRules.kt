package geiler.addons.client.macro

import java.util.IdentityHashMap

/** Pure insertion and movement rules shared by the macro editor and offline checks. */
object MacroTreeRules {
    /** Shared maximum nesting depth used by the editor, local config codec, and clipboard codec. */
    const val MAX_DEPTH = 12

    @JvmStatic
    fun canInsert(macro: MacroDefinition?, step: MacroStep?, target: List<MacroStep?>?): Boolean {
        if (macro == null || step == null || target == null) return false
        val targetDepth = findListDepth(macro.steps(), target, 0, IdentityHashMap())
        if (targetDepth < 0 || containsChildList(step, target, IdentityHashMap())) return false
        return targetDepth + subtreeDepth(step, IdentityHashMap()) <= MAX_DEPTH
    }

    /** Whether [target] is nested anywhere below this step. */
    @JvmStatic
    fun containsDescendantList(step: MacroStep?, target: List<MacroStep?>?): Boolean =
        containsChildList(step, target, IdentityHashMap())

    /** Number of additional child-list levels contained below this step. */
    @JvmStatic
    fun subtreeDepth(step: MacroStep?): Int = subtreeDepth(step, IdentityHashMap())

    @JvmStatic
    fun <T : MacroStep> canMove(
        macro: MacroDefinition?,
        source: MutableList<T>?,
        step: T?,
        target: MutableList<T>?,
        targetIndex: Int,
    ): Boolean {
        if (source == null || step == null || source.indexOf(step) < 0 || !canInsert(macro, step, target)) return false
        val sourceIndex = source.indexOf(step)
        val insertionIndex = clamp(targetIndex, target!!.size).let {
            if (source === target && sourceIndex < it) it - 1 else it
        }
        return source !== target || sourceIndex != insertionIndex
    }

    /** Moves the original node object, retaining its values and any nested child lists. */
    @JvmStatic
    fun <T : MacroStep> move(
        macro: MacroDefinition?,
        source: MutableList<T>?,
        step: T?,
        target: MutableList<T>?,
        targetIndex: Int,
    ): Boolean {
        if (!canMove(macro, source, step, target, targetIndex)) return false
        val mutableSource = source ?: return false
        val mutableTarget = target ?: return false
        val movingStep = step ?: return false
        val sourceIndex = mutableSource.indexOf(movingStep)
        var insertionIndex = clamp(targetIndex, mutableTarget.size)
        if (mutableSource === mutableTarget && sourceIndex < insertionIndex) insertionIndex--
        mutableSource.removeAt(sourceIndex)
        mutableTarget.add(insertionIndex, movingStep)
        return true
    }

    private fun findListDepth(
        steps: List<MacroStep?>,
        target: List<MacroStep?>,
        depth: Int,
        visited: IdentityHashMap<List<MacroStep?>, Boolean>,
    ): Int {
        if (steps === target) return depth
        if (visited.put(steps, true) != null) return -1
        for (step in steps) {
            if (step == null) continue
            for (children in childLists(step)) {
                if (children === target) return depth + 1
                val found = findListDepth(children, target, depth + 1, visited)
                if (found >= 0) return found
            }
        }
        return -1
    }

    private fun containsChildList(
        step: MacroStep?,
        target: List<MacroStep?>?,
        visited: IdentityHashMap<MacroStep, Boolean>,
    ): Boolean {
        if (step == null || target == null || visited.put(step, true) != null) return false
        for (children in childLists(step)) {
            if (children === target) return true
            for (child in children) if (containsChildList(child, target, visited)) return true
        }
        return false
    }

    private fun subtreeDepth(step: MacroStep?, active: IdentityHashMap<MacroStep, Boolean>): Int {
        if (step == null) return 0
        if (active.put(step, true) != null) return MAX_DEPTH + 1
        var deepest = 0
        for (children in childLists(step)) {
            for (child in children) deepest = maxOf(deepest, 1 + subtreeDepth(child, active))
        }
        active.remove(step)
        return deepest
    }

    private fun childLists(step: MacroStep?): List<List<MacroStep?>> = when (step) {
        is MacroStep.IfElse -> listOf(step.thenSteps(), step.elseSteps())
        is MacroStep.Repeat -> listOf(step.steps())
        is MacroStep.RepeatUntil -> listOf(step.steps())
        is MacroStep.Switch -> {
            val lists = ArrayList<List<MacroStep?>>(step.cases().size + 1)
            for (branch in step.cases()) lists.add(branch.steps())
            lists.add(step.defaultSteps())
            java.util.List.copyOf(lists)
        }
        else -> emptyList()
    }

    private fun clamp(index: Int, size: Int): Int = index.coerceIn(0, size)
}
