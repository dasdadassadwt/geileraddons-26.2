package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.dungeon.helpers.DungeonPuzzleObserver.CreeperPairVisual;
import geiler.addons.client.dungeon.helpers.DungeonPuzzleObserver.WaterBoardVisual;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Modifier;
import java.util.List;

/** Pure checks for source-matched puzzle render planning and reference defaults. */
public final class DungeonPuzzleVisualRendererChecks {
	private DungeonPuzzleVisualRendererChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		javaBoundaryAbiAndRecordMetadata();
		helperRecordsKeepPackageVisibilityAndNullableValues();
		settingsKeepConstructorNormalizationAndValueSemantics();
		waterTracerUsesOnlyConfirmedUpcomingLevers();
		creeperPaletteKeepsCatalogOrderAndHidesCompletedPairs();
		stylesAndReferenceColorsPreserveAlphaSemantics();
	}

	private static void javaBoundaryAbiAndRecordMetadata() {
		Class<?>[] recordValues = {
			DungeonPuzzleVisualRenderer.Settings.class,
			DungeonPuzzleVisualRenderer.WaterSettings.class,
			DungeonPuzzleVisualRenderer.CreeperSettings.class,
			DungeonPuzzleVisualRenderer.WeirdosSettings.class,
			DungeonPuzzleVisualRenderer.QuizSettings.class,
			DungeonPuzzleVisualRenderer.WaterTracerSegment.class,
			DungeonPuzzleVisualRenderer.VisibleCreeperPair.class
		};
		check(java.util.Arrays.stream(recordValues).allMatch(Class::isRecord),
			"puzzle renderer values retain Java record metadata");
		try {
			check(Modifier.isPrivate(DungeonPuzzleVisualRenderer.class.getDeclaredConstructor().getModifiers()),
				"the renderer retains its private utility-class constructor");
			check(hasPublicConstructor(DungeonPuzzleVisualRenderer.Settings.class,
				DungeonPuzzleVisualRenderer.WaterSettings.class, DungeonPuzzleVisualRenderer.CreeperSettings.class,
				DungeonPuzzleVisualRenderer.WeirdosSettings.class, DungeonPuzzleVisualRenderer.QuizSettings.class)
				&& hasPublicConstructor(DungeonPuzzleVisualRenderer.CreeperSettings.class,
					DungeonPuzzleVisualRenderer.BoxStyle.class, boolean.class, float.class)
				&& hasPublicConstructor(DungeonPuzzleVisualRenderer.WeirdosSettings.class,
					DungeonPuzzleVisualRenderer.BoxStyle.class, int.class, int.class),
				"public renderer settings retain their constructor descriptors");
			check(DungeonPuzzleVisualRenderer.Settings.class.getMethod("water").getReturnType()
				== DungeonPuzzleVisualRenderer.WaterSettings.class
				&& DungeonPuzzleVisualRenderer.CreeperSettings.class.getMethod("alpha").getReturnType() == float.class
				&& DungeonPuzzleVisualRenderer.QuizSettings.class.getMethod("depthTested").getReturnType() == boolean.class,
				"renderer wrappers retain component accessor descriptors");
			check(Modifier.isStatic(DungeonPuzzleVisualRenderer.class.getMethod("render",
				com.mojang.blaze3d.vertex.PoseStack.class, geiler.addons.client.render.WorldRenderBuffer.BufferSource.class,
				net.minecraft.client.renderer.SubmitNodeCollector.class, net.minecraft.world.phys.Vec3.class,
				net.minecraft.world.phys.Vec3.class, geiler.addons.client.dungeon.helpers.DungeonPuzzleObserver.PuzzleVisualSnapshot.class,
				DungeonPuzzleVisualRenderer.Settings.class).getModifiers())
				&& Modifier.isStatic(DungeonPuzzleVisualRenderer.Settings.class.getMethod("defaults").getModifiers())
				&& Modifier.isStatic(DungeonPuzzleVisualRenderer.class.getMethod("boxStyle", String.class).getModifiers())
				&& Modifier.isStatic(DungeonPuzzleVisualRenderer.class.getDeclaredMethod("waterTracerSegments",
					WaterBoardVisual.class, net.minecraft.world.phys.Vec3.class,
					DungeonPuzzleVisualRenderer.WaterSettings.class).getModifiers()),
				"renderer entry points and Java static helpers retain their method descriptors");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("a renderer value-wrapper constructor, accessor, or static method changed", exception);
		}
	}

	private static boolean hasPublicConstructor(Class<?> type, Class<?>... parameterTypes) {
		try {
			return Modifier.isPublic(type.getConstructor(parameterTypes).getModifiers());
		} catch (NoSuchMethodException exception) {
			return false;
		}
	}

	private static boolean hasPackagePrivateConstructor(Class<?> type, Class<?>... parameterTypes) {
		try {
			int modifiers = type.getDeclaredConstructor(parameterTypes).getModifiers();
			return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers) && !Modifier.isPrivate(modifiers);
		} catch (NoSuchMethodException exception) {
			return false;
		}
	}

	private static void helperRecordsKeepPackageVisibilityAndNullableValues() {
		check(!Modifier.isPublic(DungeonPuzzleVisualRenderer.WaterTracerSegment.class.getModifiers())
			&& !Modifier.isPublic(DungeonPuzzleVisualRenderer.VisibleCreeperPair.class.getModifiers())
			&& hasPackagePrivateConstructor(DungeonPuzzleVisualRenderer.WaterTracerSegment.class,
				net.minecraft.world.phys.Vec3.class, net.minecraft.world.phys.Vec3.class, int.class)
			&& hasPackagePrivateConstructor(DungeonPuzzleVisualRenderer.VisibleCreeperPair.class,
				int.class, CreeperPairVisual.class, int.class),
			"renderer helper records retain package-private types and canonical constructors");

		var waterTracer = new DungeonPuzzleVisualRenderer.WaterTracerSegment(null, null, 0);
		var sameWaterTracer = new DungeonPuzzleVisualRenderer.WaterTracerSegment(null, null, 0);
		check(waterTracer.equals(sameWaterTracer) && waterTracer.hashCode() == sameWaterTracer.hashCode()
			&& waterTracer.toString().equals("WaterTracerSegment[from=null, to=null, color=0]"),
			"WaterTracerSegment accepts nullable references and retains record value semantics");

		var visiblePair = new DungeonPuzzleVisualRenderer.VisibleCreeperPair(0, null, 0);
		var sameVisiblePair = new DungeonPuzzleVisualRenderer.VisibleCreeperPair(0, null, 0);
		check(visiblePair.equals(sameVisiblePair) && visiblePair.hashCode() == sameVisiblePair.hashCode()
			&& visiblePair.toString().equals("VisibleCreeperPair[catalogIndex=0, pair=null, color=0]"),
			"VisibleCreeperPair accepts a nullable pair and retains record value semantics");
	}

	private static void settingsKeepConstructorNormalizationAndValueSemantics() {
		var defaultSettings = DungeonPuzzleVisualRenderer.Settings.defaults();
		var equalSettings = new DungeonPuzzleVisualRenderer.Settings(
			defaultSettings.water(),
			new DungeonPuzzleVisualRenderer.CreeperSettings(null, false, Float.NaN),
			new DungeonPuzzleVisualRenderer.WeirdosSettings(null,
				defaultSettings.weirdos().correctColor(), defaultSettings.weirdos().wrongColor()),
			defaultSettings.quiz());
		check(equalSettings.creeper().style() == DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE
			&& equalSettings.creeper().alpha() == 0.70f
			&& equalSettings.weirdos().style() == DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE,
			"renderer settings retain null-style and non-finite-alpha defaults");
		check(defaultSettings.equals(equalSettings) && defaultSettings.hashCode() == equalSettings.hashCode()
			&& defaultSettings.toString().startsWith("Settings[water="),
			"renderer settings retain record equality, hashing, and display");
		try {
			new DungeonPuzzleVisualRenderer.Settings(null, defaultSettings.creeper(), defaultSettings.weirdos(),
				defaultSettings.quiz());
			throw new AssertionError("missing renderer settings must be rejected");
		} catch (IllegalArgumentException expectedException) { }
	}

	private static void waterTracerUsesOnlyConfirmedUpcomingLevers() {
		var first = new DungeonPuzzleObserver.WorldBlock(1, 64, 2);
		var second = new DungeonPuzzleObserver.WorldBlock(5, 65, 8);
		var settings = DungeonPuzzleVisualRenderer.Settings.defaults().water();
		var start = new Vec3(10, 70, 12);

		equals(List.of(), DungeonPuzzleVisualRenderer.waterTracerSegments(
			new WaterBoardVisual(List.of(first, second), false), start, settings),
			"unconfirmed route draws no tracer");
		var segments = DungeonPuzzleVisualRenderer.waterTracerSegments(
			new WaterBoardVisual(List.of(first, second), true), start, settings);
		equals(2, segments.size(), "confirmed route draws camera-to-next and next-to-following segments");
		equals(start, segments.getFirst().from(), "first segment starts at the camera tracer origin");
		equals(new Vec3(1.5, 64.5, 2.5), segments.getFirst().to(), "first segment ends at the first upcoming lever center");
		equals(0xFF55FF55, segments.getFirst().color(), "first segment uses the saved green color");
		equals(new Vec3(5.5, 65.5, 8.5), segments.get(1).to(), "second segment ends at the second upcoming lever center");
		equals(0xFFFFAA00, segments.get(1).color(), "second segment uses the saved gold color");
	}

	private static void creeperPaletteKeepsCatalogOrderAndHidesCompletedPairs() {
		var progressed = new CreeperPairVisual(new DungeonPuzzleObserver.WorldBlock(0, 0, 0),
			new DungeonPuzzleObserver.WorldBlock(1, 0, 0), true, 0);
		var remaining = new CreeperPairVisual(new DungeonPuzzleObserver.WorldBlock(2, 0, 0),
			new DungeonPuzzleObserver.WorldBlock(3, 0, 0), false, 3);
		var visible = DungeonPuzzleVisualRenderer.visibleCreeperPairs(List.of(progressed, remaining),
			DungeonPuzzleVisualRenderer.Settings.defaults().creeper());
		equals(1, visible.size(), "completed pair is no longer rendered");
		equals(3, visible.getFirst().catalogIndex(), "remaining pair retains its source catalog index");
		equals(0xB200AAAA, visible.getFirst().color(), "source palette index survives skipped pairs and alpha is replaced once");
	}

	private static void stylesAndReferenceColorsPreserveAlphaSemantics() {
		var defaults = DungeonPuzzleVisualRenderer.Settings.defaults();
		equals(DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, defaults.creeper().style(),
			"Creeper uses saved filled-outline style");
		check(!defaults.creeper().showTracer(), "Creeper tracer defaults off");
		equals(0x5955FF55, DungeonPuzzleVisualRenderer.styledFillColor(defaults.weirdos().correctColor(),
			DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE), "filled-outline fill uses half of the marker alpha");
		equals(0xB255FF55, defaults.weirdos().correctColor(), "Weirdos green RGBA config is converted to ARGB");
		equals(0xB2FF5555, defaults.weirdos().wrongColor(), "Weirdos red RGBA config is converted to ARGB");
		equals(0xBF55FF55, defaults.quiz().color(), "Quiz RGBA config is converted to ARGB");
		check(!defaults.quiz().depthTested(), "Quiz answer highlight defaults through walls");
		equals(0xB2AA00AA, DungeonPuzzleVisualRenderer.creeperColor(7, 0.70f),
			"eighth Creeper palette color and opacity follow source alpha replacement");
		equals(DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE,
			DungeonPuzzleVisualRenderer.boxStyle("Filled Outline"), "saved user-facing style name is accepted");
		equals(DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE,
			DungeonPuzzleVisualRenderer.boxStyle("OUTLINE"), "normalized style id is accepted");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
