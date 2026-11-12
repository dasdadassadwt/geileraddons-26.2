package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonMimicChestDetector
import geiler.addons.client.dungeon.odin.OdinDungeonMapCoreScanner
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
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
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale

/** Optional F7 Wither Boss ESP and exact-roster, class-colored party highlights. */
class DungeonSpecialMobEspModule private constructor(
    private val settings: Settings,
) : Module(
    "Dungeon Special Mob ESP",
    "Highlights the F7 Wither Boss, known Mimic chests during Clear, and known party members by class.",
    Category.F7,
    false,
    settings.withers,
    settings.mimic,
    settings.mimicFill,
    settings.mimicOutline,
    settings.partyClasses,
    settings.range,
    settings.depthCheck,
    settings.mobFill,
    settings.mobOutline,
    settings.tank,
    settings.healer,
    settings.mage,
    settings.berserk,
    settings.archer,
    settings.partyFill,
) {
    companion object {
        @JvmField
        val INSTANCE = DungeonSpecialMobEspModule(Settings())
    }

    @Volatile private var targets: List<Target> = emptyList()
    @Volatile private var mimicChests: List<BlockPos?> = emptyList()
    private val roomCoreScanner = OdinDungeonMapCoreScanner()
    private var lastLevel: ClientLevel? = null

    init {
        group(
            SettingGroup.switched("F7 Wither Boss", settings.withers, settings.range, settings.mobFill, settings.mobOutline),
            SettingGroup.switched(
                "Known Mimic Chest", settings.mimic, settings.range,
                settings.mimicFill, settings.mimicOutline,
            ),
            SettingGroup.switched(
                "Party classes", settings.partyClasses, settings.range,
                settings.tank, settings.healer, settings.mage, settings.berserk, settings.archer, settings.partyFill,
            ),
            SettingGroup("Visibility", settings.depthCheck),
        )
    }

    override fun isActive(): Boolean {
        val floor = DungeonContextTracker.currentFloor()
        val inDungeon = DungeonContextTracker.inDungeon()
        val bossPhaseObserved = inDungeon && !DungeonContextTracker.isMobEspPhase()
        val f7WitherBoss = DungeonSpecialMobRules.shouldScanF7WitherBoss(
            settings.withers.value(),
            inDungeon,
            floor != null && floor.number() == 7,
            floor != null && floor.master(),
            bossPhaseObserved,
        )
        val partyPhase = settings.partyClasses.value() && DungeonContextTracker.isMobEspPhase()
        val mimicClear = settings.mimic.value() && DungeonContextTracker.isClearPhase()
        return isEnabled() && (partyPhase || f7WitherBoss || mimicClear)
    }

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
        if (!isActive() || client.level == null || client.player == null) {
            targets = emptyList()
            mimicChests = emptyList()
            return
        }

        val level = client.level!!
        val player = client.player!!
        val next = ArrayList<Target>()
        val floor = DungeonContextTracker.currentFloor()
        val inDungeon = DungeonContextTracker.inDungeon()
        val bossPhaseObserved = inDungeon && !DungeonContextTracker.isMobEspPhase()
        val scanF7WitherBoss = DungeonSpecialMobRules.shouldScanF7WitherBoss(
            settings.withers.value(),
            inDungeon,
            floor != null && floor.number() == 7,
            floor != null && floor.master(),
            bossPhaseObserved,
        )
        if (scanF7WitherBoss) {
            val f7Floor = floor!!
            val range = settings.range.intValue()
            for (entity in ClientEntitySnapshot.nearby(level, player, range.toDouble())) {
                if (entity !is LivingEntity || entity.isRemoved() || !entity.isAlive()
                    || entity.distanceToSqr(player) > range.toDouble() * range
                    || !DungeonSpecialMobRules.isEarlyF7WitherBoss(
                        f7Floor.number() == 7,
                        f7Floor.master(),
                        bossPhaseObserved,
                        entity.getType() == EntityTypes.WITHER,
                    )
                ) continue
                // Entity type is available before the custom label; phase context limits this early signal.
                next.add(Target(entity, settings.mobFill.argb(), settings.mobOutline.argb()))
            }
            val scan = DungeonMobEspSupport.scan(
                client,
                settings.range.intValue(),
                false,
                { name -> DungeonSpecialMobRules.isF7WitherBossName(name) },
                false,
                false,
                null,
                { entity ->
                    DungeonSpecialMobRules.isF7WitherBoss(
                        f7Floor.number() == 7,
                        f7Floor.master(),
                        entity.getType() == EntityTypes.WITHER,
                        DungeonMobEspSupport.plainName(entity),
                    )
                },
            )
            for (entity in scan.matches()) {
                if (next.none { target -> target.entity === entity }) {
                    next.add(Target(entity, settings.mobFill.argb(), settings.mobOutline.argb()))
                }
            }
        }
        if (settings.partyClasses.value() && PartyListBackend.hasFreshSnapshot()) {
            // Party classes use the Player profile name and verified roster directly, not mob nameplates.
            val classes = HashMap<String, DungeonClass>()
            for (member in PartyListBackend.snapshot().members()) {
                if (member == null) continue
                val name = member.name() ?: continue
                val dungeonClass = member.dungeonClass() ?: continue
                classes[name.lowercase(Locale.ROOT)] = dungeonClass
            }
            for (entity in ClientEntitySnapshot.nearby(level, player, settings.range.intValue().toDouble())) {
                if (entity !is Player || entity === player || entity.isRemoved() || !entity.isAlive()) continue
                val dungeonClass = classes[entity.getName().getString().lowercase(Locale.ROOT)] ?: continue
                val color = classColor(dungeonClass).argb()
                next.add(Target(entity, settings.partyFill.argb(), color))
            }
        }
        targets = java.util.List.copyOf(next)
        mimicChests = if (settings.mimic.value() && DungeonContextTracker.isClearPhase()) {
            DungeonMimicChestDetector.detect(client, roomCoreScanner)
        } else {
            emptyList()
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val client = Minecraft.getInstance()
        if (client.level == null || client.player == null) return
        val player = client.player!!
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var drew = false
        for (target in targets) {
            val entity = target.entity
            if (entity == null || entity.isRemoved() || !entity.isAlive()
                || entity is ArmorStand
                || player.distanceToSqr(entity) > settings.range.intValue().toDouble() * settings.range.intValue()
            ) continue
            val bounds = entity.boundingBox
            EspRenderer.renderBox(
                pose,
                buffers,
                bounds.minX - camera.x,
                bounds.minY - camera.y,
                bounds.minZ - camera.z,
                bounds.xsize,
                bounds.ysize,
                bounds.zsize,
                target.fillColor,
                target.outlineColor,
                2.0f,
                settings.depthCheck.value(),
            )
            drew = true
        }
        if (settings.mimic.value() && DungeonContextTracker.isClearPhase()) {
            val range = settings.range.intValue().toDouble()
            val maxDistanceSquared = range * range
            for (chest in mimicChests) {
                if (chest == null || player.distanceToSqr(
                        chest.x + 0.5,
                        chest.y + 0.5,
                        chest.z + 0.5,
                    ) > maxDistanceSquared
                ) continue
                val bounds = AABB(
                    chest.x.toDouble(),
                    chest.y.toDouble(),
                    chest.z.toDouble(),
                    chest.x + 1.0,
                    chest.y + 1.0,
                    chest.z + 1.0,
                )
                EspRenderer.renderBox(
                    pose,
                    buffers,
                    bounds.minX - camera.x,
                    bounds.minY - camera.y,
                    bounds.minZ - camera.z,
                    bounds.xsize,
                    bounds.ysize,
                    bounds.zsize,
                    settings.mimicFill.argb(),
                    settings.mimicOutline.argb(),
                    2.0f,
                    settings.depthCheck.value(),
                )
                drew = true
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun classColor(dungeonClass: DungeonClass): ColorSetting = when (dungeonClass) {
        DungeonClass.TANK -> settings.tank
        DungeonClass.HEALER -> settings.healer
        DungeonClass.MAGE -> settings.mage
        DungeonClass.BERSERK -> settings.berserk
        DungeonClass.ARCHER -> settings.archer
    }

    private data class Target(
        val entity: LivingEntity?,
        val fillColor: Int,
        val outlineColor: Int,
    )

    private class Settings {
        val withers = BooleanSetting("F7 Wither Boss ESP", true)
        val mimic = BooleanSetting("Known Mimic Chest ESP", true)
        val mimicFill = ColorSetting("Mimic Chest Fill", 209, 29, 5, 160)
        val mimicOutline = ColorSetting("Mimic Chest Outline", 209, 29, 5, 255)
        val partyClasses = BooleanSetting("Party Only Class Highlight", false)
        val range = NumberSetting("Highlight Range", 8f, 128f, 64f, true)
        val depthCheck = BooleanSetting("Depth Check", true)
        val mobFill = ColorSetting("Special Mob Fill", 255, 80, 35, 48)
        val mobOutline = ColorSetting("Special Mob Outline", 255, 80, 35, 255)
        val tank = color("Tank Color", DungeonClass.TANK)
        val healer = color("Healer Color", DungeonClass.HEALER)
        val mage = color("Mage Color", DungeonClass.MAGE)
        val berserk = color("Berserk Color", DungeonClass.BERSERK)
        val archer = color("Archer Color", DungeonClass.ARCHER)
        val partyFill = ColorSetting("Party Fill", 0, 0, 0, 24)

        private fun color(name: String, dungeonClass: DungeonClass): ColorSetting {
            val color = DungeonSpecialMobRules.defaultClassColor(dungeonClass)
            return ColorSetting(name, color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF, 255)
        }
    }
}
