package geiler.addons.client.fishing

/**
 * Hook display rules, after Skyblocker's FishingHookDisplayHelper: Hypixel puts an armor stand on the hook whose name
 * counts down in seconds (`2.4`) once a fish is on its way and reads `!!!` when it bites. Pure so offline checks cover it.
 */
object FishingHookRules {
    /** How far from the bobber the stand may sit; the stand tracks the hook, so this only absorbs a tick of lag. */
    const val MAX_DISTANCE = 1.5

    private val COUNTDOWN = Regex("""\d.\d""")
    private const val BITE = "!!!"

    /** A countdown such as `2.4`, or the bite marker. Anything else (damage tags, pet names) is not the hook text. */
    @JvmStatic
    fun isHookText(plain: String?): Boolean {
        val text = plain?.trim() ?: return false
        return text.isNotEmpty() && (COUNTDOWN.matches(text) || text.contains(BITE))
    }

    /** The fish has bitten: reel in now. */
    @JvmStatic
    fun isBite(plain: String?): Boolean = plain?.contains(BITE) == true

    @JvmStatic
    fun withinHook(distance: Double): Boolean = distance <= MAX_DISTANCE
}
