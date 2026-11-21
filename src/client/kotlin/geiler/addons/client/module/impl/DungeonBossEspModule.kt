package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonMimicChestDetector
import geiler.addons.client.dungeon.helpers.DungeonMapCoreScanner
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB

/** F7 Wither Boss and known Mimic chest highlights, split out of Dungeon Mob ESP. */
class DungeonBossEspModule private constructor(
    private val settings: Settings,
) : Module(
    "Dungeon Boss ESP",
    "Highlights the F7 Wither Boss and known Mimic chests during Clear.",
    Category.F7,
    false,
    settings.withers,
    settings.mimic,
    settings.mimicFill,
    settings.mimicOutline,
    settings.range,
    settings.depthCheck,
    settings.mobFill,
    settings.mobOutline,
) {
    companion object {
        @JvmField
        val INSTANCE = DungeonBossEspModule(Settings())
    }

    @Volatile private var targets: List<LivingEntity> = emptyList()
    @Volatile private var mimicChests: List<BlockPos?> = emptyList()
    private val roomCoreScanner = DungeonMapCoreScanner()
    private var lastLevel: ClientLevel? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() })

    init {
        group(
            SettingGroup.switched("Wither Bosses", settings.withers, settings.range, settings.mobFill, settings.mobOutline),
            SettingGroup.switched("Mimics", settings.mimic, settings.range, settings.mimicFill, settings.mimicOutline),
            SettingGroup("Visibility", settings.depthCheck),
        )
    }

    override fun onEnable() {
        diagnostics.enable(
            "withers=${settings.withers.value()},mimic=${settings.mimic.value()},range=${settings.range.intValue()}," +
                "depth=${settings.depthCheck.value()}",
        )
    }

    override fun onDisable() {
        diagnostics.disable()
        targets = emptyList()
        mimicChests = emptyList()
    }

    private fun scanWither(): Boolean {
        val floor = DungeonContextTracker.currentFloor()
        val inDungeon = DungeonContextTracker.inDungeon()
        return DungeonSpecialMobRules.shouldScanF7WitherBoss(
            settings.withers.value(), inDungeon, floor != null && floor.number() == 7,
            floor != null && floor.master(), inDungeon && !DungeonContextTracker.isMobEspPhase(),
        )
    }

    override fun isActive(): Boolean =
        isEnabled() && (scanWither() || settings.mimic.value() && DungeonContextTracker.isClearPhase())

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    fun tick() {
        val client = Minecraft.getInstance()
        if (client.level != lastLevel) {
            lastLevel = client.level
            targets = emptyList()
            mimicChests = emptyList()
            roomCoreScanner.reset()
        }
        if (!isEnabled()) return
        diagnostics.advance()
        val level = client.level
        val player = client.player
        if (!isActive() || level == null || player == null) {
            targets = emptyList()
            mimicChests = emptyList()
            diagnostics.statusOnChange { "idle active=false dungeon=${DungeonContextTracker.inDungeon()}" }
            return
        }
        val next = ArrayList<LivingEntity>()
        val floor = DungeonContextTracker.currentFloor()
        if (scanWither() && floor != null) {
            val bossPhaseObserved = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()
            val range = settings.range.intValue()
            for (entity in ClientEntitySnapshot.nearby(level, player, range.toDouble())) {
                if (entity !is LivingEntity || entity.isRemoved() || !entity.isAlive() ||
                    entity.distanceToSqr(player) > range.toDouble() * range ||
                    !DungeonSpecialMobRules.isEarlyF7WitherBoss(
                        floor.number() == 7, floor.master(), bossPhaseObserved, entity.getType() == EntityTypes.WITHER,
                    )
                ) continue
                next.add(entity)
            }
            val scan = DungeonMobEspSupport.scan(
                client, range, false, { name -> DungeonSpecialMobRules.isF7WitherBossName(name) }, false, false, null,
                { entity ->
                    DungeonSpecialMobRules.isF7WitherBoss(
                        floor.number() == 7, floor.master(), entity.getType() == EntityTypes.WITHER,
                        DungeonMobEspSupport.plainName(entity),
                    )
                },
            )
            for (entity in scan.matches()) if (next.none { it === entity }) next.add(entity)
        }
        targets = java.util.List.copyOf(next)
        mimicChests = if (settings.mimic.value() && DungeonContextTracker.isClearPhase()) {
            DungeonMimicChestDetector.detect(client, roomCoreScanner)
        } else {
            emptyList()
        }
        diagnostics.statusOnChange {
            "active=true witherTargets=${targets.size} mimicChests=${mimicChests.size} master=${floor?.master() ?: false} " +
                "floor=${floor?.number() ?: -1} clearPhase=${DungeonContextTracker.isClearPhase()}"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (client.level == null) return
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val rangeSquared = settings.range.intValue().toDouble() * settings.range.intValue()
        var drew = false
        for (entity in targets) {
            if (entity.isRemoved() || !entity.isAlive() || entity is ArmorStand ||
                player.distanceToSqr(entity) > rangeSquared
            ) continue
            val bounds = entity.boundingBox
            EspRenderer.renderBox(
                pose, buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.xsize, bounds.ysize, bounds.zsize, settings.mobFill.argb(), settings.mobOutline.argb(),
                2.0f, settings.depthCheck.value(),
            )
            drew = true
        }
        if (settings.mimic.value() && DungeonContextTracker.isClearPhase()) {
            for (chest in mimicChests) {
                if (chest == null || player.distanceToSqr(chest.x + 0.5, chest.y + 0.5, chest.z + 0.5) > rangeSquared) continue
                val bounds = AABB(
                    chest.x.toDouble(), chest.y.toDouble(), chest.z.toDouble(),
                    chest.x + 1.0, chest.y + 1.0, chest.z + 1.0,
                )
                EspRenderer.renderBox(
                    pose, buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                    bounds.xsize, bounds.ysize, bounds.zsize, settings.mimicFill.argb(), settings.mimicOutline.argb(),
                    2.0f, settings.depthCheck.value(),
                )
                drew = true
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Applies the old Dungeon Mob ESP values once; values already saved on this module always win. */
    fun restoreLegacySettings(numbers: Map<String, Float>, toggles: Map<String, Boolean>, colors: Map<String, IntArray>) {
        DungeonEspSplitMigration.restoreToggle(this, toggles, settings.withers)
        DungeonEspSplitMigration.restoreToggle(this, toggles, settings.mimic)
        DungeonEspSplitMigration.restoreToggle(this, toggles, settings.depthCheck)
        DungeonEspSplitMigration.restoreNumber(this, numbers, settings.range)
        DungeonEspSplitMigration.restoreColor(this, colors, settings.mimicFill)
        DungeonEspSplitMigration.restoreColor(this, colors, settings.mimicOutline)
        DungeonEspSplitMigration.restoreColor(this, colors, settings.mobFill)
        DungeonEspSplitMigration.restoreColor(this, colors, settings.mobOutline)
    }

    private class Settings {
        val withers = BooleanSetting("F7 Wither Boss ESP", true)
        val mimic = BooleanSetting("Known Mimic Chest ESP", true)
        val mimicFill = ColorSetting("Mimic Chest Fill", 209, 29, 5, 160)
        val mimicOutline = ColorSetting("Mimic Chest Outline", 209, 29, 5, 255)
        val range = NumberSetting("Highlight Range", 8f, 128f, 64f, true)
        val depthCheck = BooleanSetting("Depth Check", false)
        val mobFill = ColorSetting("Special Mob Fill", 255, 80, 35, 48)
        val mobOutline = ColorSetting("Special Mob Outline", 255, 80, 35, 255)
    }
}
