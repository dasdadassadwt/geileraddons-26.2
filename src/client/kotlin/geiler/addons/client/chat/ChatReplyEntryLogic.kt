package geiler.addons.client.chat

import java.util.Locale
import java.util.UUID

/** Shared normalization and validation for saved reply rows. */
object ChatReplyEntryLogic {
    @JvmStatic
    fun newId(): String = UUID.randomUUID().toString()

    @JvmStatic
    fun modeForActionTemplate(actionTemplate: String?): ChatReplyEntry.Mode =
        if (actionTemplate == null || actionTemplate.javaIsBlank()) {
            ChatReplyEntry.Mode.TEXT_REPLY
        } else {
            ChatReplyEntry.Mode.LOCAL_ACTION
        }

    @JvmStatic
    fun normalizeId(id: String?): String =
        if (id == null || id.javaIsBlank()) newId() else id.javaTrim()

    @JvmStatic
    fun normalizeCommand(command: String?): String =
        command?.javaTrim()?.lowercase(Locale.ROOT) ?: ""

    @JvmStatic
    fun normalizeMode(mode: ChatReplyEntry.Mode?): ChatReplyEntry.Mode =
        mode ?: ChatReplyEntry.Mode.TEXT_REPLY

    @JvmStatic
    fun normalizeResponse(response: String?, mode: ChatReplyEntry.Mode?): String {
        if (response == null) return ""
        return if (mode == ChatReplyEntry.Mode.TEXT_REPLY) response.javaTrim() else response
    }

    @JvmStatic
    fun normalizeActionTemplate(actionTemplate: String?, mode: ChatReplyEntry.Mode?): String {
        if (actionTemplate == null) return ""
        return if (mode == ChatReplyEntry.Mode.LOCAL_ACTION) {
            ChatActionTemplateRules.normalize(actionTemplate)
        } else {
            actionTemplate
        }
    }

    @JvmStatic
    fun normalizeFolderId(folderId: String?): String? =
        if (folderId == null || folderId.javaIsBlank()) null else folderId.javaTrim()

    @JvmStatic
    fun isValid(
        id: String?,
        command: String?,
        mode: ChatReplyEntry.Mode?,
        response: String?,
        actionTemplate: String?,
    ): Boolean {
        val idValue = id ?: ""
        val commandValue = command ?: ""
        val modeValue = mode ?: ChatReplyEntry.Mode.TEXT_REPLY
        val responseValue = response ?: ""
        val actionTemplateValue = actionTemplate ?: ""

        val nameValid = !idValue.javaIsBlank() && idValue.length <= 64 &&
            ChatReplyConfigRules.normalizeCommand(commandValue).isNotEmpty()
        if (!nameValid) return false

        if (modeValue == ChatReplyEntry.Mode.TEXT_REPLY) {
            return !ChatReplyConfigRules.isReservedCommand(commandValue) &&
                actionTemplateValue.length <= ChatActionTemplateRules.MAX_TEMPLATE_LENGTH &&
                ChatReplyConfigRules.validResponse(responseValue)
        }

        return (!ChatReplyConfigRules.isReservedCommand(commandValue) ||
            ChatReplyConfigRules.isPartyTransferName(commandValue)) &&
            responseValue.length <= ChatReplyConfigRules.MAX_RESPONSE_LENGTH &&
            ChatActionTemplateRules.isValid(actionTemplateValue)
    }

    /** Matches Java String.trim(): remove only code points at or below U+0020. */
    private fun String.javaTrim(): String = trim { it.code <= 0x20 }

    /** Matches Java String.isBlank(): only Character.isWhitespace characters are blank. */
    private fun String.javaIsBlank(): Boolean = all { Character.isWhitespace(it) }
}
