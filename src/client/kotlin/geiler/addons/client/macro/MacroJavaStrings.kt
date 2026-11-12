package geiler.addons.client.macro

import geiler.addons.client.utility.JavaStringSemantics

/** Java String whitespace operations used by the frozen macro model and runtime. */
internal object MacroJavaStrings {
    @JvmStatic
    fun trim(value: String?): String? = value?.let { JavaStringSemantics.trim(it) }

    @JvmStatic
    fun strip(value: String?): String? = value?.let { JavaStringSemantics.strip(it) }

    @JvmStatic
    fun stripLeading(value: String?): String? = value?.let { JavaStringSemantics.stripLeading(it) }

    @JvmStatic
    fun isBlank(value: String?): Boolean = value == null || JavaStringSemantics.isBlank(value)
}
