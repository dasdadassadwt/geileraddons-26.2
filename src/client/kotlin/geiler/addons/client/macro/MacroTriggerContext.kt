package geiler.addons.client.macro

/** Where a macro hotkey is allowed to start. */
enum class MacroTriggerContext(
    private val labelText: String,
    private val descriptionText: String,
) {
    WORLD_ONLY("World only (no screen)", "Starts only while no Minecraft screen is open."),
    CONTAINER_ONLY("Container/inventory screens", "Starts only while an inventory or container screen is open."),
    WORLD_AND_CONTAINER("World or container", "Starts in the world or in an inventory/container screen."),
    ANY_NON_TEXT_SCREEN("Any non-text screen", "Starts in ordinary menus too, but never in chat or an editor screen."),
    ;

    fun label(): String = labelText
    fun description(): String = descriptionText
}
