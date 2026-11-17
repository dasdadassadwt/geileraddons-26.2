package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.PowderChestTracker
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Powder chests in the Crystal Hollows, after SkyHanni's Powder Chest Timer: a chest that spawns where the player
 * just mined is tracked for its 60 seconds. Shows a countdown panel, an ESP box on each chest near the player, an
 * optional age or timer label on each chest, and an optional line through every chest from the oldest to the newest.
 */
class PowderChestModule private constructor(private val settings: Settings) : MiningHudModule(
    "Powder Chests",
    "Marks powder chests that spawn around you and counts down how long they last.",
    "powder_chests",
    "Powder Chests",
    listOf("§a42s §8(§e3 §bchests§8)"),
    settings.display,
    settings.appearance,
) {
    private val tracker = PowderChestTracker()
    private var lastLevelId = 0
    private var added = 0L
    private var rejected = 0L
    private var expired = 0L
    private var opened = 0L

    override fun diagnosticSettings(): String =
        "esp=${settings.highlight.value()};timer=${settings.showTimer.value()};age=${settings.showAge.value()};" +
            "connect=${settings.connect.value()};colorByTime=${settings.colorByTime.value()}"

    override fun onEnable() {
        tracker.clear()
        added = 0
        rejected = 0
        expired = 0
        opened = 0
        super.onEnable()
    }

    override fun onDisable() {
        tracker.clear()
        super.onDisable()
    }

    /** `handleBlockUpdate` and section updates: [old] is the state before the server's change, or null if unloaded. */
    fun onBlockChange(pos: BlockPos, old: BlockState?, new: BlockState) {
        if (!isEnabled() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return
        val player = mc.player ?: return
        val distance = Math.sqrt(player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        val nearby = distance <= PowderChestTracker.MAX_CHEST_DISTANCE &&
            mc.level?.players()?.any { it !== player && it.distanceTo(player) < PowderChestTracker.NEAR_PLAYER_DISTANCE } == true
        val outcome = tracker.onBlockChange(
            pos.x, pos.y, pos.z, kind(old), kind(new)!!, System.nanoTime(), distance, nearby,
        )
        when (outcome) {
            PowderChestTracker.Outcome.ADDED -> {
                added++
                diagnostics.event("CHEST_ADDED x=${pos.x} y=${pos.y} z=${pos.z} distance=${distance.toInt()} tracked=${tracker.size()}")
            }
            PowderChestTracker.Outcome.REJECTED -> {
                rejected++
                diagnostics.event("CHEST_REJECTED x=${pos.x} y=${pos.y} z=${pos.z} playersNearby=$nearby")
            }
            PowderChestTracker.Outcome.REMOVED -> diagnostics.event("CHEST_REMOVED x=${pos.x} y=${pos.y} z=${pos.z}")
            else -> Unit
        }
    }

    /** The level-up sound is how Hypixel announces a powder chest; it vouches for the chest change beside it. */
    fun onSound(soundId: String, volume: Float, pitch: Float) {
        if (!isEnabled() || volume != 1.0f || pitch != 1.0f) return
        if (soundId == "minecraft:entity.player.levelup") tracker.onLevelUpSound(System.nanoTime())
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        beginTick()
        val level = mc.level
        val levelId = System.identityHashCode(level)
        if (levelId != lastLevelId) {
            lastLevelId = levelId
            tracker.clear()
        }
        val now = System.nanoTime()
        val gone = tracker.expire(now)
        if (gone > 0) {
            expired += gone
            diagnostics.event("CHESTS_EXPIRED count=$gone")
        }
        if (level != null) {
            for (chest in tracker.chests()) {
                val entity = level.getBlockEntity(BlockPos(chest.x, chest.y, chest.z)) as? ChestBlockEntity
                if ((entity?.getOpenNess(1f) ?: 0f) > 0f && tracker.remove(chest.x, chest.y, chest.z)) {
                    opened++
                    diagnostics.event("CHEST_OPENED x=${chest.x} y=${chest.y} z=${chest.z}")
                }
            }
        }
        val chests = tracker.chests()
        lines = if (chests.isEmpty() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) emptyList() else {
            val first = chests.first()
            val color = colorLetter(first.remainingNanos(now))
            val noun = if (chests.size == 1) "chest" else "chests"
            listOf("$color${PowderChestTracker.seconds(first.remainingNanos(now))} §8(§e${chests.size} §b$noun§8)")
        }
        diagnostics.statusOnChange {
            "tracked=${tracker.size()} added=$added rejected=$rejected expired=$expired opened=$opened " +
                "island=${MiningContext.on(Island.CRYSTAL_HOLLOWS)}"
        }
    }

    /** ESP boxes on every tracked chest and the optional oldest-to-newest line, through walls. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        val chests = tracker.chests()
        if (chests.isEmpty()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val cam = camera.position()
        val buffer = WorldRenderBuffer.current()
        val now = System.nanoTime()
        val pose = context.poseStack()
        if (settings.highlight.value()) {
            for (chest in chests) {
                val color = chestColor(chest.remainingNanos(now))
                EspRenderer.renderBox(
                    pose, buffer, chest.x - cam.x, chest.y - cam.y, chest.z - cam.z, 1.0, 1.0, 1.0,
                    (color and 0x00FFFFFF) or FILL_ALPHA, color, LINE_WIDTH,
                )
            }
        }
        if (settings.connect.value()) {
            val forward = camera.forwardVector()
            var fromX = forward.x() * LINE_START
            var fromY = forward.y() * LINE_START
            var fromZ = forward.z() * LINE_START
            for (chest in chests) {
                val toX = chest.x + 0.5 - cam.x
                val toY = chest.y + 0.5 - cam.y
                val toZ = chest.z + 0.5 - cam.z
                EspRenderer.renderLine(pose, buffer, fromX, fromY, fromZ, toX, toY, toZ, chestColor(chest.remainingNanos(now)), LINE_WIDTH)
                fromX = toX
                fromY = toY
                fromZ = toZ
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    /** Timer and age text above each chest. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        if (!settings.showTimer.value() && !settings.showAge.value()) return
        val chests = tracker.chests()
        if (chests.isEmpty()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val now = System.nanoTime()
        for (chest in chests) {
            val parts = ArrayList<String>(2)
            if (settings.showTimer.value()) parts.add(PowderChestTracker.seconds(chest.remainingNanos(now)) + " left")
            if (settings.showAge.value()) parts.add("age " + PowderChestTracker.seconds(chest.ageNanos(now)))
            ProjectedLabelRenderer.draw(
                graphics, camera, client.font, Vec3(chest.x + 0.5, chest.y + 1.4, chest.z + 0.5),
                parts.joinToString(" | "), chestColor(chest.remainingNanos(now)), 1.0f,
            )
        }
    }

    private fun chestColor(remainingNanos: Long): Int =
        if (settings.colorByTime.value()) PowderChestTracker.timeColor(remainingNanos) else settings.chestColor.opaqueArgb()

    private fun colorLetter(remainingNanos: Long): String {
        val ratio = remainingNanos.toDouble() / PowderChestTracker.LIFETIME_NANOS
        return when {
            ratio > 0.5 -> "§a"
            ratio > 0.25 -> "§6"
            ratio > 0.1 -> "§c"
            else -> "§4"
        }
    }

    private fun kind(state: BlockState?): PowderChestTracker.Kind? = when {
        state == null -> null
        state.isAir -> PowderChestTracker.Kind.AIR
        state.`is`(Blocks.CHEST) -> PowderChestTracker.Kind.CHEST
        state.`is`(Blocks.STONE) -> PowderChestTracker.Kind.STONE
        else -> PowderChestTracker.Kind.OTHER
    }

    private class Settings {
        val highlight = BooleanSetting("Highlight Chests", true)
        val showTimer = BooleanSetting("Timer On Chest", true)
        val showAge = BooleanSetting("Show Chest Age", false)
        val connect = BooleanSetting("Connect Chests", false)
        val colorByTime = BooleanSetting("Color By Time Left", true)
        val chestColor = ColorSetting("Chest Color", 85, 255, 255, 255)
        val display: List<Setting> = listOf(highlight, showTimer, showAge, connect, colorByTime, chestColor)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val FILL_ALPHA = 0x40000000
        private const val LINE_WIDTH = 3.0f
        private const val LINE_START = 0.5

        @JvmField
        val INSTANCE = PowderChestModule(Settings())
    }
}
