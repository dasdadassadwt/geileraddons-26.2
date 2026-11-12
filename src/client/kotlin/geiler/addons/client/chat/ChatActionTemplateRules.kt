package geiler.addons.client.chat

import java.util.Optional
import java.util.regex.Pattern

/** Validation and expansion for one locally typed, single-command action template. */
object ChatActionTemplateRules {
    const val MAX_TEMPLATE_LENGTH = 128

    private const val MAX_ARGUMENT_LENGTH = 128
    private const val PLACEHOLDER = "{args}"
    private val ARGUMENTS = Pattern.compile("[A-Za-z0-9_.,:+~\\-]+(?:\\s+[A-Za-z0-9_.,:+~\\-]+)*")

    @JvmStatic
    fun normalize(template: String?): String = template?.trim { it <= ' ' } ?: ""

    @JvmStatic
    fun isValid(template: String?): Boolean {
        val value = normalize(template)
        if (value.isEmpty() || value.length > MAX_TEMPLATE_LENGTH || !value.startsWith("/")
            || value.startsWith("//")) return false
        val placeholder = value.indexOf(PLACEHOLDER)
        val afterPlaceholder = placeholder + PLACEHOLDER.length
        if (placeholder < 2 || value[placeholder - 1] != ' '
            || afterPlaceholder < value.length && value[afterPlaceholder] != ' '
            || value.indexOf(PLACEHOLDER, afterPlaceholder) >= 0) return false
        if (ChatStringSemantics.isBlank(value.substring(1, placeholder))) return false
        val withoutPlaceholder = value.substring(0, placeholder) +
            value.substring(placeholder + PLACEHOLDER.length)
        if (withoutPlaceholder.indexOf('{') >= 0 || withoutPlaceholder.indexOf('}') >= 0) return false
        for (index in value.indices) {
            val current = value[index]
            val type = Character.getType(current)
            if (Character.isISOControl(current) || type == Character.LINE_SEPARATOR.toInt()
                || type == Character.PARAGRAPH_SEPARATOR.toInt() || current == '\u00a7' || current == ';' || current == '|')
                return false
            if (index > 0 && current == '/') return false
        }
        return !ChatStringSemantics.isBlank(value.substring(1))
    }

    /** Returns the slashless command expected by ClientPacketListener.sendCommand. */
    @JvmStatic
    fun expand(template: String?, arguments: String?): Optional<String> {
        val value = normalize(template)
        if (arguments == null) return Optional.empty()
        for (current in arguments) {
            val type = Character.getType(current)
            if (Character.isISOControl(current) || type == Character.LINE_SEPARATOR.toInt()
                || type == Character.PARAGRAPH_SEPARATOR.toInt() || current == '\u00a7') return Optional.empty()
        }
        val args = arguments.trim { it <= ' ' }.replace(Regex(" +"), " ")
        if (!isValid(value) || args.isEmpty() || args.length > MAX_ARGUMENT_LENGTH
            || !ARGUMENTS.matcher(args).matches()) return Optional.empty()
        val command = value.substring(1).replace(PLACEHOLDER, args)
        return if (command.length <= 256) Optional.of(command) else Optional.empty()
    }
}
