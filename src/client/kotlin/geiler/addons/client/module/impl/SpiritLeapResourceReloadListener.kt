package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Rebuilds private POV resources in step with Minecraft's client-resource reload lifecycle. */
object SpiritLeapResourceReloadListener {
    private val shaderResource = Identifier.fromNamespaceAndPath(
        "geileraddons",
        "shaders/core/spirit_leap_camera.fsh",
    )
    private val diagnosticsEnabled = AtomicBoolean(false)
    private val shaderPreparationObservations = AtomicInteger()
    private const val MAX_SHADER_PREPARATION_OBSERVATIONS = 8

    @JvmStatic
    fun register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId() = geiler.addons.GeilerAddons.id("spirit_leap_pov")

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    val tick = Minecraft.getInstance().level?.gameTime ?: 0L
                    SpiritLeapMenuModule.recordEvent(
                        tick,
                        "POV_SHADER_RESOURCE ${safeShaderResourceState(resourceManager)}",
                    )
                    SpiritLeapPovRenderer.INSTANCE.onMainResourceReload(resourceManager)
                }
            },
        )
    }

    /** Enables a small thread-safe probe budget for shader preparation during resource reloads. */
    @JvmStatic
    fun setDiagnosticsEnabled(enabled: Boolean) {
        if (enabled) shaderPreparationObservations.set(0)
        diagnosticsEnabled.set(enabled)
    }

    /** Captures the exact file discovery state before Minecraft builds its shader source table. */
    @JvmStatic
    fun recordShaderPreparation(resourceManager: ResourceManager) {
        if (!diagnosticsEnabled.get()) return
        val observation = shaderPreparationObservations.updateAndGet { count ->
            (count + 1).coerceAtMost(MAX_SHADER_PREPARATION_OBSERVATIONS + 1)
        }
        if (observation > MAX_SHADER_PREPARATION_OBSERVATIONS) return

        // ShaderManager.prepare may run on a resource-preparation worker. This hook deliberately
        // reads no Minecraft singleton or mutable client state.
        GeilerAddonsLog.write(
            Category.F7,
            SpiritLeapMenuModule.INSTANCE.name(),
            0L,
            "POV_SHADER_PREPARE observation=$observation ${safeShaderResourceState(resourceManager)}",
        )
    }

    private fun safeShaderResourceState(resourceManager: ResourceManager): String =
        try {
            shaderResourceState(resourceManager)
        } catch (error: RuntimeException) {
            "probeError=${diagnosticToken(error.javaClass.simpleName, 64)}"
        }

    private fun shaderResourceState(resourceManager: ResourceManager): String {
        val available = resourceManager.getResource(shaderResource).isPresent
        val listed = resourceManager.listResources("shaders") { id ->
            id.namespace == shaderResource.namespace && id.path.endsWith(".fsh")
        }.containsKey(shaderResource)
        val namespaces = resourceManager.namespaces
        val allMatchingPacks = resourceManager.listPacks().toList()
            .filter { it.packId().contains("geileraddons", ignoreCase = true) }
        val packSamples = allMatchingPacks.take(4)
        val packSummary = packSamples.joinToString(";") { pack ->
            val packId = diagnosticToken(pack.packId(), 48)
            val namespacePresent = "geileraddons" in pack.getNamespaces(PackType.CLIENT_RESOURCES)
            val resourcePresent = pack.getResource(PackType.CLIENT_RESOURCES, shaderResource) != null
            "$packId(namespace=$namespacePresent,resource=$resourcePresent)"
        }.ifEmpty { "none" }
        val thread = diagnosticToken(Thread.currentThread().name, 40)
        val manager = diagnosticToken(resourceManager.javaClass.simpleName, 64)
        return "available=$available listed=$listed managerNamespace=${"geileraddons" in namespaces}" +
            " modPackCount=${allMatchingPacks.size} packSamples=$packSummary" +
            " manager=$manager thread=$thread" +
            " id=geileraddons:shaders/core/spirit_leap_camera"
    }

    private fun diagnosticToken(value: String, maxChars: Int): String = buildString(value.length.coerceAtMost(maxChars)) {
        value.take(maxChars).forEach { character ->
            append(if (Character.isISOControl(character)) ' ' else character)
        }
    }
}
