package geiler.addons.client.macro

/** Formats Kotlin value models with the compact component spelling used by their former records. */
internal fun macroRecordString(typeName: String, vararg components: Pair<String, Any?>): String = buildString {
    append(typeName).append('[')
    components.forEachIndexed { index, (name, value) ->
        if (index > 0) append(", ")
        append(name).append('=').append(value)
    }
    append(']')
}

/** Keeps unvalidated nullable fields from the former Java platform API nullable at runtime. */
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> kotlinPlatformValue(value: T?): T = value as T
