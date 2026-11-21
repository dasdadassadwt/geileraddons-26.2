package geiler.addons.client.module.impl

import geiler.addons.client.fishing.SeaCreatureCapRules
import geiler.addons.client.fishing.SeaCreatureRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.tree.ChatText
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Counts the sea creatures you have fished up that are still alive and shows `alive/cap` as a movable HUD line. A mob name
 * tag (`[Lv..] Name`) that first appears within 6 blocks of your bobber in the 2.5 s after you reel in is yours, with the
 * same rule as Sea Creature Alerts; it stops counting when its tag is gone. The cap is a setting, not read from the game.
 */
class SeaCreatureCapModule private constructor(settings: Settings) : Module(
    "Sea Creature Cap",
    "Shows how many of your fished-up sea creatures are alive against the cap.",
    Category.FISHING,
    false,
    settings.cap,
    settings.hideWhenNone,
), HudElement {
    private val cap = settings.cap
    private val hideWhenNone = settings.hideWhenNone

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 300)
    private val tracker = SeaCreatureCapRules.Tracker()
    private val counted = SeaCreatureRules.SeenSet(SEEN_TTL_NANOS)

    private var lastBobberPos: Vec3? = null
    private var bobberGoneNanos = Long.MIN_VALUE
    private var hookOut = false
    private var ticks = 0
    private var gained = 0
    private var lost = 0

    override fun id(): String = "sea_creature_cap"

    override fun displayName(): String = "Sea Creature Cap"

    override fun visible(): Boolean =
        isEnabled() && eligible() && (!hideWhenNone.value() || tracker.count() > 0 || hookOut)

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun width(font: Font?): Int = font!!.width(shown())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun previewWidth(font: Font?): Int = font!!.width(label(SAMPLE_ALIVE))

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, shown(), x, y, WHITE)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, if (visible()) shown() else label(SAMPLE_ALIVE), x, y, WHITE)
    }

    private fun eligible(): Boolean {
        if (!SkyblockItems.inSkyblock()) return false
        val island = HypixelModApi.currentIsland()
        return island != Island.CATACOMBS && island != Island.KUUDRA
    }

    private fun shown(): String = label(tracker.count())

    private fun label(alive: Int): String {
        val color = if (SeaCreatureCapRules.atCap(alive, cap.intValue())) "§c" else "§b"
        return color + SeaCreatureCapRules.text(alive, cap.intValue())
    }

    override fun onEnable() {
        reset()
        diagnostics.enable("cap=${cap.intValue()} hideWhenNone=${hideWhenNone.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE gained=$gained lost=$lost alive=${tracker.count()}")
        reset()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null || !eligible()) {
            if (tracker.count() > 0) reset()
            return
        }
        val hook = player.fishing
        val out = hook != null && !hook.isRemoved
        if (out) {
            lastBobberPos = hook!!.position()
        } else if (hookOut) {
            bobberGoneNanos = System.nanoTime()
        }
        hookOut = out

        if (++ticks >= SCAN_TICKS) {
            ticks = 0
            val before = tracker.count()
            val dropped = tracker.retain(tracker.snapshot().filter { id -> level.getEntity(id)?.isRemoved == false }.toSet())
            if (dropped > 0) {
                lost += dropped
                diagnostics.event("GONE dropped=$dropped alive=${tracker.count()} before=$before")
            }
            scanNew(level)
        }
        diagnostics.status { "enabled=true alive=${tracker.count()} cap=${cap.intValue()} gained=$gained lost=$lost hookOut=$hookOut suppressedEvents=$it" }
    }

    private fun scanNew(level: net.minecraft.client.multiplayer.ClientLevel) {
        val bobber = lastBobberPos ?: return
        val now = System.nanoTime()
        if (!SeaCreatureRules.isOwn(bobberGoneNanos, now, 0.0)) return
        val area = AABB.ofSize(bobber, SCAN_RADIUS * 2, SCAN_RADIUS * 2, SCAN_RADIUS * 2)
        for (entity in level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { it.customName != null }) {
            val plain = ChatText.plain(entity.customName?.string ?: continue)
            if (!SeaCreatureCapRules.isLabel(plain)) continue
            if (!SeaCreatureRules.isOwn(bobberGoneNanos, now, entity.position().distanceTo(bobber))) continue
            if (!counted.firstSight(entity.id, now) || !tracker.add(entity.id)) continue
            gained++
            diagnostics.event("COUNT label=${plain.take(40)} alive=${tracker.count()} cap=${cap.intValue()}")
        }
    }

    private fun reset() {
        tracker.clear()
        counted.clear()
        lastBobberPos = null
        bobberGoneNanos = Long.MIN_VALUE
        hookOut = false
        ticks = 0
        gained = 0
        lost = 0
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Sea Creature Cap")
        val cap = spec.slider("cap")
        val hideWhenNone = spec.toggle("hideWhenNone")
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SAMPLE_ALIVE = 3
        private const val SCAN_TICKS = 5
        private const val SCAN_RADIUS = 6.0
        private const val SEEN_TTL_NANOS = 6L * 60L * 1_000_000_000L

        @JvmField
        val INSTANCE = SeaCreatureCapModule(Settings())
    }
}
