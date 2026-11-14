package geiler.addons.client.module.impl

/** Pure rules for the Animation, Double Use and Disconnect fixes. */
object GameFixRules {
    /** What a held item makes Double Use Fix suppress: a right-click on a block (dagger) or the extra rod cast. */
    enum class DisableType { NONE, DAGGER, ROD }

    /** A fishing rod always wins; otherwise a right-click ability naming Attunement marks a Blaze dagger. */
    @JvmStatic
    fun disableType(isFishingRod: Boolean, rightClickAbility: String): DisableType = when {
        isFishingRod -> DisableType.ROD
        rightClickAbility.contains("Attunement") -> DisableType.DAGGER
        else -> DisableType.NONE
    }

    /** A fix runs when its module is on and, if it is limited to SkyBlock, only while on SkyBlock. */
    @JvmStatic
    fun active(enabled: Boolean, skyblockOnly: Boolean, inSkyblock: Boolean): Boolean =
        enabled && (!skyblockOnly || inSkyblock)

    /** Removes the first entry of [items] matching [isPose] (the pose entry stops the scan); returns how many were removed. */
    @JvmStatic
    fun <T> removeFirstMatching(items: MutableList<T>, isPose: (T) -> Boolean): Int {
        val index = items.indexOfFirst(isPose)
        if (index < 0) return 0
        items.removeAt(index)
        return 1
    }
}
