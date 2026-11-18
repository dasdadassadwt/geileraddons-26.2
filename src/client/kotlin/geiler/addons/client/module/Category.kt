package geiler.addons.client.module

/**
 * The two halves of the Click GUI. Normal holds the original modules; Macros holds the ported macro modules, which are
 * kept in their own categories, view state and JSON file so the two never mix.
 */
enum class Workspace(private val switchLabel: String) {
    NORMAL("Macros"),
    MACROS("Modules");

    fun otherWorkspace(): Workspace = if (this == NORMAL) MACROS else NORMAL

    /** The rail button that leaves this workspace is named after the workspace it leads to. */
    fun switchLabel(): String = switchLabel

    companion object {
        /** Unknown or missing persisted names fall back to the original workspace. */
        @JvmStatic
        fun fromName(name: String?): Workspace = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

enum class Category(private val label: String, val workspace: Workspace = Workspace.NORMAL) {
    F7("Dungeons"),
    ENCHANTING("Enchanting"),
    SLAYER("Slayer"),
    HUNTING("Hunting"),
    FISHING("Fishing"),
    FARMING("Farming"),
    FORAGING("Foraging"),
    MINING("Mining"),
    KUUDRA("Kuudra"),
    MISCELLANEOUS("Miscellaneous"),
    VISUAL("Visual"),
    DEV("Dev"),
    ENGINE("Engine", Workspace.MACROS),
    AREA_KILLER("Area Killer", Workspace.MACROS),
    SIM("Sim", Workspace.MACROS);

    fun displayName(): String = label

    companion object {
        /** The categories of one workspace, in rail order. */
        @JvmStatic
        fun of(workspace: Workspace): List<Category> = values().filter { it.workspace == workspace }
    }
}
