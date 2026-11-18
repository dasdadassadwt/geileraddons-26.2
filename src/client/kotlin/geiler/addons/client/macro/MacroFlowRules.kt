package geiler.addons.client.macro

import java.util.Locale
import java.util.random.RandomGenerator

/** Small pure rules shared by the client runner and its offline checks. */
object MacroFlowRules {
    /** OUT-2: the protocol limit for a chat line or command; longer text would be rejected by the server. */
    const val MAX_CHAT_LENGTH = 256

    enum class UntilDecision { EXIT, RUN_BODY, EMPTY_BODY }

    @JvmStatic
    fun repeatUntil(stopConditionTrue: Boolean, hasBody: Boolean): UntilDecision = when {
        stopConditionTrue -> UntilDecision.EXIT
        hasBody -> UntilDecision.RUN_BODY
        else -> UntilDecision.EMPTY_BODY
    }

    enum class WorldSwitchStart { COMPLETE, WAIT, DETECTION_OFF }

    /** FL-2/FL-3: a switch to the island the player is already on completes at once; no detection can never complete. */
    @JvmStatic
    fun worldSwitchStart(detectionEnabled: Boolean, current: geiler.addons.client.location.Island?, target: geiler.addons.client.location.Island?): WorldSwitchStart = when {
        !detectionEnabled -> WorldSwitchStart.DETECTION_OFF
        current != null && current == target && current != geiler.addons.client.location.Island.NONE -> WorldSwitchStart.COMPLETE
        else -> WorldSwitchStart.WAIT
    }

    enum class LevelChange { WAIT, RESUME, ABORT }

    /** How long a run waits for the island to be reported again after a level change it did not expect. */
    const val LEVEL_CHANGE_WAIT_NANOS = 15_000_000_000L

    /**
     * A level change during a run with no World Switch node (a plain Wait between /hub and /warp): the run resumes once
     * the new island is known, instead of dying in the lobby. Without island detection or an island restriction the new
     * island is never named, so it resumes at once; the restriction itself is enforced by the runner's island check.
     */
    @JvmStatic
    fun levelChangeWithoutSwitch(
        detectionEnabled: Boolean,
        islandRestricted: Boolean,
        current: geiler.addons.client.location.Island?,
        waitedNanos: Long,
    ): LevelChange = when {
        !detectionEnabled || !islandRestricted || (current != null && current != geiler.addons.client.location.Island.NONE) ->
            LevelChange.RESUME
        waitedNanos > LEVEL_CHANGE_WAIT_NANOS -> LevelChange.ABORT
        else -> LevelChange.WAIT
    }

    /**
     * The island list is a start gate. A run that already changed world (its own /is, /hub or /warp) is not ended by it,
     * so a macro restricted to one island can travel and carry on; a world-switch wait also survives an unreported island.
     */
    @JvmStatic
    fun runEndsOnIsland(
        allowed: Boolean,
        crossedWorld: Boolean,
        waitingForWorldSwitch: Boolean,
        current: geiler.addons.client.location.Island?,
    ): Boolean = !allowed && !crossedWorld && !(waitingForWorldSwitch && current == geiler.addons.client.location.Island.NONE)

    /** FL-1: literal item-count arguments become number literals so function binding does not drop their item ids. */
    @JvmStatic
    fun materializeItemCountArguments(
        arguments: List<MacroValue?>?,
        count: java.util.function.ToIntFunction<MacroValue>,
    ): List<MacroValue?>? = arguments?.map { argument ->
        if (argument != null && argument.type() == MacroValue.Type.ITEM_COUNT && !argument.variableReference()) {
            MacroValue.literal(MacroValue.Type.NUMBER, count.applyAsInt(argument).toString())
        } else argument
    }

    private val PLAIN_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")

    /** DATA-2: plain decimal text only; rejects NaN, Infinity, hex floats and Java type suffixes like "5d". */
    @JvmStatic
    fun parsePlainNumber(text: String?): Double? {
        val clean = text?.trim() ?: return null
        if (!PLAIN_NUMBER.matches(clean)) return null
        return clean.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * DATA-1: when either side is a number the other side is read as a number too, so the text "10" is
     * greater than the number 9 and the text "5" equals the number 5. Two texts still compare as text.
     */
    @JvmStatic
    fun compareValues(left: Any?, right: Any?): Int {
        if (left is Number && right is Number) return java.lang.Double.compare(left.toDouble(), right.toDouble())
        if (left is Boolean && right is Boolean) return java.lang.Boolean.compare(left, right)
        if (left is Number || right is Number) {
            val l = if (left is Number) left.toDouble() else parsePlainNumber(left?.toString())
            val r = if (right is Number) right.toDouble() else parsePlainNumber(right?.toString())
            if (l != null && r != null) return java.lang.Double.compare(l, r)
        }
        return java.lang.String.valueOf(left).compareTo(java.lang.String.valueOf(right))
    }

    @JvmStatic
    fun missingItemEndsUntil(clickItemStep: Boolean, stopConditionTrue: Boolean): Boolean =
        clickItemStep && stopConditionTrue

    @JvmStatic
    fun contextEnded(systemEnabled: Boolean, hasLevel: Boolean, hasPlayer: Boolean, waitingForWorldSwitch: Boolean): Boolean =
        !systemEnabled || !hasLevel || (!hasPlayer && !waitingForWorldSwitch)

    @JvmStatic
    fun itemNameMatches(actual: String?, wanted: String?, partial: Boolean): Boolean {
        val name = normalize(actual)
        val needle = normalize(wanted)
        if (name.isEmpty() || needle.isEmpty()) return false
        return if (partial) name.contains(needle) else name == needle
    }

    /** Comma-separated names are alternatives; an item matches when any non-empty name matches. */
    @JvmStatic
    fun itemNameMatchesAny(actual: String?, wanted: String?, partial: Boolean): Boolean {
        if (wanted == null || MacroJavaStrings.isBlank(wanted)) return false
        return wanted.split(',')
            .asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .any { itemNameMatches(actual, it, partial) }
    }

    @JvmStatic
    fun randomDelay(minMillis: Int, maxMillis: Int, random: RandomGenerator): Int {
        val min = maxOf(0, minMillis)
        val max = maxOf(min, maxMillis)
        return if (min == max) min else random.nextInt(min, max + 1)
    }

    private fun normalize(value: String?): String = MacroJavaStrings.trim(value)?.lowercase(Locale.ROOT) ?: ""
}
