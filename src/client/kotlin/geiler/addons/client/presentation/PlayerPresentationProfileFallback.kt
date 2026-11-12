package geiler.addons.client.presentation

/** Chooses only already-validated local data, keeping a usable profile available during fetch failures. */
object PlayerPresentationProfileFallback {
    @JvmStatic
    fun local(cached: PlayerPresentationProfiles?, bundled: PlayerPresentationProfiles?): PlayerPresentationProfiles =
        cached ?: bundled ?: PlayerPresentationProfiles.empty()

    @JvmStatic
    fun hosted(refreshed: PlayerPresentationProfiles?, previous: PlayerPresentationProfiles?): PlayerPresentationProfiles =
        refreshed ?: previous ?: PlayerPresentationProfiles.empty()
}
