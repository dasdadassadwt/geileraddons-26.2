package geiler.addons.client.tree

import java.util.regex.Pattern

/** Matches log-break announcements, a distinct event that must not be deduplicated against gifts. */
object TreeFelled {
    private val FELLED = Pattern.compile(
        "^(?:TIMBER|PETALFALL)!\\s+.+?\\s+felled the entire\\s+.+\\s+Tree!$",
        Pattern.CASE_INSENSITIVE,
    )

    @JvmStatic
    fun matches(message: String): Boolean = FELLED.matcher(ChatText.plain(message)).find()
}
