package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec

/** Click feedback for confirmed dungeon secrets, independent from drawing waypoint markers. */
class SecretClickedModule private constructor() : Module(
    "Secret Clicked",
    "Shows configurable box and chime feedback when dungeon secrets are clicked or collected.",
    Category.F7,
    false,
    boxes,
    boxStyle,
    boxColor,
    depthCheck,
    lockedColor,
    timeToStay,
    boxesInBoss,
    itemBoxes,
    chime,
    feedbackWindowMillis,
    allowSecretBlocksInBoss,
    chimeInBoss,
    sound,
    volume,
    pitch,
) {
    init {
        group(*spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = activeInContext(isEnabled(), DungeonContextTracker.inDungeon())

    override fun inactiveReason(): String? =
        inactiveReasonInContext(isEnabled(), DungeonContextTracker.inDungeon())

    fun mayShowBox(item: Boolean, bossPhase: Boolean): Boolean =
        allowsBoxFeedback(isEnabled(), boxes.value(), itemBoxes.value(), boxesInBoss.value(), item, bossPhase)

    fun mayPlayChime(bossPhase: Boolean): Boolean =
        allowsChimeFeedback(isEnabled(), chime.value(), chimeInBoss.value(), bossPhase)

    override fun onEnable() {
        SecretWaypointsModule.INSTANCE.onSecretClickedEnabled()
    }

    override fun onDisable() {
        SecretWaypointsModule.INSTANCE.onSecretClickedDisabled()
    }

    companion object {
        /** Static fields remain Java-visible for Secret Waypoints and config migration code. */
        @JvmStatic
        fun activeInContext(enabled: Boolean, inDungeon: Boolean): Boolean = enabled && inDungeon

        @JvmStatic
        fun inactiveReasonInContext(enabled: Boolean, inDungeon: Boolean): String? =
            if (enabled && !inDungeon) "Waiting for a dungeon" else null

        @JvmStatic
        fun allowsBoxFeedback(
            enabled: Boolean,
            boxesEnabled: Boolean,
            itemBoxesEnabled: Boolean,
            boxesInBossEnabled: Boolean,
            item: Boolean,
            bossPhase: Boolean,
        ): Boolean = enabled && boxesEnabled && (!item || itemBoxesEnabled) && (!bossPhase || boxesInBossEnabled)

        @JvmStatic
        fun allowsChimeFeedback(
            enabled: Boolean,
            chimeEnabled: Boolean,
            chimeInBossEnabled: Boolean,
            bossPhase: Boolean,
        ): Boolean = enabled && chimeEnabled && (!bossPhase || chimeInBossEnabled)

        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        private val spec = ModuleSettingsSpec.of("Secret Clicked")

        @JvmField
        val boxes = spec.toggle("boxes")

        @JvmField
        val boxStyle = spec.choice("boxStyle")

        @JvmField
        val boxColor = spec.color("boxColor")

        @JvmField
        val depthCheck = spec.toggle("depthCheck")

        @JvmField
        val lockedColor = spec.color("lockedColor")

        @JvmField
        val timeToStay = spec.slider("timeToStay")

        @JvmField
        val boxesInBoss = spec.toggle("boxesInBoss")

        @JvmField
        val itemBoxes = spec.toggle("itemBoxes")

        @JvmField
        val chime = spec.toggle("chime")

        @JvmField
        val feedbackWindowMillis = spec.slider("feedbackWindowMillis")

        @JvmField
        val allowSecretBlocksInBoss = spec.toggle("allowSecretBlocksInBoss")

        @JvmField
        val chimeInBoss = spec.toggle("chimeInBoss")

        @JvmField
        val sound = spec.text("sound")

        @JvmField
        val volume = spec.slider("volume")

        @JvmField
        val pitch = spec.slider("pitch")

        @JvmField
        val INSTANCE = SecretClickedModule()
    }
}
