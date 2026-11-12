package geiler.addons.client.location

/**
 * Named gate for Tiki features.
 *
 * The official shared Hypixel Mod API is the sole location authority. In particular, a missing
 * handshake, a null or unknown server type, and an unknown island mode all fail closed instead of
 * allowing terrain guesses or stale state to activate the module.
 */
class TorrhusPresence private constructor() {
    companion object {
        /**
         * Kept as a lifecycle no-op for the existing client tick wiring. Location state is
         * event-driven now, so polling terrain here would reintroduce the false-positive path this
         * gate prevents.
         */
        @JvmStatic
        fun tick() {}

        /** Whether the latest location event explicitly names Torrhus Canyon. */
        @JvmStatic
        fun isPresent(): Boolean = HypixelModApi.currentIsland() == Island.TORRHUS_CANYON

        /** Why the Tiki module is idle, or null when the API names Torrhus Canyon. */
        @JvmStatic
        fun reason(): String? = HypixelModApi.reasonNotOn(Island.TORRHUS_CANYON)
    }
}
