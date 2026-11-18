package geiler.addons.client.kuudra

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.slayer.SlayerSidebar
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.entity.monster.cubemob.MagmaCube

/** What the client can see of a Kuudra run: phase from Elle's lines, boss, supply carriers, build piles. Client thread only. */
object KuudraState {
    /** A floating tag: an unfinished ballista pile with its percent, or a "SUPPLIES RECEIVED" stand. */
    class Tag(val x: Double, val y: Double, val z: Double, val name: String, val percent: Int)

    private const val REFRESH_TICKS = 10
    private const val TIER_TICKS = 100
    private const val BOSS_MAX_Y = 10.0
    private const val CARRIER_MAX_Y = 67.0

    var phase = KuudraPhase.NONE
        private set
    var tier = 0
        private set
    var boss: MagmaCube? = null
        private set
    var giants: List<Giant> = emptyList()
        private set
    var piles: List<Tag> = emptyList()
        private set
    var receivedStands: List<Tag> = emptyList()
        private set
    var buildPercent = 0
        private set
    var builders = 0
        private set

    /** Nanotime of Elle's supplies line, or 0, for the supply times. */
    var runStartNanos = 0L
        private set

    private var lastLevel: Any? = null
    private var ticks = 0

    @JvmStatic
    fun inKuudra(): Boolean = HypixelModApi.currentIsland() == Island.KUUDRA

    fun onChat(plainMessage: String) {
        if (!inKuudra()) return
        val next = KuudraPhase.fromMessage(plainMessage) ?: return
        phase = next
        if (next == KuudraPhase.SUPPLIES) runStartNanos = System.nanoTime()
    }

    /** One client tick; refreshes the entity lists every 10 ticks while in Kuudra. */
    fun tick() {
        val client = Minecraft.getInstance()
        val level = client.level
        if (level !== lastLevel) {
            lastLevel = level
            clear()
        }
        if (level == null || !inKuudra()) {
            if (phase != KuudraPhase.NONE || boss != null || giants.isNotEmpty() || piles.isNotEmpty()) clear()
            return
        }
        val player = client.player
        if (player != null && player.y < BOSS_MAX_Y && (phase == KuudraPhase.SKIP || phase == KuudraPhase.DPS)) phase = KuudraPhase.BOSS
        if (ticks++ % REFRESH_TICKS != 0) return
        if (tier == 0 || ticks % TIER_TICKS == 1) tier = KuudraRules.tierFrom(SlayerSidebar.read(level.scoreboard)) ?: tier

        var foundBoss: MagmaCube? = null
        val foundGiants = ArrayList<Giant>()
        val foundPiles = ArrayList<Tag>()
        val foundStands = ArrayList<Tag>()
        for (entity in level.entitiesForRendering()) {
            when (entity) {
                is MagmaCube -> if (entity.size == 30 && entity.maxHealth >= 10_000f && (foundBoss == null || entity.y > foundBoss.y)) {
                    foundBoss = entity
                }
                is Giant -> if (foundGiants.size < KuudraRules.MAX_GIANTS && entity.y < CARRIER_MAX_Y &&
                    entity.getItemBySlot(EquipmentSlot.MAINHAND).hoverName.string.endsWith("Head")) foundGiants.add(entity)
                is ArmorStand -> {
                    val name = entity.customName?.string?.let { ChatText.plain(it) } ?: continue
                    KuudraRules.buildStatus(name)?.let { buildPercent = it[0]; builders = it[1] }
                    val percent = KuudraRules.pilePercent(name)
                    if (percent != null && foundPiles.size < KuudraRules.MAX_PILES) foundPiles.add(Tag(entity.x, entity.y, entity.z, name, percent))
                    else if (name.contains("SUPPLIES RECEIVED") && foundStands.size < KuudraRules.MAX_PILES) foundStands.add(Tag(entity.x, entity.y, entity.z, name, 100))
                }
            }
        }
        boss = foundBoss
        giants = foundGiants
        piles = foundPiles
        receivedStands = foundStands
    }

    private fun clear() {
        phase = KuudraPhase.NONE
        tier = 0
        boss = null
        giants = emptyList()
        piles = emptyList()
        receivedStands = emptyList()
        buildPercent = 0
        builders = 0
        runStartNanos = 0L
        ticks = 0
    }
}
