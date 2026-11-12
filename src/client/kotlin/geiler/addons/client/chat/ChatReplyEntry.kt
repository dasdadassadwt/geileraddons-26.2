package geiler.addons.client.chat

/** One command entry, explicitly either an incoming text reply or a locally invoked server action. */
class ChatReplyEntry(
    id: String?,
    command: String?,
    mode: Mode?,
    response: String?,
    actionTemplate: String?,
    folderId: String?,
    enabled: Boolean,
) {
    enum class Mode { TEXT_REPLY, LOCAL_ACTION }

    private val idValue = ChatReplyEntryLogic.normalizeId(id)
    private val commandValue = ChatReplyEntryLogic.normalizeCommand(command)
    private val modeValue = ChatReplyEntryLogic.normalizeMode(mode)
    private val responseValue = ChatReplyEntryLogic.normalizeResponse(response, modeValue)
    private val actionTemplateValue = ChatReplyEntryLogic.normalizeActionTemplate(actionTemplate, modeValue)
    private val folderIdValue = ChatReplyEntryLogic.normalizeFolderId(folderId)
    private val enabledValue = enabled

    /** New entries default to plain replies. */
    constructor(command: String?, response: String?) : this(
        ChatReplyEntryLogic.newId(), command, Mode.TEXT_REPLY, response, "", null, true,
    )

    /** Compatibility for the earlier local configuration editor: a supplied template selects action mode. */
    constructor(command: String?, response: String?, actionTemplate: String?) : this(
        ChatReplyEntryLogic.newId(), command, ChatReplyEntryLogic.modeForActionTemplate(actionTemplate),
        response, actionTemplate, null, true,
    )

    /** Compatibility constructor for saved text-reply rows. */
    constructor(id: String?, command: String?, response: String?, folderId: String?, enabled: Boolean) : this(
        id, command, Mode.TEXT_REPLY, response, "", folderId, enabled,
    )

    /** Compatibility for callers that passed an action template as the final argument. */
    constructor(
        id: String?,
        command: String?,
        response: String?,
        folderId: String?,
        enabled: Boolean,
        actionTemplate: String?,
    ) : this(
        id, command, ChatReplyEntryLogic.modeForActionTemplate(actionTemplate), response, actionTemplate, folderId,
        enabled,
    )

    fun id(): String = idValue
    fun command(): String = commandValue
    fun mode(): Mode = modeValue
    fun response(): String = responseValue
    fun actionTemplate(): String = actionTemplateValue
    fun folderId(): String? = folderIdValue
    fun enabled(): Boolean = enabledValue

    fun isValid(): Boolean =
        ChatReplyEntryLogic.isValid(idValue, commandValue, modeValue, responseValue, actionTemplateValue)

    override fun equals(other: Any?): Boolean = other is ChatReplyEntry &&
        idValue == other.idValue && commandValue == other.commandValue && modeValue == other.modeValue &&
        responseValue == other.responseValue && actionTemplateValue == other.actionTemplateValue &&
        folderIdValue == other.folderIdValue && enabledValue == other.enabledValue

    override fun hashCode(): Int {
        var result = idValue.hashCode()
        result = 31 * result + commandValue.hashCode()
        result = 31 * result + modeValue.hashCode()
        result = 31 * result + responseValue.hashCode()
        result = 31 * result + actionTemplateValue.hashCode()
        result = 31 * result + (folderIdValue?.hashCode() ?: 0)
        result = 31 * result + java.lang.Boolean.hashCode(enabledValue)
        return result
    }

    override fun toString(): String =
        "ChatReplyEntry[id=$idValue, command=$commandValue, mode=$modeValue, response=$responseValue, " +
            "actionTemplate=$actionTemplateValue, folderId=$folderIdValue, enabled=$enabledValue]"
}
