package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.odin.SpiritLeapLayout
import geiler.addons.client.dungeon.odin.SpiritLeapRoster
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.UUID

/** Fixed camera panels owned by the Leap menu; cards are drawn after this layer. */
class SpiritLeapCameraHud private constructor() {
    @JvmRecord
    data class Panel(val quadrant: Int, val targetName: String?, val targetId: UUID?, val bounds: SpiritLeapLayout.Bounds?) {
        override fun toString() =
            "Panel[quadrant=$quadrant, targetName=$targetName, targetId=$targetId, bounds=$bounds]"
    }

    @JvmRecord
    data class SamplePoint(val x: Int, val y: Int) {
        override fun toString() = "SamplePoint[x=$x, y=$y]"
    }

    private var panels: List<Panel> = emptyList()
    private var lastCards: List<SpiritLeapRoster.Card>? = null
    private var lastPanelIds: Map<String, UUID>? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lookupLevel: ClientLevel? = null
    private var lookupTick = Long.MIN_VALUE
    private var loadedIds: Map<String, UUID> = emptyMap()

    fun draw(graphics: GuiGraphicsExtractor, width: Int, height: Int, cards: List<SpiritLeapRoster.Card>?) {
        val client = Minecraft.getInstance()
        val level = client.level
        if (!client.isSameThread() || !SpiritLeapMenuModule.INSTANCE.isEnabled() ||
            !SpiritLeapMenuModule.povsEnabled() || client.gui.screen() == null || level == null ||
            cards.isNullOrEmpty() || width < 2 || height < 2
        ) {
            clear()
            return
        }
        val ids = loadedPlayerIds(level)
        if (cards !== lastCards || ids !== lastPanelIds || width != lastWidth || height != lastHeight) {
            panels = reconcile(cards, ids, panels, width, height)
            lastCards = cards
            lastPanelIds = ids
            lastWidth = width
            lastHeight = height
        }
        for (panel in panels) {
            SpiritLeapPovRenderer.INSTANCE.drawPanel(graphics, width, height, cards, panel.quadrant, panel.targetId)
        }
    }

    private fun loadedPlayerIds(level: ClientLevel): Map<String, UUID> {
        val tick = level.gameTime
        if (lookupLevel === level && lookupTick == tick) return loadedIds
        val next = HashMap<String, UUID>()
        val players: List<AbstractClientPlayer?> = level.players()
        for (player in players) {
            if (player != null) next.putIfAbsent(normalize(player.gameProfile.name), player.uuid)
        }
        if (lookupLevel !== level || next != loadedIds) loadedIds = java.util.Map.copyOf(next)
        lookupLevel = level
        lookupTick = tick
        return loadedIds
    }

    fun clear() {
        panels = emptyList()
        lastCards = null
        lastPanelIds = null
        lookupLevel = null
        lookupTick = Long.MIN_VALUE
        loadedIds = emptyMap()
    }

    companion object {
        @JvmField val INSTANCE = SpiritLeapCameraHud()

        @JvmStatic
        fun reconcile(
            cards: List<SpiritLeapRoster.Card?>?,
            ids: Map<String, UUID>?,
            previous: List<Panel?>?,
            width: Int,
            height: Int,
        ): List<Panel> {
            if (cards == null || width < 2 || height < 2) return java.util.List.of()
            val result = ArrayList<Panel>(4)
            val quadrants = HashSet<Int>()
            val names = HashSet<String>()
            for (card in cards) {
                val member = card?.member() ?: continue
                if (member.empty()) continue
                val bounds = quadrantBounds(card.quadrant(), width, height) ?: continue
                if (quadrants.contains(card.quadrant())) continue
                val name = normalize(member.name())
                if (name.isEmpty() || !names.add(name)) continue
                quadrants.add(card.quadrant())
                var id = ids?.get(name)
                if (id == null) {
                    id = previous?.firstOrNull { panel ->
                        panel != null && panel.quadrant == card.quadrant() && panel.targetName == name
                    }?.targetId
                }
                result += Panel(card.quadrant(), name, id, bounds)
            }
            return java.util.List.copyOf(result)
        }

        @JvmStatic
        fun quadrantBounds(quadrant: Int, width: Int, height: Int): SpiritLeapLayout.Bounds? {
            if (quadrant !in 0..3 || width < 2 || height < 2) return null
            val left = if (quadrant % 2 == 0) 0 else width / 2
            val top = if (quadrant < 2) 0 else height / 2
            val right = if (quadrant % 2 == 0) width / 2 else width
            val bottom = if (quadrant < 2) height / 2 else height
            return SpiritLeapLayout.Bounds(left, top, right - left, bottom - top)
        }

        /** Chooses an exposed patch, excluding the card's maximum hover bounds. */
        @JvmStatic
        fun samplePoint(quadrant: Int, width: Int, height: Int, distance: Float, margin: Int): SamplePoint? {
            val panel = quadrantBounds(quadrant, width, height) ?: return null
            val layout = SpiritLeapLayout.calculate(width, height, distance)
            val card = layout.quadrant(quadrant) ?: return null
            val grow = layout.maxHoverGrowth()
            val inset = maxOf(1, margin)
            val fractions = intArrayOf(1, 4, 7)
            for (fy in fractions) for (fx in fractions) {
                val x = panel.x() + panel.width() * fx / 8
                val y = panel.y() + panel.height() * fy / 8
                if (x - inset < panel.x() || y - inset < panel.y() ||
                    x + inset >= panel.x() + panel.width() || y + inset >= panel.y() + panel.height()
                ) continue
                val intersects = x + inset >= card.x() - grow && x - inset < card.x() + card.width() + grow &&
                    y + inset >= card.y() - grow && y - inset < card.y() + card.height() + grow
                if (!intersects) return SamplePoint(x, y)
            }
            return null
        }

        private fun normalize(name: String?) = name?.lowercase(Locale.ROOT).orEmpty()
    }
}
