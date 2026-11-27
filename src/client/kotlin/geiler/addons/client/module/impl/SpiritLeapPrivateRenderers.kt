package geiler.addons.client.module.impl

import geiler.addons.client.mixin.LevelExtractorSpiritLeapAccessor
import geiler.addons.client.mixin.LevelRenderStateSpiritLeapAccessor
import geiler.addons.client.mixin.SectionOcclusionGraphSpiritLeapAccessor
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.state.level.LevelRenderState

/**
 * The world renderers of the Spirit Leap POV feeds: one per feed, never the main one. A second renderer misses three
 * things the main one gets, so this hands them on: the chunks already loaded when it is created ([seed]), later chunk
 * load and emptiness changes ([beforeMainFlip], because only the main extraction may consume them) and block changes
 * ([forwardDirty]). Render thread only.
 */
object SpiritLeapPrivateRenderers {
    /** Chunks around a teammate that a feed builds; keeps four extra renderers affordable. */
    const val VIEW_DISTANCE = 4

    class Entry(val renderer: LevelRenderer, val level: ClientLevel) {
        lateinit var extractor: LevelExtractor
        var seeded = false
        private var width = 0
        private var height = 0

        fun resize(newWidth: Int, newHeight: Int) {
            if (newWidth == width && newHeight == height) return
            width = newWidth
            height = newHeight
            renderer.resize(newWidth, newHeight)
        }
    }

    private val entries = ArrayList<Entry>(4)

    @JvmStatic
    fun cappedViewDistance(distance: Int): Int = minOf(distance, VIEW_DISTANCE)

    @JvmStatic
    fun isPrivate(renderer: LevelRenderer?): Boolean = renderer != null && entries.any { it.renderer === renderer }

    fun create(client: Minecraft, level: ClientLevel, state: LevelRenderState, width: Int, height: Int): Entry {
        val renderer = LevelRenderer(
            client.entityRenderDispatcher, client.blockEntityRenderDispatcher, client.modelManager, client.textureManager,
            client.atlasManager, client.shaderManager, client.gameRenderer, width, height,
        )
        check(renderer !== client.levelRenderer) { "private-renderer-is-the-main-renderer" }
        val entry = Entry(renderer, level)
        // Registered before the extractor exists: setLevel must already be treated as secondary.
        entries.add(entry)
        try {
            (renderer as LevelRenderStateSpiritLeapAccessor).`geiler$setLevelRenderState`(state)
            entry.extractor = LevelExtractor(client, state, renderer)
            entry.extractor.setLevel(level)
        } catch (failure: Throwable) {
            close(entry)
            throw failure
        }
        return entry
    }

    private fun graph(renderer: LevelRenderer): SectionOcclusionGraphSpiritLeapAccessor =
        renderer.sectionOcclusionGraph() as Any as SectionOcclusionGraphSpiritLeapAccessor

    /** After the first extraction (which resets the new renderer): copies what the main renderer knows is loaded. */
    fun seed(entry: Entry) {
        if (entry.seeded) return
        val main = graph(Minecraft.getInstance().levelRenderer)
        val target: SectionOcclusionGraph = entry.renderer.sectionOcclusionGraph()
        target.updateLoadedChunks(LongOpenHashSet(main.`geiler$loadedChunks`()), LongOpenHashSet())
        target.updateEmptySections(LongOpenHashSet(main.`geiler$emptySections`()), LongOpenHashSet())
        target.invalidate()
        entry.seeded = true
    }

    /** Just before the main extraction swaps away this tick's chunk changes. */
    @JvmStatic
    fun beforeMainFlip(chunks: ClientChunkCache) {
        for (entry in entries) {
            if (!entry.seeded) continue
            val graph = entry.renderer.sectionOcclusionGraph()
            graph.updateLoadedChunks(chunks.addedLoadedChunks(), chunks.removedLoadedChunks())
            graph.updateEmptySections(chunks.addedEmptySections(), chunks.removedEmptySections())
        }
    }

    /** A section the main extractor was told to rebuild. */
    @JvmStatic
    fun forwardDirty(source: LevelExtractor, x: Int, y: Int, z: Int, fromPlayer: Boolean) {
        if (entries.isEmpty() || source !== Minecraft.getInstance().levelExtractor) return
        for (entry in entries) {
            if (entry.seeded) {
                (entry.extractor as Any as LevelExtractorSpiritLeapAccessor).`geiler$setSectionDirty`(x, y, z, fromPlayer)
            }
        }
    }

    fun endFrame() {
        for (entry in entries) entry.renderer.endFrame()
    }

    fun close(entry: Entry) {
        if (entry !in entries) return
        try {
            entry.renderer.close()
        } finally {
            entries.remove(entry)
        }
    }

    /** For diagnostics. A main renderer that stops drawing shows up as `mainVisibleSections=0`. */
    fun stats(): String = "privateRenderers=${entries.size} " +
        "mainVisibleSections=${Minecraft.getInstance().levelRenderer.visibleSections().size} " +
        "povVisibleSections=${entries.joinToString("/") { it.renderer.visibleSections().size.toString() }}"
}
