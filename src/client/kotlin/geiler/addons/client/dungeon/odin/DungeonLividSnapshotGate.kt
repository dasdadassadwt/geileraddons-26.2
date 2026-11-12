package geiler.addons.client.dungeon.odin

/** Pure Floor 5 Livid target-snapshot phase gate; Java-callable as a static method. */
object DungeonLividSnapshotGate {
    @JvmStatic
    fun shouldScan(featureEnabled: Boolean, inDungeon: Boolean, floorNumber: Int, mobEspPhase: Boolean): Boolean =
        featureEnabled && inDungeon && floorNumber == 5 && !mobEspPhase
}
