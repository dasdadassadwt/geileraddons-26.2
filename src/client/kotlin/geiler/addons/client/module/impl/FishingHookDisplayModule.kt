package geiler.addons.client.module.impl

import geiler.addons.client.fishing.FishingHookRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
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
    "Shows the fish countdown and the !!! bite marker big above your hook.",
    Category.FISHING,
    false,
    settings.atCrosshair,
    settings.scale,
    settings.sound,
) {
    private val atCrosshair = settings.atCrosshair
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
        diagnostics.enable("crosshair=${atCrosshair.value()} scale=${scale.intValue()} sound=${sound.value()}")
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

    /** The countdown or `!!!`, large, above the hook or at the crosshair. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled()) return
        val shown = text ?: return
        val client = Minecraft.getInstance()
        val size = scale.intValue().coerceIn(1, 4).toFloat()
        val color = if (FishingHookRules.isBite(shown)) RED else YELLOW
        val label = "§l$shown"
        if (atCrosshair.value()) {
            val width = client.window.guiScaledWidth
            val height = client.window.guiScaledHeight
            val textWidth = client.font.width(label) * size
            graphics.pose().pushMatrix()
            graphics.pose().translate(width / 2f - textWidth / 2f, height / 2f - 30f)
            graphics.pose().scale(size, size)
            graphics.text(client.font, label, 0, 0, color)
            graphics.pose().popMatrix()
            return
        }
        val pos = hookPos ?: return
        ProjectedLabelRenderer.drawFloatingText(
            graphics, client.gameRenderer.mainCamera(), client.font, pos.add(0.0, ABOVE_HOOK, 0.0), label, color, size,
        )
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
        val atCrosshair = spec.toggle("atCrosshair")
        val scale = spec.slider("scale")
        val sound = spec.toggle("sound")
    }

    companion object {
        private const val RED = 0xFFFF5555.toInt()
        private const val YELLOW = 0xFFFFFF55.toInt()
        private const val ABOVE_HOOK = 1.2
        private const val DUMP_AFTER_TICKS = 160
        private const val DUMP_RADIUS = 3.0
        private const val DUMP_MAX = 8

        @JvmField
        val INSTANCE = FishingHookDisplayModule(Settings())
    }
}
