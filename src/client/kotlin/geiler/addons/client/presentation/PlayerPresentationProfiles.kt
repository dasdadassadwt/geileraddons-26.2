package geiler.addons.client.presentation

import java.io.IOException

/** Immutable, validated player labels, client-only sizes, and text styles parsed from the hosted profile. */
class PlayerPresentationProfiles private constructor(players: Map<String, Entry>) {
    private val players: Map<String, Entry> = java.util.Map.copyOf(players)
    private val animatedNameStyles = PlayerPresentationProfilesParser.hasAnimatedNameStyles(this.players)

    fun find(playerName: String?): Entry? = PlayerPresentationProfilesParser.find(players, playerName)

    fun entries(): Map<String, Entry> = players

    fun hasAnimatedNameStyles(): Boolean = animatedNameStyles

    /** Record-shaped Java value API retained for existing Java callers. */
    class Entry(
        private val playerNameValue: String?,
        private val aliasValue: String?,
        private val effectMetadataValue: String?,
        private val sizeXValue: Float?,
        private val sizeYValue: Float?,
        private val sizeZValue: Float?,
        private val colorValue: Int?,
        gradient: List<Int>?,
        private val animationValue: Animation?,
    ) {
        private val gradientValue: List<Int> = gradient?.let { java.util.List.copyOf(it) } ?: java.util.List.of()

        /** Legacy scalar-size constructor; older profile data maps equally across all three axes. */
        constructor(
            playerName: String?,
            alias: String?,
            effectMetadata: String?,
            size: Float?,
            color: Int?,
            gradient: List<Int>?,
            animation: Animation?,
        ) : this(playerName, alias, effectMetadata, size, size, size, color, gradient, animation)

        fun playerName(): String? = playerNameValue

        fun alias(): String? = aliasValue

        fun effectMetadata(): String? = effectMetadataValue

        fun sizeX(): Float? = sizeXValue

        fun sizeY(): Float? = sizeYValue

        fun sizeZ(): Float? = sizeZValue

        fun color(): Int? = colorValue

        fun gradient(): List<Int> = gradientValue

        fun animation(): Animation? = animationValue

        /** Compatibility accessor for older callers that used one uniform size multiplier. */
        fun size(): Float? =
            if (sizeXValue != null && sizeXValue == sizeYValue && sizeXValue == sizeZValue) sizeXValue else null

        fun hasSizeOverride(): Boolean = sizeXValue != null && sizeYValue != null && sizeZValue != null

        fun hasNameStyle(): Boolean = colorValue != null || gradientValue.isNotEmpty()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return playerNameValue == other.playerNameValue &&
                aliasValue == other.aliasValue &&
                effectMetadataValue == other.effectMetadataValue &&
                sizeXValue == other.sizeXValue &&
                sizeYValue == other.sizeYValue &&
                sizeZValue == other.sizeZValue &&
                colorValue == other.colorValue &&
                gradientValue == other.gradientValue &&
                animationValue == other.animationValue
        }

        override fun hashCode(): Int {
            var result = playerNameValue?.hashCode() ?: 0
            result = 31 * result + (aliasValue?.hashCode() ?: 0)
            result = 31 * result + (effectMetadataValue?.hashCode() ?: 0)
            result = 31 * result + (sizeXValue?.hashCode() ?: 0)
            result = 31 * result + (sizeYValue?.hashCode() ?: 0)
            result = 31 * result + (sizeZValue?.hashCode() ?: 0)
            result = 31 * result + (colorValue?.hashCode() ?: 0)
            result = 31 * result + gradientValue.hashCode()
            result = 31 * result + (animationValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Entry[playerName=$playerNameValue, alias=$aliasValue, effectMetadata=$effectMetadataValue, " +
                "sizeX=$sizeXValue, sizeY=$sizeYValue, sizeZ=$sizeZValue, color=$colorValue, " +
                "gradient=$gradientValue, animation=$animationValue]"
    }

    @JvmRecord
    data class Animation(val cyclesPerSecond: Float) {
        override fun toString(): String = "Animation[cyclesPerSecond=$cyclesPerSecond]"
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_JSON_BYTES = 256 * 1024
        const val MAX_JSON_CHARS = 256 * 1024
        const val MAX_JSON_DEPTH = 8
        const val MAX_JSON_TOKENS = 32_768
        const val MAX_JSON_NODES = 16_384
        const val MAX_PLAYERS = 512
        const val MAX_EFFECT_METADATA_CHARS = 2 * 1024
        const val MIN_GRADIENT_STOPS = 2
        const val MAX_GRADIENT_STOPS = 8
        const val MIN_ANIMATION_SPEED = 0.05
        const val MAX_ANIMATION_SPEED = 2.0

        private val EMPTY = PlayerPresentationProfiles(java.util.Map.of())

        @JvmStatic
        fun empty(): PlayerPresentationProfiles = EMPTY

        /** Decodes a bounded UTF-8 profile, rejecting malformed bytes rather than replacing them. */
        @JvmStatic
        @Throws(IOException::class)
        fun decodeUtf8(bytes: ByteArray?): String = PlayerPresentationProfilesParser.decodeUtf8(bytes)

        @JvmStatic
        @Throws(IOException::class)
        fun parseUtf8(bytes: ByteArray?): PlayerPresentationProfiles = parse(decodeUtf8(bytes))

        /** Preflights the whole document with streaming limits before Gson allocates a tree. */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(json: String?): PlayerPresentationProfiles {
            val parsed = PlayerPresentationProfilesParser.parse(json)
            return if (parsed.isEmpty()) EMPTY else PlayerPresentationProfiles(parsed)
        }
    }
}
