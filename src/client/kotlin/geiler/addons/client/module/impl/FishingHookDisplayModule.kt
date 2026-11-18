package geiler.addons.client.module.impl

import geiler.addons.client.fishing.FishingHookRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.entity.EntityTypeTest

/**
 * Shows the game's own hook timer big: once a fish is on its way Hypixel puts an armor stand on the hook that counts
 * down and then reads `!!!`; this mirrors its name above the hook or at the crosshair, after Skyblocker's Fishing Hook
 * Display. No time is computed here, the text is the stand's.
 */
class FishingHookDisplayModule private constructor(settings: Settings) : Module(
    "Fishing Hook Display",
    "Shows the fish countdown and the !!! bite marker big as a movable HUD element.",
    Category.FISHING,
    false,
    settings.scale,
    settings.sound,
), HudElement {
    private val scale = settings.scale
    private val sound = settings.sound
    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 12, 100L, 300)

    private var text: String? = null
    private var hookPos: net.minecraft.world.phys.Vec3? = null
    private var hookTicks = 0
    private var foundThisCast = false
    private var dumped = false
    private var bites = 0
    private var seenTexts = 0

    override fun onEnable() {
        reset()
        diagnostics.enable("scale=${scale.intValue()} sound=${sound.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val hook = if (SkyblockItems.inSkyblock()) client.player?.fishing else null
        if (level == null || hook == null || hook.isRemoved) {
            if (hookPos != null) diagnostics.event("HOOK_GONE found=$foundThisCast ticks=$hookTicks bites=$bites")
            hookPos = null
            text = null
            hookTicks = 0
            foundThisCast = false
            dumped = false
            return
        }
        hookTicks++
        hookPos = hook.position()
        val area = hook.boundingBox.inflate(FishingHookRules.MAX_DISTANCE)
        var best: String? = null
        var bestDistance = Double.MAX_VALUE
        for (stand in level.getEntities(EntityTypeTest.forClass(ArmorStand::class.java), area) { true }) {
            val name = stand.customName?.string ?: continue
            if (!FishingHookRules.isHookText(name)) continue
            val distance = stand.position().distanceTo(hook.position())
            if (!FishingHookRules.withinHook(distance) || distance >= bestDistance) continue
            bestDistance = distance
            best = name.trim()
        }

        val before = text
        text = best
        if (best != null && best != before) {
            seenTexts++
            foundThisCast = true
            diagnostics.event("HOOK_TEXT text=$best distance=${"%.2f".format(bestDistance)} ticks=$hookTicks")
            if (FishingHookRules.isBite(best) && !FishingHookRules.isBite(before)) {
                bites++
                diagnostics.event("BITE ticks=$hookTicks")
                if (sound.value()) ModuleSounds.play("minecraft:block.note_block.pling")
            }
        }
        // After a long wait with nothing found, record what is around the hook once so the next test can see it.
        if (!foundThisCast && !dumped && hookTicks >= DUMP_AFTER_TICKS) {
            dumped = true
            dumpNearby(level, hook)
        }
        diagnostics.status {
            "enabled=true hook=true text=${text ?: "none"} hookTicks=$hookTicks found=$foundThisCast " +
                "texts=$seenTexts bites=$bites suppressedEvents=$it"
        }
    }

    private fun dumpNearby(level: net.minecraft.client.multiplayer.ClientLevel, hook: Entity) {
        val area = hook.boundingBox.inflate(DUMP_RADIUS)
        val near = level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { it !== hook && it !== Minecraft.getInstance().player }
        diagnostics.event("NO_TEXT_AFTER_TICKS ticks=$hookTicks nearby=${near.size}")
        for (entity in near.take(DUMP_MAX)) {
            diagnostics.event(
                "NEARBY type=${entity.type} distance=${"%.2f".format(entity.position().distanceTo(hook.position()))} " +
                    "name=${entity.customName?.string?.take(40) ?: "none"}",
            )
        }
    }

    override fun id(): String = "fishing_hook_display"

    override fun displayName(): String = "Fishing Hook Display"

    override fun visible(): Boolean = isEnabled() && text != null

    override fun previewMode(): String = if (text != null) "live" else "sample"

    override fun width(font: Font?): Int = Math.round(font!!.width(label(text ?: SAMPLE)) * size())

    override fun height(font: Font?): Int = Math.round(font!!.lineHeight * size())

    override fun previewWidth(font: Font?): Int = Math.round(font!!.width(label(SAMPLE)) * size())

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        draw(graphics, font, x, y, text ?: return)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        draw(graphics, font, x, y, text ?: SAMPLE)

    private fun size(): Float = scale.intValue().coerceIn(1, 4).toFloat()

    private fun label(shown: String): String = "§l$shown"

    /** The countdown or `!!!`, large, at the element's Move Elements position. */
    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, shown: String) {
        val size = size()
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(size, size)
        graphics.text(font, label(shown), 0, 0, if (FishingHookRules.isBite(shown)) RED else YELLOW)
        graphics.pose().popMatrix()
    }

    private fun reset() {
        text = null
        hookPos = null
        hookTicks = 0
        foundThisCast = false
        dumped = false
        bites = 0
        seenTexts = 0
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Fishing Hook Display")
        val scale = spec.slider("scale")
        val sound = spec.toggle("sound")
    }

    companion object {
        private const val RED = 0xFFFF5555.toInt()
        private const val YELLOW = 0xFFFFFF55.toInt()
        private const val SAMPLE = "3.4"
        private const val DUMP_AFTER_TICKS = 160
        private const val DUMP_RADIUS = 3.0
        private const val DUMP_MAX = 8

        @JvmField
        val INSTANCE = FishingHookDisplayModule(Settings())
    }
}
