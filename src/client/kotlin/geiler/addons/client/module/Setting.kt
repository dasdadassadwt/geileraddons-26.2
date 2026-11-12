package geiler.addons.client.module

/** A row that can appear in a module's settings panel. */
sealed interface Setting {
    fun name(): String

    /** Label shown in the panel; name remains the stable persistence key. */
    fun displayName(): String = name()

    /** Whether this row belongs to the optional diagnostic surface. */
    fun isDebugOnly(): Boolean = false
}
