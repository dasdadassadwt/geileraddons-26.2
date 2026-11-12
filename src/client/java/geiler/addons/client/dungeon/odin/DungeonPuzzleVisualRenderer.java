package geiler.addons.client.dungeon.odin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.CreeperPairVisual;
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.PuzzleVisualSnapshot;
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.WaterBoardVisual;
import geiler.addons.client.render.WorldRenderBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Java record and method facade for the Kotlin puzzle renderer logic. */
public final class DungeonPuzzleVisualRenderer {
	private DungeonPuzzleVisualRenderer() { }

	public static void render(PoseStack poseStack, WorldRenderBuffer.BufferSource buffers,
		SubmitNodeCollector nodeCollector, Vec3 cameraPosition, Vec3 tracerStart,
		PuzzleVisualSnapshot snapshot, Settings settings) {
		DungeonPuzzleVisualRendererLogic.render(poseStack, buffers, nodeCollector, cameraPosition,
			tracerStart, snapshot, settings);
	}

	@SuppressWarnings("unchecked")
	public static List<WaterTracerSegment> waterTracerSegments(WaterBoardVisual water, Vec3 tracerStart,
		WaterSettings settings) {
		return (List<WaterTracerSegment>) (List<?>)
			DungeonPuzzleVisualRendererLogic.waterTracerSegments(water, tracerStart, settings);
	}

	@SuppressWarnings("unchecked")
	public static List<VisibleCreeperPair> visibleCreeperPairs(List<CreeperPairVisual> pairs,
		CreeperSettings settings) {
		return (List<VisibleCreeperPair>) (List<?>)
			DungeonPuzzleVisualRendererLogic.visibleCreeperPairs(pairs, settings);
	}

	public static int creeperColor(int catalogIndex, float alpha) {
		return DungeonPuzzleVisualRendererLogic.creeperColor(catalogIndex, alpha);
	}

	public static int styledFillColor(int argb, BoxStyle style) {
		return DungeonPuzzleVisualRendererLogic.styledFillColor(argb, style);
	}

	public static int rgbaHexToArgb(int rgba) {
		return DungeonPuzzleVisualRendererLogic.rgbaHexToArgb(rgba);
	}

	public static BoxStyle boxStyle(String value) {
		return DungeonPuzzleVisualRendererLogic.boxStyle(value);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	public enum BoxStyle { FILLED, OUTLINE, FILLED_OUTLINE }

	public record Settings(WaterSettings water, CreeperSettings creeper,
		WeirdosSettings weirdos, QuizSettings quiz) {
		public Settings {
			if (water == null || creeper == null || weirdos == null || quiz == null) {
				throw new IllegalArgumentException("Puzzle render settings are required");
			}
		}

		public static Settings defaults() {
			return DungeonPuzzleVisualRendererLogic.defaults();
		}
	}

	public record WaterSettings(boolean showTracer, int firstColor, int secondColor) { }

	public record CreeperSettings(BoxStyle style, boolean showTracer, float alpha) {
		public CreeperSettings {
			style = style == null ? BoxStyle.FILLED_OUTLINE : style;
			alpha = Float.isFinite(alpha) ? clamp(alpha, 0.0f, 1.0f) : 0.70f;
		}
	}

	public record WeirdosSettings(BoxStyle style, int correctColor, int wrongColor) {
		public WeirdosSettings {
			style = style == null ? BoxStyle.FILLED_OUTLINE : style;
		}
	}

	public record QuizSettings(int color, boolean depthTested) { }

	static record WaterTracerSegment(Vec3 from, Vec3 to, int color) { }

	static record VisibleCreeperPair(int catalogIndex, CreeperPairVisual pair, int color) { }
}
