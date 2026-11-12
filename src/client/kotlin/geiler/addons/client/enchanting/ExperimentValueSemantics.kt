package geiler.addons.client.enchanting

/** Value semantics shared by Kotlin replacements for normalized Java records. */
internal object ExperimentValueSemantics {
    fun recordHashCode(vararg components: Any?): Int {
        var result = 0
        for (component in components) result = 31 * result + (component?.hashCode() ?: 0)
        return result
    }

    fun recordString(recordName: String, vararg components: Pair<String, Any?>): String =
        recordName + components.joinToString(prefix = "[", postfix = "]") { (name, value) ->
            "$name=$value"
        }
}
