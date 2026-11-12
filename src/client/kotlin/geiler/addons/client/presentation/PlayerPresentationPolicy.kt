package geiler.addons.client.presentation

/** Pure migration and size-resolution rules shared by the split presentation modules. */
object PlayerPresentationPolicy {
    @JvmStatic
    fun splitModuleEnable(savedTargetValue: Boolean?, legacyCombinedValue: Boolean?): Boolean? = savedTargetValue

    /** Own local size applies only to the local player; other players can use hosted sizes. */
    @JvmStatic
    fun sizeMultiplier(
        requestedName: String?,
        ownName: String?,
        ownSizeEnabled: Boolean,
        ownSize: Float,
        hostedSizesEnabled: Boolean,
        hostedSize: Float?,
    ): Float {
        if (requestedName == null) return 1.0f
        if (ownSizeEnabled && ownName != null && ownName.equals(requestedName, ignoreCase = true) && validSize(ownSize)) {
            return ownSize
        }
        if (hostedSizesEnabled && hostedSize != null && validSize(hostedSize)) return hostedSize
        return 1.0f
    }

    private fun validSize(size: Float): Boolean = size.isFinite() && size >= 0.1f && size <= 4.0f
}
