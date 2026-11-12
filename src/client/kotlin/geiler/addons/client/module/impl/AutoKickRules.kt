package geiler.addons.client.module.impl

/** Pure requirement predicates kept separate so their boundary behavior can be checked offline. */
object AutoKickRules {
    enum class GearOutcome { PASS, FAIL, UNKNOWN }

    /** A configured PB is an upper bound; zero disables the check. */
    @JvmStatic
    fun personalBestPasses(fastestSeconds: Long, limitSeconds: Long): Boolean =
        limitSeconds <= 0 || fastestSeconds > 0 && fastestSeconds <= limitSeconds

    /** Unknown or incomplete profile data can delay evaluation, but can never authorize a kick. */
    @JvmStatic
    fun canApplyAction(hasUnknownOrIncompleteData: Boolean, hasKnownFailure: Boolean): Boolean =
        !hasUnknownOrIncompleteData && hasKnownFailure

    /** Keeps unavailable gear evidence distinct from confirmed absence in a complete inventory. */
    @JvmStatic
    fun gearOutcome(enabled: Boolean, dataAvailable: Boolean, present: Boolean): GearOutcome = when {
        !enabled -> GearOutcome.PASS
        !dataAvailable -> GearOutcome.UNKNOWN
        present -> GearOutcome.PASS
        else -> GearOutcome.FAIL
    }

    /** Party classes describe the whole roster, so refetching one player's profile cannot resolve them. */
    @JvmStatic
    fun profileRefreshableUnknowns(unknowns: List<String?>?): List<String> =
        unknowns.orEmpty().filterNotNull().filter { it != "Party classes" }.distinct()

    @JvmStatic
    fun needsProfileRefresh(profileIncomplete: Boolean, unknowns: List<String?>?): Boolean =
        profileIncomplete || profileRefreshableUnknowns(unknowns).isNotEmpty()

    /** Parses a PB limit as m:ss or as legacy whole seconds. Invalid values disable the check. */
    @JvmStatic
    fun parsePersonalBestLimitSeconds(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        val normalized = trimLikeJavaString(value)
        return try {
            val colon = normalized.indexOf(':')
            if (colon < 0) return normalized.toInt().coerceAtLeast(0)
            if (colon != normalized.lastIndexOf(':')) return 0
            val minutes = trimLikeJavaString(normalized.substring(0, colon)).toLong()
            val seconds = trimLikeJavaString(normalized.substring(colon + 1)).toInt()
            if (minutes < 0 || minutes > Int.MAX_VALUE / 60L || seconds !in 0..59) return 0
            val total = minutes * 60L + seconds
            if (total > Int.MAX_VALUE) 0 else total.toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }

    /** Matches java.lang.String.trim() so the Kotlin port preserves the Java parser's input semantics. */
    private fun trimLikeJavaString(value: String): String = value.trim { it <= ' ' }
}
