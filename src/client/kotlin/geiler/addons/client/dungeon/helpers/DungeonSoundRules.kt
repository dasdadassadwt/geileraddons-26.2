package geiler.addons.client.dungeon.helpers

import java.util.Locale
import kotlin.jvm.JvmRecord

/** Classification, independent mute decisions, and exact Creeper Beams hit replacement gating. */
class DungeonSoundRules private constructor() {
    enum class Kind { CREEPER_FUSE, CREEPER_HURT, CREEPER_DEATH, EXPLOSION, OTHER }

    @JvmRecord
    data class Decision(
        val kind: Kind?,
        val playDing: Boolean,
        val cancelOriginal: Boolean,
    ) {
        override fun toString(): String =
            "Decision[kind=$kind, playDing=$playDing, cancelOriginal=$cancelOriginal]"
    }

    companion object {
        const val WEIRDOS_CORRECT_CLUE_SOUND = "minecraft:entity.firework_rocket.large_blast"
        const val WEIRDOS_CORRECT_CLUE_VOLUME = 2.0f
        const val WEIRDOS_CORRECT_CLUE_PITCH = 1.0f

        private const val CREEPER_BEAM_HIT_SOUND = "minecraft:block.glass.hit"

        @JvmStatic
        fun classify(soundId: String?): DungeonSoundRules.Kind {
            if (soundId.isNullOrBlank()) return DungeonSoundRules.Kind.OTHER
            val id = soundId.lowercase(Locale.ROOT)
            if (id.endsWith("entity.creeper.primed") || id.endsWith("entity.creeper.fuse")) {
                return DungeonSoundRules.Kind.CREEPER_FUSE
            }
            if (id.endsWith("entity.creeper.hurt")) return DungeonSoundRules.Kind.CREEPER_HURT
            if (id.endsWith("entity.creeper.death")) return DungeonSoundRules.Kind.CREEPER_DEATH
            if (id.contains("explosion") || id.endsWith("entity.generic.explode")) {
                return DungeonSoundRules.Kind.EXPLOSION
            }
            return DungeonSoundRules.Kind.OTHER
        }

        @JvmStatic
        fun decide(
            soundId: String?,
            creeperDing: Boolean,
            muteCreeperHurt: Boolean,
            muteExplosions: Boolean,
        ): DungeonSoundRules.Decision {
            val kind = classify(soundId)
            return DungeonSoundRules.Decision(
                kind,
                kind == DungeonSoundRules.Kind.CREEPER_FUSE && creeperDing,
                kind == DungeonSoundRules.Kind.CREEPER_HURT && muteCreeperHurt
                    || kind == DungeonSoundRules.Kind.EXPLOSION && muteExplosions,
            )
        }

        @JvmStatic
        fun isCreeperBeamHit(soundId: String?): Boolean = CREEPER_BEAM_HIT_SOUND == soundId

        @JvmStatic
        fun shouldReplaceCreeperBeamHit(
            soundId: String?,
            enabled: Boolean,
            activePuzzle: Boolean,
            matchedEndpoint: Boolean,
        ): Boolean = enabled && activePuzzle && matchedEndpoint && isCreeperBeamHit(soundId)
    }
}
