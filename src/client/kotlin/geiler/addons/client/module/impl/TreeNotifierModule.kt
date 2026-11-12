package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.TextSetting
import geiler.addons.client.tree.ChatSuppressor
import geiler.addons.client.tree.TreeFelled
import geiler.addons.client.tree.TreeType
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent

/**
 * Flashes a title when a tree gives a gift, and a separate one when a tree comes down.
 *
 * Two titles rather than one because they are two events, not two names for the same one: a
 * felling can happen without a gift, can arrive before one, and can repeat for a single tree.
 * Giving each its own wording and sound is what makes them tellable apart without looking at chat.
 */
class TreeNotifierModule private constructor(settings: Settings) : Module(
    "Tree Broken Notifier",
    "Shows a title when a tree is felled or gives a gift.",
    Category.FORAGING,
    settings.giftEnabled, settings.giftText, settings.giftSound, settings.giftPitch, settings.hideGiftChat,
    settings.felledEnabled, settings.felledText, settings.felledSound, settings.felledPitch,
    settings.hideFelledChat,
    settings.duration, settings.playSound, settings.volume,
) {
    private val giftEnabled = settings.giftEnabled
    private val giftText = settings.giftText
    private val giftSound = settings.giftSound
    private val giftPitch = settings.giftPitch
    private val hideGiftChat = settings.hideGiftChat

    private val felledEnabled = settings.felledEnabled
    private val felledText = settings.felledText
    private val felledSound = settings.felledSound
    private val felledPitch = settings.felledPitch
    private val hideFelledChat = settings.hideFelledChat

    private val duration = settings.duration
    private val playSound = settings.playSound
    private val volume = settings.volume

    private val suppressor = ChatSuppressor()
    /** The separator being withheld while the next line decides whether it opened a gift block. */
    private var heldLine: Component? = null

    private var lastGiftTitle = 0L
    /** Remembered so a felled line, which never names a tree, can still fill in the tree placeholder. */
    private var lastTree: TreeType? = null
    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 16, 100L, 1024)

    init {
        // Each trigger's section leads with the toggle that switches it on, the same way Tiki
        // Helper's do, so the first row decides whether the rest of the section matters.
        group(*settings.spec.groups.toTypedArray())
    }

    /** Just a carrier so the constructor can both build the settings and keep references to them. */
    private class Settings {
        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        val spec = ModuleSettingsSpec.of("Tree Broken Notifier")
        val giftEnabled = spec.toggle("giftEnabled")
        val giftText = spec.text("giftText")
        val giftSound = spec.text("giftSound")
        val giftPitch = spec.slider("giftPitch")
        val hideGiftChat = spec.toggle("hideGiftChat")

        val felledEnabled = spec.toggle("felledEnabled")
        val felledText = spec.text("felledText")
        val felledSound = spec.text("felledSound")
        val felledPitch = spec.slider("felledPitch")
        val hideFelledChat = spec.toggle("hideFelledChat")

        val duration = spec.slider("duration")
        val playSound = spec.toggle("playSound")
        val volume = spec.slider("volume")
    }

    // ---- triggering ---------------------------------------------------------------------

    /**
     * Current client entrypoint: fires titles and decides whether the line reaches chat.
     *
     * @return true if the caller should cancel the message, either because it is being hidden or
     *         because it is being held back for a moment
     */
    fun onChatMessage(content: Component?, message: String): Boolean = onChatMessage(message, content)

    /** Keeps the original Java-visible argument order for existing callers. */
    fun onChatMessage(message: String, content: Component?): Boolean {
        if (!isEnabled()) return false
        val now = System.currentTimeMillis()
        notifyFor(message, now)

        val decision = suppressor.accept(message, hideGiftChat.value(), hideFelledChat.value())
        if (decision.emitHeld) {
            emitHeld()
        } else {
            // Not emitted means deliberately discarded - it was the block's opening bar. Dropping
            // the reference matters: left set, the next end of tick would print it on its own.
            heldLine = null
        }
        return when (decision.action) {
            ChatSuppressor.Action.SHOW -> false
            ChatSuppressor.Action.HIDE -> true
            ChatSuppressor.Action.HOLD -> {
                heldLine = content
                true
            }
        }
    }

    private fun notifyFor(message: String, now: Long) {
        if (TreeFelled.matches(message)) {
            if (felledEnabled.value()) {
                show(felledText, felledSound, felledPitch, lastTree)
            }
            return
        }

        val type = TreeType.fromMessage(message) ?: return
        // Recorded even with the gift title switched off, so a later felling can still name the tree.
        lastTree = type
        if (!giftEnabled.value()) return
        if (now - lastGiftTitle < GIFT_WINDOW_MILLIS) return
        lastGiftTitle = now
        show(giftText, giftSound, giftPitch, type)
    }

    override fun onEnable() {
        diagnostics.enable("gift=${giftEnabled.value()},felled=${felledEnabled.value()}")
    }

    /** Call once per client tick: releases anything still held and closes an unterminated block. */
    fun tick() {
        if (isEnabled()) {
            diagnostics.advance()
            diagnostics.status { suppressed ->
                "gift=${giftEnabled.value()},felled=${felledEnabled.value()}" +
                    ",durationTicks=${Math.round(duration.value() * 20)},playSound=${playSound.value()}" +
                    ",suppressedEvents=$suppressed"
            }
        }
        if (suppressor.endOfTick()) {
            emitHeld()
        }
    }

    /**
     * Puts a withheld line back into chat.
     *
     * Re-added rather than un-cancelled because the decision to keep it can only be made after
     * the packet has already been handled; this is the same call the mod uses for its own
     * messages, so the line lands looking exactly as the server sent it.
     */
    private fun emitHeld() {
        val line = heldLine ?: return
        heldLine = null
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(line)
    }

    override fun onDisable() {
        diagnostics.disable()
        // Switching off mid-block must not strand a line that was never shown.
        suppressor.endOfTick()
        emitHeld()
        lastGiftTitle = 0L
        lastTree = null
    }

    private fun show(text: TextSetting, sound: TextSetting, pitch: NumberSetting, type: TreeType?) {
        val title = substitute(text.value(), type)
        val durationTicks = maxOf(1, Math.round(duration.value() * 20))
        TitleAlertStack.submit("tree.notifier", title, durationTicks, 0xFFFFFFFF.toInt())
        val soundResult = playSound(sound, pitch)
        writeTitleDiagnostic(durationTicks, title, sound, pitch, soundResult)
    }

    private fun playSound(sound: TextSetting, pitch: NumberSetting): String {
        if (!playSound.value()) return "disabled"
        val id = Identifier.tryParse(sound.value().trim()) ?: return "invalid-id"
        val event: SoundEvent = BuiltInRegistries.SOUND_EVENT.getValue(id) ?: return "unregistered"
        Minecraft.getInstance().getSoundManager()
            .play(SimpleSoundInstance.forUI(event, pitch.value(), volume.value()))
        return "played"
    }

    private fun substitute(text: String, type: TreeType?): String {
        val named = type ?: lastTree
        return text.replace(TREE_PLACEHOLDER, named?.displayName() ?: UNKNOWN_TREE)
    }

    private fun bounded(value: String?): String {
        if (value == null) return "none"
        val safe = value.replace('\r', ' ').replace('\n', ' ')
        return if (safe.length <= MAX_DIAGNOSTIC_TEXT) safe
        else safe.substring(0, MAX_DIAGNOSTIC_TEXT - 3) + "..."
    }

    private fun writeTitleDiagnostic(
        durationTicks: Int,
        title: String,
        sound: TextSetting,
        pitch: NumberSetting,
        soundResult: String,
    ) {
        diagnostics.event(
            "TITLE source=tree-notifier,durationTicks=$durationTicks,text=${bounded(title)}" +
                ",sound=$soundResult,soundId=${bounded(sound.value())},pitch=${pitch.value()}",
        )
    }

    companion object {
        @JvmField
        val INSTANCE = TreeNotifierModule(Settings())

        private const val TREE_PLACEHOLDER = "{tree}"
        private const val UNKNOWN_TREE = "Tree"
        private const val GIFT_WINDOW_MILLIS = 1000L
        private const val MAX_DIAGNOSTIC_TEXT = 96
    }
}
