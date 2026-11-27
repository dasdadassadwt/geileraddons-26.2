package geiler.addons.client.sim

/**
 * The network firewall of the device simulators. While one runs, no packet that clicks, digs, drops, swings or
 * attacks leaves the client. The simulator handles clicks before the game acts on them; this is the second line,
 * asked by the mixin on `ClientCommonPacketListenerImpl.send`. Movement, chat and commands are untouched.
 */
object DeviceSimGuard {
    private val BLOCKED = setOf(
        "ServerboundUseItemOnPacket", "ServerboundUseItemPacket", "ServerboundPlayerActionPacket",
        "ServerboundInteractPacket", "ServerboundAttackPacket", "ServerboundSwingPacket",
        "ServerboundPickItemFromBlockPacket", "ServerboundPickItemFromEntityPacket",
        "ServerboundSpectatorActionPacket",
    )

    @Volatile
    private var running = false

    @Volatile
    var blockedCount = 0L
        private set

    @JvmStatic
    fun arm(on: Boolean) {
        running = on
    }

    @JvmStatic
    fun active(): Boolean = running

    @JvmStatic
    fun isBlocked(simpleName: String): Boolean = simpleName in BLOCKED

    @JvmStatic
    fun shouldDrop(packet: Any?): Boolean {
        if (!running || packet == null) return false
        val drop = isBlocked(packet.javaClass.simpleName)
        if (drop) blockedCount++
        return drop
    }
}
