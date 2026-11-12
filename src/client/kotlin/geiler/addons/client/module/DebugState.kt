package geiler.addons.client.module

/** Runtime gate for optional diagnostic settings. State is changed by the Dev Debug module. */
object DebugState {
    @Volatile
    private var debugEnabled = false

    @JvmStatic
    fun enabled(): Boolean = debugEnabled

    /** Called by DebugModule when its persisted module switch changes. */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }
}
