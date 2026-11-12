package geiler.addons.client.enchanting

/** Shared null-to-text defaults used by Java record boundaries. */
object ExperimentTextSupport {
    @JvmStatic
    fun emptyIfNull(value: String?): String = value ?: ""

    @JvmStatic
    fun defaultIfNull(value: String?, fallback: String): String = value ?: fallback
}
