package geiler.addons.client.termsim

/**
 * The network firewall of the Terminal Simulator. A simulator screen is a local chest that the server never opened,
 * so no container packet may leave the client while one is showing. The screen itself never sends anything (it
 * overrides every method that would), and this guard drops the container packets as a second line: the mixin on
 * `ClientCommonPacketListenerImpl.send` asks [blocks] before any packet is sent.
 */
object TermSimGuard {
    /** Serverbound packets that only make sense for a container the server opened. */
    private val BLOCKED = setOf(
        "ServerboundPlaceRecipePacket", "ServerboundSetCreativeModeSlotPacket", "ServerboundSelectBundleItemPacket",
        "ServerboundRenameItemPacket", "ServerboundSelectTradePacket", "ServerboundSetBeaconPacket",
        "ServerboundRecipeBookSeenRecipePacket", "ServerboundRecipeBookChangeSettingsPacket",
    )
    const val PACKAGE = "net.minecraft.network.protocol.game."

    @Volatile
    private var shown: Any? = null

    @Volatile
    var blockedCount = 0L
        private set

    /** The simulator screen that is showing now, or null. */
    @JvmStatic
    fun opened(screen: Any) {
        shown = screen
    }

    @JvmStatic
    fun closed(screen: Any) {
        if (shown === screen) shown = null
    }

    @JvmStatic
    fun active(): Boolean = shown != null

    /** Names of every blocked packet class, for checks. */
    @JvmStatic
    fun blockedNames(): Set<String> = BLOCKED

    /** True when a packet of [simpleName] must be dropped now. */
    @JvmStatic
    fun blocks(simpleName: String): Boolean = active() && isContainerPacket(simpleName)

    @JvmStatic
    fun isContainerPacket(simpleName: String): Boolean = simpleName.startsWith("ServerboundContainer") || simpleName in BLOCKED

    /** Called by the mixin for every outgoing packet; counts what it drops. */
    @JvmStatic
    fun shouldDrop(packet: Any?): Boolean {
        if (packet == null || !active()) return false
        val drop = isContainerPacket(packet.javaClass.simpleName)
        if (drop) blockedCount++
        return drop
    }
}
