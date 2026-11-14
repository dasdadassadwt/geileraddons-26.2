package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.zombie.Zombie

/** Pure rules of the Mimic, Prince and Bat announcements. */
object MimicRules {
    private val PRINCE = Regex("^A Prince falls\\. \\+1 Bonus Score$")
    private val BAT = Regex("^A Bat has been slain\\. \\+1 Bonus Score$")

    /** The death-status entity event id. */
    const val DEATH_EVENT = 3

    @JvmStatic
    fun isMimicFloor(floor: Int?): Boolean = floor == 6 || floor == 7

    /** The Mimic is a baby zombie; its death arrives as entity event 3. */
    @JvmStatic
    fun isMimicDeath(eventId: Int, isZombie: Boolean, isBaby: Boolean): Boolean =
        eventId == DEATH_EVENT && isZombie && isBaby

    @JvmStatic
    fun isPrinceLine(plain: String): Boolean = PRINCE.matches(plain)

    @JvmStatic
    fun isBatLine(plain: String): Boolean = BAT.matches(plain)
}

/** Announces the Mimic, Prince and Bat kills to the party, after Odin's Mimic module. */
class MimicAnnouncerModule private constructor() : Module(
    "Mimic Announcer",
    "Detects and announces Mimic, Prince and Bat kills in dungeons.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val sendMimic = spec.toggle("sendMimic")
    private val sendPrince = spec.toggle("sendPrince")
    private val sendBat = spec.toggle("sendBat")

    private var mimicKilled = false
    private var princeKilled = false
    private var batsKilled = 0
    private var wasInDungeon = false
    private var announced = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        announced = 0L
        diagnostics.enable("mimic=${sendMimic.value()} prince=${sendPrince.value()} bat=${sendBat.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE announced=$announced")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration: forgets the run's kills once the dungeon is left. */
    fun tick() {
        val inDungeon = DungeonContextTracker.inDungeon()
        if (wasInDungeon && !inDungeon) {
            mimicKilled = false
            princeKilled = false
            batsKilled = 0
        }
        wasInDungeon = inDungeon
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "enabled=true inDungeon=$inDungeon mimicKilled=$mimicKilled princeKilled=$princeKilled " +
                "bats=$batsKilled announced=$announced suppressedEvents=$it"
        }
    }

    private fun inClear(): Boolean = DungeonContextTracker.inDungeon() && DungeonContextTracker.isMobEspPhase()

    private fun floorNumber(): Int? = DungeonContextTracker.currentFloor()?.number()

    /** `ClientPacketListener.handleEntityEvent` after the entity handled it: a baby zombie dying is the Mimic. */
    fun onEntityEvent(entity: Entity?, eventId: Int) {
        if (mimicKilled || !MimicRules.isMimicFloor(floorNumber()) || !inClear() || entity == null) return
        val zombie = entity as? Zombie
        if (!MimicRules.isMimicDeath(eventId, zombie != null, zombie?.isBaby == true)) return
        mimicKilled = true
        diagnostics.event("mimicKilled floor=${floorNumber()} announce=${isEnabled() && sendMimic.value()}")
        if (isEnabled() && sendMimic.value()) announce("Mimic Killed!")
    }

    fun onChatMessage(plain: String) {
        if (!inClear()) return
        when {
            !princeKilled && MimicRules.isPrinceLine(plain) -> {
                princeKilled = true
                diagnostics.event("princeKilled announce=${isEnabled() && sendPrince.value()}")
                if (isEnabled() && sendPrince.value()) announce("Prince Killed!")
            }
            MimicRules.isBatLine(plain) -> {
                batsKilled++
                diagnostics.event("batKilled total=$batsKilled announce=${isEnabled() && sendBat.value()}")
                if (isEnabled() && sendBat.value()) announce("Bat Killed!")
            }
        }
    }

    private fun announce(text: String) {
        announced++
        PlayerAlerts.command("pc $text")
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Mimic Announcer")

        @JvmField
        val INSTANCE = MimicAnnouncerModule()
    }
}
