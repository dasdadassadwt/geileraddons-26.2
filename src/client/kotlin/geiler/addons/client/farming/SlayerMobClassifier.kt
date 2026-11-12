package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Conservative names for common Slayer bosses and minibosses, stripped from their mob labels. */
class SlayerMobClassifier private constructor() {
    private data class NameKind(val name: String, val kind: Kind)

    enum class Kind { OTHER, BOSS, MINIBOSS }

    companion object {
        private val NAMES = listOf(
            NameKind("riftstalker bloodfiend", Kind.BOSS),
            NameKind("tarantula broodfather", Kind.BOSS),
            NameKind("inferno demonlord", Kind.BOSS),
            NameKind("voidgloom seraph", Kind.BOSS),
            NameKind("sven packmaster", Kind.BOSS),
            NameKind("revenant horror", Kind.BOSS),
            NameKind("atoned horror", Kind.BOSS),
            NameKind("millenia-aged blaze", Kind.MINIBOSS),
            NameKind("revenant sycophant", Kind.MINIBOSS),
            NameKind("deformed revenant", Kind.MINIBOSS),
            NameKind("atoned champion", Kind.MINIBOSS),
            NameKind("tarantula vermin", Kind.MINIBOSS),
            NameKind("tarantula beast", Kind.MINIBOSS),
            NameKind("voidcrazed maniac", Kind.MINIBOSS),
            NameKind("voidling devotee", Kind.MINIBOSS),
            NameKind("voidling radical", Kind.MINIBOSS),
            NameKind("pack enforcer", Kind.MINIBOSS),
            NameKind("sven follower", Kind.MINIBOSS),
            NameKind("sven alpha", Kind.MINIBOSS),
            NameKind("burning soul", Kind.MINIBOSS),
            NameKind("flare", Kind.MINIBOSS),
        )

        @JvmStatic
        fun classify(rawName: String): Kind {
            val name = ChatText.stripForMatch(rawName).lowercase(Locale.ROOT)
            for (entry in NAMES) {
                if (name.contains(entry.name)) return entry.kind
            }
            return Kind.OTHER
        }
    }
}
