package geiler.addons.client.chat

import geiler.addons.client.utility.JavaStringSemantics
import java.util.regex.Pattern

/** Java String whitespace behavior retained where the frozen Java implementation used it. */
internal object ChatStringSemantics {
    fun trim(value: String): String = JavaStringSemantics.trim(value)

    fun strip(value: String): String = JavaStringSemantics.strip(value)

    fun stripLeading(value: String): String = JavaStringSemantics.stripLeading(value)

    fun isBlank(value: String): Boolean = JavaStringSemantics.isBlank(value)

    /** Minecraft player-name rule: 1-16 ASCII letters, digits or underscores (whole string). */
    const val MINECRAFT_USERNAME_REGEX = "[A-Za-z0-9_]{1,16}"

    /** Precompiled [MINECRAFT_USERNAME_REGEX]; use with `matcher(...).matches()` or `find()` as the caller needs. */
    val MINECRAFT_USERNAME: Pattern = Pattern.compile(MINECRAFT_USERNAME_REGEX)

    fun isMinecraftUsername(value: String?): Boolean = value != null && MINECRAFT_USERNAME.matcher(value).matches()
}
