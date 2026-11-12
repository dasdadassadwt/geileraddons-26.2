package geiler.addons.client.module

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping

/**
 * Shows a vanilla [KeyMapping] as an inline key-capture row on the module page and keeps both in sync:
 * capturing a key rebinds the mapping, and a rebind from Controls updates the row. The row is not
 * persisted; the mapping's own options.txt entry stays the source of truth.
 */
class KeyMappingMirror(private val mapping: KeyMapping, label: String) {
    val setting = TextSetting(label, label, mapping.saveString(), 48, true)
    private var lastMapping = mapping.saveString()

    /** Call once per client tick or settings layout; a no-op unless one side changed. */
    fun sync() {
        val now = mapping.saveString()
        if (now != lastMapping) {
            lastMapping = now
            setting.setValue(now)
        } else if (setting.value() != now) {
            mapping.setKey(InputConstants.getKey(setting.value()))
            KeyMapping.resetMapping()
            lastMapping = mapping.saveString()
            setting.setValue(lastMapping)
        }
    }
}
