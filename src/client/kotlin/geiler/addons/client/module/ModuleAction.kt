package geiler.addons.client.module

/** A button row inside a module's settings panel. */
@JvmRecord
data class ModuleAction(
    val label: String,
    val description: String?,
    val onClick: Runnable,
) : Setting {
    constructor(label: String, onClick: Runnable) : this(label, null, onClick)

    override fun name(): String = label
}
