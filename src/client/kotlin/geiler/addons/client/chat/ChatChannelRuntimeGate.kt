package geiler.addons.client.chat

/** Pure shared gate used by bounded history capture. */
object ChatChannelRuntimeGate {
    @JvmStatic
    fun shouldCapture(moduleActive: Boolean): Boolean = moduleActive

    @JvmStatic
    fun shouldSuppressVanilla(moduleActive: Boolean, windowOpen: Boolean): Boolean = false

    /** Kept for old callers; native chat is never suppressed by channel filtering. */
    @JvmStatic
    fun shouldSuppressVanilla(
        moduleActive: Boolean,
        focusedWindow: Boolean,
        hudEnabled: Boolean,
        chatFocused: Boolean,
    ): Boolean = false
}
