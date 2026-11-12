package geiler.addons.client.presentation

import java.time.Duration

/** Single source of truth for the owner-edited profile location and bounded refresh policy. */
object PlayerPresentationProfileSource {
    const val REPOSITORY = "dasdadassadwt/geileraddons-26.2"
    const val BRANCH = "main"
    const val REPOSITORY_PATH = "src/client/resources/data/player-presentations.json"
    const val BUNDLED_RESOURCE = "/data/player-presentations.json"
    const val RAW_PROFILE_URL = "https://raw.githubusercontent.com/$REPOSITORY/$BRANCH/$REPOSITORY_PATH"

    @JvmField
    val MAX_BODY_BYTES: Int = PlayerPresentationProfiles.MAX_JSON_BYTES

    @JvmField
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)

    @JvmField
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)

    @JvmField
    val RESPONSE_DEADLINE: Duration = Duration.ofSeconds(9)

    @JvmField
    val REFRESH_INTERVAL: Duration = Duration.ofHours(6)

    @JvmField
    val FAILURE_RETRY: Duration = Duration.ofMinutes(15)
}
