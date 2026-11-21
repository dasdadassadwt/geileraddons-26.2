package geiler.addons.client.module.impl

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.helpers.DungeonPlayerRoster
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.Player
import java.util.HashMap
import java.util.Locale

/** Teammate highlights by dungeon class: class-colored fill and outline plus name titles. */
class DungeonClassEspModule private constructor(
    private val settings: Settings,
) : Module(
    "Dungeon Class ESP",
    "Highlights teammates by dungeon class with a class-colored fill and name titles.",
    Category.F7,
    false,
    settings.range,
    settings.depthCheck,
    settings.fillByClass,
    settings.fillOpacity,
    settings.fill,
    settings.titles,
    settings.titleClassTag,
    settings.titleScale,
    settings.titleHeight,
    settings.editColors,
) {
    companion object {
        @JvmField
        val INSTANCE = DungeonClassEspModule(Settings())

        /** Class color at the given opacity percent (clamped to 0..100); RGB comes from the class color. */
        @JvmStatic
        fun classFillArgb(classArgb: Int, opacityPercent: Int): Int {
            val alpha = Math.round(opacityPercent.coerceIn(0, 100) * 2.55f)
            return (alpha shl 24) or (classArgb and 0x00FFFFFF)
        }

        /** NoFrills' format: a yellow [class letter], then the name, which keeps the class color. */
        @JvmStatic
        fun titleText(name: String, className: String, showClass: Boolean): String =
            if (showClass && className.isNotBlank()) "§e[${className.first()}]§r $name" else name
    }

    private data class Target(val player: Player, val dungeonClass: DungeonClass)

    @Volatile private var targets: List<Target> = emptyList()
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() })

    init {
        group(
            SettingGroup(null, settings.range, settings.depthCheck),
            SettingGroup("Fill", settings.fillByClass, settings.fillOpacity, settings.fill),
            SettingGroup.switched("Titles", settings.titles, settings.titleClassTag, settings.titleScale, settings.titleHeight),
            SettingGroup("Class Colors", settings.editColors),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean = when {
        setting === settings.fillOpacity -> settings.fillByClass.value()
        setting === settings.fill -> !settings.fillByClass.value()
        setting === settings.titleClassTag || setting === settings.titleScale || setting === settings.titleHeight ->
            settings.titles.value()
        else -> true
    }

    override fun onEnable() {
        diagnostics.enable(
            "range=${settings.range.intValue()},depth=${settings.depthCheck.value()},fillByClass=${settings.fillByClass.value()}," +
                "opacity=${settings.fillOpacity.intValue()},titles=${settings.titles.value()}," +
                "classTag=${settings.titleClassTag.value()},scale=${settings.titleScale.value()},height=${settings.titleHeight.value()}",
        )
    }

    override fun onDisable() {
        diagnostics.disable()
        targets = emptyList()
    }

    /** Like NoFrills' class nametags, active for the whole dungeon, including the boss fight. */
    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (!isActive() || level == null || player == null) {
            targets = emptyList()
            diagnostics.statusOnChange { "idle active=false dungeon=${DungeonContextTracker.inDungeon()}" }
            return
        }
        val classes = HashMap<String, DungeonClass>()
        // The dungeon tab list is the live source of teammate classes; the party list only knows
        // classes advertised through Party Finder, so it is a fallback.
        for (tabPlayer in DungeonPlayerRoster.snapshot()) {
            val dungeonClass = DungeonClass.parse(tabPlayer.dungeonClass()) ?: continue
            classes[tabPlayer.name().lowercase(Locale.ROOT)] = dungeonClass
        }
        if (PartyListBackend.hasFreshSnapshot()) {
            for (member in PartyListBackend.snapshot().members()) {
                val memberName = member.name()
                val dungeonClass = member.dungeonClass()
                if (memberName != null && dungeonClass != null) classes.putIfAbsent(memberName.lowercase(Locale.ROOT), dungeonClass)
            }
        }
        val next = ArrayList<Target>()
        // Every loaded teammate gets a title; the range only limits the box highlight.
        for (entity in level.players()) {
            if (entity === player || entity.isRemoved() || !entity.isAlive()) continue
            val dungeonClass = classes[entity.getName().getString().lowercase(Locale.ROOT)] ?: continue
            next.add(Target(entity, dungeonClass))
        }
        targets = java.util.List.copyOf(next)
        diagnostics.statusOnChange {
            "active=true rosterClasses=${classes.size} highlighted=${targets.size} " +
                "classes=${targets.joinToString(",") { it.dungeonClass.displayName() }.take(80)}"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive() || targets.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val rangeSquared = settings.range.intValue().toDouble() * settings.range.intValue()
        var drew = false
        for (target in targets) {
            val entity = target.player
            if (entity.isRemoved() || !entity.isAlive() || player.distanceToSqr(entity) > rangeSquared) continue
            val classArgb = SpiritLeapMenuModule.classColorArgb(target.dungeonClass.displayName())
            val fill = if (settings.fillByClass.value()) classFillArgb(classArgb, settings.fillOpacity.intValue())
                else settings.fill.argb()
            val bounds = entity.boundingBox
            EspRenderer.renderBox(
                context.poseStack(), buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.xsize, bounds.ysize, bounds.zsize, fill, classArgb, 2.0f, settings.depthCheck.value(),
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Draws each teammate's name above their head in their class color. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive() || !settings.titles.value() || targets.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera()
        val partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        for (target in targets) {
            val entity = target.player
            if (entity.isRemoved() || !entity.isAlive()) continue
            val text = titleText(entity.getName().getString(), target.dungeonClass.displayName(), settings.titleClassTag.value())
            ProjectedLabelRenderer.drawFloatingText(
                graphics, camera, client.font, entity.getPosition(partialTick).add(0.0, settings.titleHeight.value().toDouble(), 0.0), text,
                SpiritLeapMenuModule.classColorArgb(target.dungeonClass.displayName()), settings.titleScale.value(),
            )
        }
    }

    /** Applies the old Dungeon Mob ESP values once; values already saved on this module always win. */
    fun restoreLegacySettings(numbers: Map<String, Float>, toggles: Map<String, Boolean>, colors: Map<String, IntArray>) {
        DungeonEspSplitMigration.restoreToggle(this, toggles, settings.depthCheck)
        DungeonEspSplitMigration.restoreNumber(this, numbers, settings.range)
        DungeonEspSplitMigration.restoreColor(this, colors, settings.fill)
    }

    private class Settings {
        val range = NumberSetting("Highlight Range", 8f, 128f, 64f, true)
        val depthCheck = BooleanSetting("Depth Check", false)
        val fillByClass = BooleanSetting("Fill By Class Color", true)
        val fillOpacity = NumberSetting("Class Fill Opacity", 0f, 100f, 20f, true)
        val fill = ColorSetting("Party Class Fill", 0, 0, 0, 24)
        val titles = BooleanSetting("Class Titles", true)
        val titleClassTag = BooleanSetting("Show Class In Title", true)
        val titleScale = NumberSetting("Title Scale", 0.6f, 2.0f, 1.0f)
        val titleHeight = NumberSetting("Title Height", 0.5f, 5.0f, 3.25f)
        val editColors = ModuleAction(
            "Edit Class Colors", "Class colors are shared with Spirit Leap Menu and the dungeon map.",
            Runnable {
                ClickGuiState.setCategory(Category.F7)
                ClickGuiState.setOpenModule(SpiritLeapMenuModule.INSTANCE)
            },
        )
    }
}
