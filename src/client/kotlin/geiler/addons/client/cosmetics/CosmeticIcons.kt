package geiler.addons.client.cosmetics

/** How the Cosmetics menu frames each style's card picture (pure; the screen uploads the pixels). */
object CosmeticIcons {
    /** A 3/4 view from behind and a little above, so wings and tails read. */
    const val YAW = 2.45f
    const val PITCH = -0.3f

    /** Head pieces and auras face the viewer (cat ears show their pink inside); the rest is seen from behind. */
    @JvmStatic
    fun yaw(style: CosmeticStyle): Float = when (style.slot) {
        CosmeticSlot.HEAD, CosmeticSlot.HALO, CosmeticSlot.AURA, CosmeticSlot.HAT, CosmeticSlot.FACE -> 0.6f
        CosmeticSlot.PET -> 0.45f
        CosmeticSlot.SKIRT -> if (style == CosmeticStyle.MAID_SKIRT) YAW else 0.6f
        else -> YAW
    }

    /** Head pieces are shown on a head, everything else on the whole stand-in player. */
    @JvmStatic
    fun stand(style: CosmeticStyle): CosmeticIconRenderer.Stand = when (style.slot) {
        CosmeticSlot.HEAD, CosmeticSlot.HALO, CosmeticSlot.HAT, CosmeticSlot.FACE -> CosmeticIconRenderer.Stand.HEAD
        CosmeticSlot.BACK -> CosmeticIconRenderer.Stand.TORSO
        CosmeticSlot.PET -> CosmeticIconRenderer.Stand.NONE
        else -> CosmeticIconRenderer.Stand.FULL
    }

    /** The card picture of [piece] at [seconds] of its idle animation. */
    @JvmStatic
    fun pixels(piece: CosmeticPiece, size: Int, seconds: Float): IntArray = CosmeticIconRenderer.render(
        CosmeticRig.place(CosmeticLoadout.of(listOf(piece)), CosmeticRig.Motion.standing(seconds, 5)),
        null, stand(piece.style), size, yaw(piece.style), PITCH,
    )
}
