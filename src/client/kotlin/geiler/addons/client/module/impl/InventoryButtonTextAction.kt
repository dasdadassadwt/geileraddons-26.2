package geiler.addons.client.module.impl

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Parses a user-clicked inventory action without depending on Minecraft connection state. */
object InventoryButtonTextAction {
    enum class Kind { COMMAND, CHAT }

    @JvmRecord
    data class Dispatch(val kind: Kind?, val payload: String?)

    @JvmStatic
    fun parse(raw: String?): Dispatch? {
        if (raw == null) return null
        val value = javaTrim(raw)
        if (value.isEmpty()) return null
        if (value.startsWith("/")) {
            val command = javaTrim(value.substring(1))
            return if (command.isEmpty()) null else Dispatch(Kind.COMMAND, command)
        }
        return Dispatch(Kind.CHAT, value)
    }

    private fun javaTrim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start] <= ' ') start++
        while (start < end && value[end - 1] <= ' ') end--
        return value.substring(start, end)
    }
}
