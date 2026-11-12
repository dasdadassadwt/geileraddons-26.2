package geiler.addons.client.module

enum class Category(private val label: String) {
    F7("Dungeons"),
    ENCHANTING("Enchanting"),
    SLAYER("Slayer"),
    HUNTING("Hunting"),
    FARMING("Farming"),
    FORAGING("Foraging"),
    MISCELLANEOUS("Miscellaneous"),
    VISUAL("Visual"),
    DEV("Dev");

    fun displayName(): String = label
}
