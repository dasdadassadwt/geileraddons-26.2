package geiler.addons.client.module.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.BlendFunction;
import geiler.addons.client.dungeon.odin.SpiritLeapLayout;
import geiler.addons.client.dungeon.odin.SpiritLeapRoster;
import geiler.addons.client.dungeon.odin.SpiritLeapSelection;
import geiler.addons.client.mixin.Matrix4fStackAccess;
import geiler.addons.client.mixin.SkyblockerSpiritLeapSupport;
import geiler.addons.client.mixin.SodiumWorldRendererSpiritLeapSupport;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import net.fabricmc.fabric.impl.client.rendering.level.LevelExtractionContextImpl;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Focused acceptance checks for Leap card layout, colors, and user-facing settings. */
public final class SpiritLeapMenuChecks {
	private SpiritLeapMenuChecks() { }

	public static void run() {
		checkSettingsAndPalette();
		checkCameraHudPanels();
		checkEarlyGuiOrdering();
		checkDiagnosticSettings();
		checkCardGeometry();
		checkClassLabels();
		checkRosterPresenceAndEmptyRenderGate();
		checkLoadedPlayerStatusOverridesStaleRoster();
		checkRosterCardCache();
		checkRosterOrderingColorsAndEmptyMembers();
		checkRosterRecordCompatibility();
		checkSelectionRecordCompatibility();
		checkModuleRecordCompatibility();
		checkUnloadedTargetSelection();
		checkClickDispatchAdapter();
		checkKeyWhitespaceCompatibility();
		checkMouseQuadrantRouting();
		checkHoverTiming();
		checkDiagnosticEventBudget();
		checkPovTargetLifecycleGuards();
		checkPovGuiFramePublication();
		checkPovSessionTransitions();
		checkPovRendererCompatibility();
		checkSkyblockerCompatibilityHook();
		checkSodiumWorldRendererCompatibilityHook();
		checkFabricExtractionContextGate();
		checkFabricExtractionContextArgumentMapping();
		checkPovRendererDiagnostics();
		checkFixedCameraProbePolicy();
		checkPovQuadrantFeedsAndStaggering();
		checkConfiguredCameraCadence();
		checkPovCameraHistoryLifecycle();
		checkPovRenderAttemptLifecycle();
		checkSodiumAccessorWarmup();
		checkPovVisibilityAndFreeLookGuards();
		checkRendererStateRestoration();
		checkTemporaryCameraStateRestoration();
		checkPovTargetCleanupLifecycle();
		checkPovResourceReloadDeferral();
		checkPovModelViewRestoration();
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		run();
	}

	private static void checkSettingsAndPalette() {
		SpiritLeapMenuModule module = SpiritLeapMenuModule.INSTANCE;
		NumberSetting roundness = number(module, "Extra Roundness");
		NumberSetting distance = number(module, "Card Distance");
		require(roundness.min() == 0 && roundness.max() == 18 && roundness.intValue() == 9
			&& roundness.isInteger(), "roundness exposes the 0-18 integer slider with the Odin default");
		require(distance.min() == SpiritLeapLayout.MIN_DISTANCE_PERCENT
			&& distance.max() == SpiritLeapLayout.MAX_DISTANCE_PERCENT
			&& distance.value() == SpiritLeapLayout.DEFAULT_DISTANCE_PERCENT,
			"card distance exposes the bounded screen-relative default");

		ColorSetting tank = color(module, "Tank Class Color");
		ColorSetting berserk = color(module, "Berserk Class Color");
		ColorSetting mage = color(module, "Mage Class Color");
		ColorSetting archer = color(module, "Archer Class Color");
		ColorSetting healer = color(module, "Healer Class Color");
		require(tank.hex().equals("00AA00") && berserk.hex().equals("AA0000")
			&& mage.hex().equals("5555FF") && archer.hex().equals("FFAA00")
			&& healer.hex().equals("FF55FF"), "class color picker defaults match the requested palette");
		require(module.classColorArgb("Tank") == 0xFF00AA00
			&& module.classColorArgb("BERSERK") == 0xFFAA0000
			&& module.classColorArgb("Mage") == 0xFF5555FF
			&& module.classColorArgb("Archer") == 0xFFFFAA00
			&& module.classColorArgb("Healer") == 0xFFFF55FF,
			"case-insensitive class mapping selects each configurable Leap color");
		tank.set(1, 2, 3, 255);
		require(module.classColorArgb("tank") == 0xFF010203,
			"changing a per-class picker changes that class card color");
		tank.reset();

		BooleanSetting colorsEnabled = bool(module, "Class Colors");
		boolean prior = colorsEnabled.rawValue();
		try {
			colorsEnabled.setValue(true);
			require(module.isSettingVisible(tank), "class color pickers are visible when Class Colors is enabled");
			colorsEnabled.setValue(false);
			require(!module.isSettingVisible(tank), "class color pickers are hidden when Class Colors is disabled");
		} finally {
			colorsEnabled.setValue(prior);
		}
		List<Setting> allSettings = module.groups().stream().flatMap(group -> group.settings().stream()).toList();
		BooleanSetting showPovs = bool(module, "Show POVs");
		require(showPovs.rawValue(), "Show POVs is enabled by default");
		NumberSetting fps = number(module, "POV FPS");
		require(fps.min() == 1 && fps.max() == 30 && fps.intValue() == 4 && fps.isInteger()
			&& !fps.prefersTextInput(), "POV FPS offers the chosen 1-30 integer slider with default 4");
		NumberSetting transparency = number(module, "POV Transparency");
		require(transparency.min() == 0 && transparency.max() == 100 && transparency.intValue() == 0
			&& transparency.isInteger() && !transparency.prefersTextInput(),
			"POV Transparency offers a 0-100 integer slider with opaque default");
		boolean oldPovs = showPovs.rawValue();
		float oldFps = fps.value();
		float oldTransparency = transparency.value();
		try {
			showPovs.setValue(false);
			require(!module.isSettingVisible(fps), "POV FPS is hidden when camera panels are disabled");
			require(!module.isSettingVisible(transparency), "POV Transparency is hidden when camera panels are disabled");
			showPovs.setValue(true);
			require(module.isSettingVisible(fps), "POV FPS appears alongside enabled camera panels");
			require(module.isSettingVisible(transparency), "POV Transparency appears alongside enabled camera panels");
			fps.setValue(100);
			require(SpiritLeapMenuModule.povUpdatesPerSecond() == 30, "saved update rates are clamped to 30");
			fps.setValue(-1);
			require(SpiritLeapMenuModule.povUpdatesPerSecond() == 1, "saved update rates are clamped to 1");
			fps.setValue(15);
			require(SpiritLeapMenuModule.diagnosticSettings().contains("povFps=15"),
				"automatic settings diagnostics report the actual camera update rate");
			transparency.setValue(50);
			require(SpiritLeapMenuModule.povTransparencyPercent() == 50
				&& SpiritLeapMenuModule.diagnosticSettings().contains("povTransparency=50"),
				"saved transparency is reported in automatic settings diagnostics");
			transparency.setValue(100);
			require(SpiritLeapMenuModule.povTransparencyPercent() == 100,
				"maximum transparency remains available through the setting");
		} finally {
			showPovs.setValue(oldPovs);
			fps.setValue(oldFps);
			transparency.setValue(oldTransparency);
		}
		require(allSettings.stream().anyMatch(setting -> setting.displayName().equals("Show POVs")),
			"the independently rendered POV feed has a user-facing toggle");
		checkIncomingTitleHasNoMovableHud();
	}

	private static void checkCameraHudPanels() {
		var panelPipeline = SpiritLeapPovRenderer.cameraPanelPipeline();
		require(net.minecraft.client.renderer.RenderPipelines.getStaticPipelines().contains(panelPipeline),
			"the camera compositor participates in Minecraft's shader reload lifecycle");
		require(panelPipeline.getColorTargetState().blendFunction().orElseThrow().equals(BlendFunction.TRANSLUCENT)
			&& panelPipeline.getColorTargetState().writeMask() == 15
			&& panelPipeline.getFragmentShader().toString().equals("geileraddons:core/spirit_leap_camera"),
			"camera panels use source-over blending while preserving framebuffer RGB independently of framebuffer alpha");
		require(SpiritLeapPovRenderer.panelOpacityAlpha(0) == 255
			&& SpiritLeapPovRenderer.panelOpacityAlpha(50) == 128
			&& SpiritLeapPovRenderer.panelOpacityAlpha(100) == 0
			&& SpiritLeapPovRenderer.panelOpacityAlpha(-10) == 255
			&& SpiritLeapPovRenderer.panelOpacityAlpha(110) == 0,
			"camera panel alpha maps 0%, midpoint, and 100% transparency with bounded input");
		try {
			String shader = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "resources", "assets",
				"geileraddons", "shaders", "core", "spirit_leap_camera.fsh"));
			require(shader.contains("float alpha = vertexColor.a * ColorModulator.a;")
				&& shader.contains("fragColor = vec4(rgb, alpha);"),
				"camera shader applies the panel opacity without multiplying by framebuffer alpha");
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect the POV panel shader for the focused check", failure);
		}
		UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");
		var a = new SpiritLeapRoster.Member("Alice", "MAGE", 100, true);
		var b = new SpiritLeapRoster.Member("Bob", "ARCHER", 100, true);
		var empty = new SpiritLeapRoster.Member("", "", 0, false);
		var cards = List.of(new SpiritLeapRoster.Card(0, empty), new SpiritLeapRoster.Card(1, empty),
			new SpiritLeapRoster.Card(2, a), new SpiritLeapRoster.Card(3, empty));
		var panels = SpiritLeapCameraHud.reconcile(cards, Map.of("alice", alice), List.of(), 1921, 1081);
		require(panels.size() == 1 && panels.getFirst().quadrant() == 2
			&& panels.getFirst().targetId().equals(alice),
			"one real card creates exactly its own camera panel, including sparse class quadrants");
		require(panels.getFirst().bounds().equals(new SpiritLeapLayout.Bounds(0, 540, 960, 541)),
			"odd screen dimensions preserve exact quarter boundaries without painting a neighbor");
		var gap = SpiritLeapCameraHud.reconcile(cards, Map.of(), panels, 1921, 1081);
		require(gap.getFirst().targetId().equals(alice), "temporary entity gaps retain the same card UUID binding");
		var replacement = SpiritLeapCameraHud.reconcile(List.of(new SpiritLeapRoster.Card(2, b)),
			Map.of(), gap, 1921, 1081);
		require(replacement.size() == 1 && replacement.getFirst().targetId() == null,
			"a different card never inherits the previous player's cached identity");
		var sorted = SpiritLeapCameraHud.reconcile(List.of(new SpiritLeapRoster.Card(0, b),
			new SpiritLeapRoster.Card(3, a)), Map.of("alice", alice, "bob", bob), panels, 1920, 1080);
		require(sorted.size() == 2 && sorted.get(0).targetId().equals(bob)
			&& sorted.get(1).targetId().equals(alice) && sorted.get(1).quadrant() == 3,
			"camera bindings follow the sorted cards rather than a separate feed ordering");
		require(SpiritLeapCameraHud.reconcile(List.of(new SpiritLeapRoster.Card(0, a),
			new SpiritLeapRoster.Card(1, a), new SpiritLeapRoster.Card(8, b)),
			Map.of("alice", alice, "bob", bob), List.of(), 1920, 1080).size() == 1,
			"duplicate and invalid cards cannot create extra camera panels");
		require(SpiritLeapCameraHud.reconcile(List.of(), Map.of(), sorted, 1920, 1080).isEmpty(),
			"removing all cards removes all camera panels");
		for (int width : new int[] { 320, 1280, 1921 }) for (int height : new int[] { 240, 721 }) {
			long area = 0;
			for (int q = 0; q < 4; q++) {
				var bounds = SpiritLeapCameraHud.quadrantBounds(q, width, height);
				var blit = SpiritLeapPovRenderer.panelBlit(q, width, height);
				require(blit.x0() == bounds.x() && blit.y0() == bounds.y()
					&& blit.x1() == bounds.x() + bounds.width()
					&& blit.y1() == bounds.y() + bounds.height()
					&& blit.x1() > blit.x0() && blit.y1() > blit.y0(),
					"GPU texture blits use absolute endpoints with nonzero area in every quadrant");
				require(blit.u0() == 0.0F && blit.u1() == 1.0F
					&& blit.v0() == 1.0F && blit.v1() == 0.0F,
					"GPU texture blits sample the full framebuffer with the GUI overload's u0/u1/v0/v1 ordering");
				area += (long) bounds.width() * bounds.height();
				for (float distance : new float[] { 5.5f, 8.5f, 18f }) {
					var point = SpiritLeapCameraHud.samplePoint(q, width, height, distance, 8);
					if (point == null) continue;
					var layout = SpiritLeapLayout.calculate(width, height, distance);
					var card = layout.quadrant(q);
					int grow = layout.maxHoverGrowth();
					require(point.x() + 8 < card.x() - grow || point.x() - 8 >= card.x() + card.width() + grow
						|| point.y() + 8 < card.y() - grow || point.y() - 8 >= card.y() + card.height() + grow,
						"diagnostic patches exclude card pixels at every tested layout and hover size");
				}
			}
			require(area == (long) width * height, "fixed quarter panels exactly partition the viewport");
		}
	}

	private static void checkEarlyGuiOrdering() {
		SpiritLeapGuiOrdering.EarlyRenderMarker marker = new SpiritLeapGuiOrdering.EarlyRenderMarker();
		Object leapScreen = new Object();
		Object otherScreen = new Object();
		marker.beginGuiExtraction();
		marker.mark(leapScreen);
		require(!marker.consume(otherScreen), "an unrelated screen cannot consume the early Leap render marker");
		require(marker.consume(leapScreen) && !marker.consume(leapScreen),
			"the matching screen suppresses exactly one later duplicate extraction");
		marker.mark(leapScreen);
		marker.beginGuiExtraction();
		require(!marker.consume(leapScreen), "a new GUI extraction expires a stale early-render marker");
		try {
			String guiMixin = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "SpiritLeapGuiOrderingMixin.java"));
			String hudMixin = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "SpiritLeapHudOrderingMixin.java"));
			java.nio.file.Path kotlinOrderingPath = java.nio.file.Path.of("src", "client", "kotlin", "geiler",
				"addons", "client", "module", "impl", "SpiritLeapGuiOrdering.kt");
			java.nio.file.Path guiOrderingPath = java.nio.file.Files.exists(kotlinOrderingPath) ? kotlinOrderingPath
				: java.nio.file.Path.of("src", "client", "java", "geiler", "addons", "client", "module", "impl",
					"SpiritLeapGuiOrdering.java");
			String guiOrdering = java.nio.file.Files.readString(guiOrderingPath);
			String screenMixin = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "ScreenMixin.java"));
			String mixinConfig = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "resources",
				"geileraddons.client.mixins.json"));
			require(guiMixin.contains("extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V")
				&& guiMixin.contains("SpiritLeapGuiOrdering.beginGuiExtraction()")
				&& hudMixin.contains("extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
				&& hudMixin.contains("SpiritLeapGuiOrdering.renderBeforeHud(graphics)")
				&& hudMixin.contains("at = @At(\"HEAD\")"),
				"the Leap surface is queued before vanilla HUD extraction and stale markers are cleared per GUI frame");
			require(screenMixin.contains("SpiritLeapGuiOrdering.consumeEarlyRender(container)")
				&& mixinConfig.contains("\"SpiritLeapGuiOrderingMixin\""),
				"the later screen pass is registered and consumes the matching early render once");
			require(guiOrdering.contains("SpiritLeapSurfaceOwner.shouldSuppressPostLeapReplacement(screen)")
				&& (guiOrdering.contains("ExperimentSolverModule.INSTANCE.ownsScreen(screen)")
					|| guiOrdering.contains("ExperimentSolverModule.INSTANCE.ownsScreen(container)")),
				"early Leap extraction preserves transition suppression and experiment-screen ownership");
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect GUI ordering hooks for the focused check", failure);
		}
	}

	private static void checkIncomingTitleHasNoMovableHud() {
		try {
			java.nio.file.Path kotlinClientSource = java.nio.file.Path.of("src", "client", "kotlin",
				"geiler", "addons", "client", "GeilerAddonsClient.kt");
			java.nio.file.Path clientSourcePath = java.nio.file.Files.exists(kotlinClientSource) ? kotlinClientSource
				: java.nio.file.Path.of("src", "client", "java", "geiler", "addons", "client", "GeilerAddonsClient.java");
			String clientSource = java.nio.file.Files.readString(clientSourcePath);
			require(!clientSource.contains("SpiritLeapIncomingHudElement"),
				"incoming leap title is not registered as a movable HUD element");
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect HUD registrations for the focused check", failure);
		}
	}

	private static void checkDiagnosticSettings() {
		String oldTopLeft = SpiritLeapMenuModule.topLeftKey.value();
		String oldTopRight = SpiritLeapMenuModule.topRightKey.value();
		String oldBottomLeft = SpiritLeapMenuModule.bottomLeftKey.value();
		String oldBottomRight = SpiritLeapMenuModule.bottomRightKey.value();
		String oldArcher = SpiritLeapMenuModule.archerKey.value();
		String oldBerserk = SpiritLeapMenuModule.berserkKey.value();
		String oldHealer = SpiritLeapMenuModule.healerKey.value();
		String oldMage = SpiritLeapMenuModule.mageKey.value();
		String oldTank = SpiritLeapMenuModule.tankKey.value();
		String oldCustomOrder = SpiritLeapMenuModule.customOrder.value();
		String oldSort = SpiritLeapMenuModule.sort.value();
		String oldKeyMode = SpiritLeapMenuModule.keyMode.value();
		String oldSoundId = SpiritLeapMenuModule.soundId.value();
		boolean oldAnnounce = SpiritLeapMenuModule.announceLeaps.rawValue();
		boolean oldSound = SpiritLeapMenuModule.playSound.rawValue();
		boolean oldTitle = SpiritLeapMenuModule.incomingTitle.rawValue();
		float oldTitleDuration = SpiritLeapMenuModule.incomingTitleDurationTicks.value();
		try {
			SpiritLeapMenuModule.topLeftKey.setValue("left\n1");
			SpiritLeapMenuModule.topRightKey.setValue("right");
			SpiritLeapMenuModule.bottomLeftKey.setValue("up");
			SpiritLeapMenuModule.bottomRightKey.setValue("down");
			SpiritLeapMenuModule.archerKey.setValue("archer");
			SpiritLeapMenuModule.berserkKey.setValue("berserk");
			SpiritLeapMenuModule.healerKey.setValue("healer");
			SpiritLeapMenuModule.mageKey.setValue("mage");
			SpiritLeapMenuModule.tankKey.setValue("tank");
			SpiritLeapMenuModule.customOrder.setValue("Alpha, Beta,,Gamma");
			SpiritLeapMenuModule.sort.setValue("CUSTOM");
			SpiritLeapMenuModule.keyMode.setValue("CLASS");
			SpiritLeapMenuModule.announceLeaps.setValue(true);
			SpiritLeapMenuModule.playSound.setValue(false);
			SpiritLeapMenuModule.incomingTitle.setValue(true);
			SpiritLeapMenuModule.incomingTitleDurationTicks.setValue(77);
			SpiritLeapMenuModule.soundId.setValue("custom:sound\nbad");

			String diagnostic = SpiritLeapMenuModule.diagnosticSettings();
			require(diagnostic.contains("cornerKeys[tl=left_1,tr=right,bl=up,br=down]")
				&& diagnostic.contains("classKeys[archer=archer,berserk=berserk,healer=healer,mage=mage,tank=tank]"),
				"diagnostic settings include every bounded, sanitized configured Leap key");
			require(diagnostic.contains("customOrderEntries=3") && !diagnostic.contains("Alpha")
				&& !diagnostic.contains("Beta") && !diagnostic.contains("Gamma"),
				"custom order diagnostics report a bounded entry count without logging player names");
			require(diagnostic.contains("announceLeaps=true playArrivalSound=false incomingTitle=true titleTicks=77")
				&& diagnostic.contains("soundId=custom:sound_bad"),
				"diagnostic settings include arrival toggles, title duration, and a sanitized sound ID");
			require(diagnostic.length() <= 1024
				&& SpiritLeapMenuModule.boundedDiagnosticText("a\nb|c", 4).equals("a_b?~")
				&& SpiritLeapMenuModule.diagnosticCustomOrderEntryCount("A,".repeat(100)) == 48,
				"diagnostic values and custom order counts stay within fixed output bounds");
		} finally {
			SpiritLeapMenuModule.topLeftKey.setValue(oldTopLeft);
			SpiritLeapMenuModule.topRightKey.setValue(oldTopRight);
			SpiritLeapMenuModule.bottomLeftKey.setValue(oldBottomLeft);
			SpiritLeapMenuModule.bottomRightKey.setValue(oldBottomRight);
			SpiritLeapMenuModule.archerKey.setValue(oldArcher);
			SpiritLeapMenuModule.berserkKey.setValue(oldBerserk);
			SpiritLeapMenuModule.healerKey.setValue(oldHealer);
			SpiritLeapMenuModule.mageKey.setValue(oldMage);
			SpiritLeapMenuModule.tankKey.setValue(oldTank);
			SpiritLeapMenuModule.customOrder.setValue(oldCustomOrder);
			SpiritLeapMenuModule.sort.setValue(oldSort);
			SpiritLeapMenuModule.keyMode.setValue(oldKeyMode);
			SpiritLeapMenuModule.soundId.setValue(oldSoundId);
			SpiritLeapMenuModule.announceLeaps.setValue(oldAnnounce);
			SpiritLeapMenuModule.playSound.setValue(oldSound);
			SpiritLeapMenuModule.incomingTitle.setValue(oldTitle);
			SpiritLeapMenuModule.incomingTitleDurationTicks.setValue(oldTitleDuration);
		}
	}

	private static void checkCardGeometry() {
		SpiritLeapLayout.Layout standard = SpiritLeapLayout.calculate(1920, 1080,
			SpiritLeapLayout.DEFAULT_DISTANCE_PERCENT);
		require(standard.cardWidth() == 200 && standard.cardHeight() == 75,
			"standard viewports render Odin-sized cards");
		require(standard.topLeft().x() + standard.topRight().x() + standard.cardWidth() == 1920
			&& Math.abs(standard.topLeft().y() + standard.bottomLeft().y() + standard.cardHeight() - 1080) <= 1,
			"card cluster is symmetric around the screen center");
		assertNoHoverOverlap(standard, "default card distance keeps full hover growth separated");
		assertHoveredBoundsInScreen(standard, 1920, 1080,
			"standard hovered cards stay inside the viewport");

		SpiritLeapLayout.Layout minimum = SpiritLeapLayout.calculate(1920, 1080, -20);
		SpiritLeapLayout.Layout maximum = SpiritLeapLayout.calculate(1920, 1080, 100);
		require(SpiritLeapLayout.calculate(1920, 1080, Float.NaN).equals(standard)
			&& SpiritLeapLayout.calculate(1920, 1080, Float.POSITIVE_INFINITY).equals(standard)
			&& SpiritLeapLayout.calculate(1920, 1080, Float.NEGATIVE_INFINITY).equals(standard),
			"non-finite card distance falls back to the stable default layout");
		require(minimum.equals(SpiritLeapLayout.calculate(1920, 1080, SpiritLeapLayout.MIN_DISTANCE_PERCENT))
			&& maximum.equals(SpiritLeapLayout.calculate(1920, 1080, SpiritLeapLayout.MAX_DISTANCE_PERCENT)),
			"card distance inputs clamp exactly to the configured minimum and maximum");
		require(minimum.topLeft().x() >= 0 && minimum.topLeft().y() >= 0
			&& minimum.bottomRight().x() + minimum.cardWidth() <= 1920
			&& minimum.bottomRight().y() + minimum.cardHeight() <= 1080,
			"minimum distance is clamped to a non-overlapping in-bounds layout");
		require(maximum.topLeft().x() >= 0 && maximum.topLeft().y() >= 0
			&& maximum.bottomRight().x() + maximum.cardWidth() <= 1920
			&& maximum.bottomRight().y() + maximum.cardHeight() <= 1080,
			"maximum distance remains in bounds");
		assertNoHoverOverlap(minimum, "minimum card distance prevents overlap after hover growth");
		assertHoveredBoundsInScreen(minimum, 1920, 1080,
			"minimum-distance hovered cards stay inside the viewport");

		SpiritLeapLayout.Layout compact = SpiritLeapLayout.calculate(400, 200, 0);
		require(compact.cardWidth() < 200 && compact.cardHeight() <= 75
			&& compact.topLeft().x() >= 0 && compact.topLeft().y() >= 0
			&& compact.bottomRight().x() + compact.cardWidth() <= 400
			&& compact.bottomRight().y() + compact.cardHeight() <= 200,
			"small viewports shrink cards only enough to retain the centered cluster");
		assertNoHoverOverlap(compact, "compact cards remain separated after hover growth");
		assertHoveredBoundsInScreen(compact, 400, 200,
			"compact hovered cards stay fully inside the viewport");
	}

	private static void assertNoHoverOverlap(SpiritLeapLayout.Layout layout, String message) {
		var left = layout.topLeft();
		var right = layout.topRight();
		var top = layout.topLeft();
		var bottom = layout.bottomLeft();
		int hoverGrowth = layout.maxHoverGrowth();
		int horizontalGap = right.x() - (left.x() + left.width()) - 2 * hoverGrowth;
		int verticalGap = bottom.y() - (top.y() + top.height()) - 2 * hoverGrowth;
		require(horizontalGap >= 0 && verticalGap >= 0, message);
	}

	private static void assertHoveredBoundsInScreen(SpiritLeapLayout.Layout layout, int screenWidth,
		int screenHeight, String message) {
		int growth = layout.maxHoverGrowth();
		for (int quadrant = 0; quadrant < 4; quadrant++) {
			SpiritLeapLayout.Bounds bounds = layout.quadrant(quadrant);
			require(bounds.x() - growth >= 0 && bounds.y() - growth >= 0
				&& bounds.x() + bounds.width() + growth <= screenWidth
				&& bounds.y() + bounds.height() + growth <= screenHeight, message);
		}
	}

	private static void checkClassLabels() {
		require(SpiritLeapLayout.displayClassLabel("Archer", false).equals("ARCHER")
			&& SpiritLeapLayout.displayClassLabel("berserk", false).equals("BERSERK")
			&& SpiritLeapLayout.displayClassLabel("Healer", false).equals("HEALER"),
			"class labels display uppercase regardless of input casing");
		require(SpiritLeapLayout.displayClassLabel("Mage", true).equals("DEAD")
			&& SpiritLeapLayout.displayClassLabel(null, false).equals("Class unknown"),
			"dead and missing classes have stable display labels");
	}

	private static void checkUnloadedTargetSelection() {
		SpiritLeapRoster.Member unloaded = new SpiritLeapRoster.Member("RemoteTeammate", "Archer",
			0, false, false);
		List<SpiritLeapRoster.Card> cards = List.of(new SpiritLeapRoster.Card(0, unloaded));
		List<SpiritLeapSelection.MenuHead> heads = List.of(
			new SpiritLeapSelection.MenuHead(14, "RemoteTeammate", true));
		var target = SpiritLeapSelection.targetForQuadrant(true, "Spirit Leap", 0, cards, heads);
		require(target.isPresent() && target.get().containerSlot() == 14,
			"a matching menu head remains selectable when its teammate entity is not loaded");

		SpiritLeapRoster.Member dead = new SpiritLeapRoster.Member("RemoteTeammate", "Archer",
			0, false, true);
		var deadTarget = SpiritLeapSelection.targetForQuadrant(true, "Spirit Leap", 0,
			List.of(new SpiritLeapRoster.Card(0, dead)), heads);
		require(deadTarget.isEmpty(), "a dead teammate is rejected even when the menu head matches");
	}

	private static void checkRosterPresenceAndEmptyRenderGate() {
		List<SpiritLeapSelection.MenuHead> heads = List.of(
			new SpiritLeapSelection.MenuHead(11, "[MVP+] Archer", true),
			new SpiritLeapSelection.MenuHead(12, "Tankmate", true),
			new SpiritLeapSelection.MenuHead(13, "Mystery", true),
			new SpiritLeapSelection.MenuHead(14, "Zombie Head", true),
			new SpiritLeapSelection.MenuHead(15, "Self", true),
			new SpiritLeapSelection.MenuHead(10, "OutsideRange", true),
			new SpiritLeapSelection.MenuHead(9, "FakePlayer", false));
		List<SpiritLeapRoster.Member> known = List.of(
			new SpiritLeapRoster.Member("Archer", "Archer", 83, true, false),
			new SpiritLeapRoster.Member("Tankmate", "", 0, false, true));
		List<SpiritLeapRoster.Member> resolved = SpiritLeapRoster.resolveMenuHeads(heads, known, "Self");
		require(resolved.size() == 3 && resolved.get(0).name().equals("Archer")
			&& resolved.get(0).dungeonClass().equals("Archer") && resolved.get(0).online()
			&& resolved.get(1).dead() && resolved.get(2).dungeonClass().isEmpty(),
			"validated menu heads recover teammates and enrich class/status from available roster data");
		SpiritLeapRoster menuRoster = new SpiritLeapRoster();
		menuRoster.update(resolved, "Self");
		List<SpiritLeapRoster.Card> cards = menuRoster.cards(SpiritLeapRoster.Sort.NAME, List.of());
		Map<SpiritLeapSelection.Quadrant, String> corners = Map.of(
			SpiritLeapSelection.Quadrant.TOP_LEFT, "1");
		var keyed = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "1",
			SpiritLeapSelection.KeyMode.CORNERS, corners, Map.of(), cards, heads);
		var clicked = SpiritLeapSelection.targetForQuadrant(true, "Spirit Leap", 0, cards, heads);
		require(keyed.isPresent() && clicked.isPresent() && keyed.get().containerSlot() == 11
			&& clicked.get().containerSlot() == keyed.get().containerSlot(),
			"displayed fallback cards map key and click selection to the same live menu head slot");
		require(SpiritLeapLayout.displayClassLabel(cards.get(1).member().dungeonClass(), false)
			.equals("Class unknown"), "fallback cards label missing class data explicitly");
		require(SpiritLeapRoster.resolveMenuHeads(List.of(
			new SpiritLeapSelection.MenuHead(15, "Self", true)), List.of(), "Self").isEmpty(),
			"a local-player head alone does not activate the custom Leap screen");
	}

	private static void checkLoadedPlayerStatusOverridesStaleRoster() {
		require(!DungeonOdinAssistModule.isLoadedLeapMemberDead(true, 20.0f),
			"a loaded living teammate overrides stale dead status from party or tab metadata");
		require(DungeonOdinAssistModule.isLoadedLeapMemberDead(false, 20.0f)
			&& DungeonOdinAssistModule.isLoadedLeapMemberDead(true, 0.0f),
			"a loaded dead teammate is still marked dead when not alive or at zero health");
	}

	private static void checkRosterCardCache() {
		SpiritLeapRoster roster = new SpiritLeapRoster();
		roster.update(List.of(new SpiritLeapRoster.Member("Zed", "Archer", 70, true),
			new SpiritLeapRoster.Member("Amy", "Healer", 90, true)), "Local");
		SpiritLeapRoster.CardsCache cache = new SpiritLeapRoster.CardsCache();
		List<SpiritLeapRoster.Card> byName = cache.cards(roster, SpiritLeapRoster.Sort.NAME, "");
		require(cache.cards(roster, SpiritLeapRoster.Sort.NAME, "") == byName,
			"the card list is reused while the immutable roster and ordering settings stay unchanged");
		roster.update(List.of(new SpiritLeapRoster.Member("Zed", "Archer", 70, true),
			new SpiritLeapRoster.Member("Amy", "Healer", 90, true)), "Local");
		require(cache.cards(roster, SpiritLeapRoster.Sort.NAME, "") == byName,
			"an equivalent fresh client-tick roster snapshot keeps its immutable card list");
		List<SpiritLeapRoster.Card> custom = cache.cards(roster, SpiritLeapRoster.Sort.CUSTOM, "Zed,Amy");
		require(custom != byName && custom.get(0).member().name().equals("Zed"),
			"changing sort mode and custom order invalidates the cached cards");
		List<SpiritLeapRoster.Card> reordered = cache.cards(roster, SpiritLeapRoster.Sort.CUSTOM, "Amy,Zed");
		require(reordered != custom && reordered.get(0).member().name().equals("Amy"),
			"changing custom-order text invalidates and rebuilds card ordering");
		roster.update(List.of(new SpiritLeapRoster.Member("Charlie", "Tank", 100, true)), "Local");
		List<SpiritLeapRoster.Card> updated = cache.cards(roster, SpiritLeapRoster.Sort.CUSTOM, "Amy,Zed");
		require(updated != reordered && updated.get(0).member().name().equals("Charlie"),
			"a new immutable roster snapshot invalidates cached cards");
		cache.clear();
		List<SpiritLeapRoster.Card> afterClear = cache.cards(roster, SpiritLeapRoster.Sort.CUSTOM, "Amy,Zed");
		require(afterClear != updated && afterClear.get(0).member().name().equals("Charlie"),
			"lifecycle clear drops the derived card list and rebuilds only when requested again");
		cache.clear();
	}

	private static void checkRosterOrderingColorsAndEmptyMembers() {
		SpiritLeapRoster roster = new SpiritLeapRoster();
		roster.update(List.of(new SpiritLeapRoster.Member("zEd", "Mage", 40, true),
			new SpiritLeapRoster.Member("Bob", "Archer", 80, true),
			new SpiritLeapRoster.Member("alice", "Healer", 60, true),
			new SpiritLeapRoster.Member("Tankmate", "Tank", 95, true),
			new SpiritLeapRoster.Member("Local", "Berserk", 100, true),
			new SpiritLeapRoster.Member("  ", "Healer", 100, true)), "Local");
		require(roster.hasObservedTeammates() && roster.observed().size() == 4,
			"roster snapshots omit the local player and blank names");
		List<SpiritLeapRoster.Card> byName = roster.cards(SpiritLeapRoster.Sort.NAME, List.of());
		List<SpiritLeapRoster.Card> byClass = roster.cards(SpiritLeapRoster.Sort.CLASS, List.of());
		List<SpiritLeapRoster.Card> byHealth = roster.cards(SpiritLeapRoster.Sort.HEALTH, List.of());
		List<SpiritLeapRoster.Card> odin = roster.cards(SpiritLeapRoster.Sort.ODIN, List.of());
		require(byName.get(0).member().name().equals("alice") && byName.get(1).member().name().equals("Bob")
			&& byClass.get(0).member().name().equals("Bob") && byClass.get(1).member().name().equals("alice")
			&& byHealth.get(0).member().name().equals("Tankmate") && byHealth.get(3).member().name().equals("zEd"),
			"name, class, and health sorting preserve case-insensitive tie-breaking and descending health");
		require(odin.get(0).member().name().equals("Bob") && odin.get(1).member().name().equals("zEd")
			&& odin.get(2).member().name().equals("alice") && odin.get(3).member().name().equals("Tankmate"),
			"Odin sorting reserves each known class corner before filling the remaining quadrant");
		require(SpiritLeapRoster.classColorCode("ARCHER") == '6'
			&& SpiritLeapRoster.classColorCode("Berserker") == '4'
			&& SpiritLeapRoster.classColorCode("Healer") == 'd'
			&& SpiritLeapRoster.classColorCode("Mage") == 'b'
			&& SpiritLeapRoster.classColorCode("Tank") == '2'
			&& SpiritLeapRoster.classColorCode("unknown") == 'f'
			&& SpiritLeapRoster.classColorArgb("Mage") == 0xFF55FFFF
			&& SpiritLeapRoster.classColorArgb("unknown") == 0xFFFFFFFF,
			"class codes and packed colors preserve the Odin palette and unknown-class fallback");

		SpiritLeapRoster.Member blank = new SpiritLeapRoster.Member(" \t ", null, 0, false, false);
		List<SpiritLeapRoster.Card> emptyCards = SpiritLeapRoster.odinCards(List.of());
		roster.clear();
		require(blank.empty() && !roster.hasObservedTeammates() && emptyCards.size() == 4
			&& emptyCards.get(0).quadrant() == 0 && emptyCards.get(0).member().empty()
			&& emptyCards.get(3).quadrant() == 3 && emptyCards.get(3).member().empty(),
			"blank members, cleared rosters, and missing teammates retain four empty quadrant cards");
	}

	private static void checkRosterRecordCompatibility() {
		var memberComponents = SpiritLeapRoster.Member.class.getRecordComponents();
		var cardComponents = SpiritLeapRoster.Card.class.getRecordComponents();
		require(SpiritLeapRoster.Member.class.isRecord() && memberComponents != null
			&& memberComponents.length == 5
			&& memberComponents[0].getName().equals("name")
			&& memberComponents[0].getType() == String.class
			&& memberComponents[1].getName().equals("dungeonClass")
			&& memberComponents[1].getType() == String.class
			&& memberComponents[2].getName().equals("healthPercent")
			&& memberComponents[2].getType() == int.class
			&& memberComponents[3].getName().equals("online")
			&& memberComponents[3].getType() == boolean.class
			&& memberComponents[4].getName().equals("dead")
			&& memberComponents[4].getType() == boolean.class,
			"Member keeps the frozen Java record component names, order, and types");
		require(SpiritLeapRoster.Card.class.isRecord() && cardComponents != null
			&& cardComponents.length == 2
			&& cardComponents[0].getName().equals("quadrant")
			&& cardComponents[0].getType() == int.class
			&& cardComponents[1].getName().equals("member")
			&& cardComponents[1].getType() == SpiritLeapRoster.Member.class,
			"Card keeps the frozen Java record component names, order, and types");

		try {
			SpiritLeapRoster.Member.class.getConstructor(
				String.class, String.class, int.class, boolean.class, boolean.class);
			SpiritLeapRoster.Member.class.getConstructor(String.class, String.class, int.class, boolean.class);
			SpiritLeapRoster.Card.class.getConstructor(int.class, SpiritLeapRoster.Member.class);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("roster record constructors changed from the frozen public API", failure);
		}

		var normalized = new SpiritLeapRoster.Member(null, null, 150, true, false);
		var normalizedCopy = new SpiritLeapRoster.Member("", "", 100, true, false);
		var shortConstructor = new SpiritLeapRoster.Member(null, " Healer ", -5, false);
		require(normalized.name().isEmpty() && normalized.dungeonClass().isEmpty()
			&& normalized.healthPercent() == 100 && normalized.equals(normalizedCopy)
			&& normalized.hashCode() == normalizedCopy.hashCode()
			&& normalized.toString().equals(
				"Member[name=, dungeonClass=, healthPercent=100, online=true, dead=false]"),
			"Member keeps nullable-input normalization, health clamping, and record value methods");
		require(shortConstructor.name().isEmpty() && shortConstructor.dungeonClass().equals("Healer")
			&& shortConstructor.healthPercent() == 0 && !shortConstructor.dead(),
			"the four-argument Member overload keeps normalization and defaults dead to false");

		var nullableCard = new SpiritLeapRoster.Card(7, null);
		require(nullableCard.quadrant() == 7 && nullableCard.member() == null
			&& nullableCard.equals(new SpiritLeapRoster.Card(7, null))
			&& nullableCard.hashCode() == new SpiritLeapRoster.Card(7, null).hashCode()
			&& nullableCard.toString().equals("Card[quadrant=7, member=null]"),
			"Card preserves Java-record null-component, equality, and string behavior");
	}

	private static void checkSelectionRecordCompatibility() {
		checkRecordComponents(SpiritLeapSelection.MenuHead.class,
			new String[] { "containerSlot", "displayName", "playerHead" },
			int.class, String.class, boolean.class);
		checkRecordComponents(SpiritLeapSelection.Target.class,
			new String[] { "playerName", "dungeonClass", "containerSlot" },
			String.class, String.class, int.class);
		checkRecordComponents(SpiritLeapSelection.MouseAction.class,
			new String[] { "consumed", "target" },
			boolean.class, Optional.class);
		var targetComponent = SpiritLeapSelection.MouseAction.class.getRecordComponents()[1];
		require(targetComponent.getGenericType().getTypeName().equals(
			Optional.class.getTypeName() + "<" + SpiritLeapSelection.Target.class.getTypeName() + ">"),
			"MouseAction keeps its Optional<Target> generic component type");

		var head = new SpiritLeapSelection.MenuHead(11, null, true);
		checkRecordValue(head, new SpiritLeapSelection.MenuHead(11, null, true),
			new SpiritLeapSelection.MenuHead(11, "", true),
			"MenuHead[containerSlot=11, displayName=null, playerHead=true]", "MenuHead");
		var target = new SpiritLeapSelection.Target(null, null, 13);
		checkRecordValue(target, new SpiritLeapSelection.Target(null, null, 13),
			new SpiritLeapSelection.Target(null, "", 13),
			"Target[playerName=null, dungeonClass=null, containerSlot=13]", "Target");
		var action = new SpiritLeapSelection.MouseAction(true, null);
		checkRecordValue(action, new SpiritLeapSelection.MouseAction(true, null),
			new SpiritLeapSelection.MouseAction(false, null),
			"MouseAction[consumed=true, target=null]", "MouseAction");
		var targetedAction = new SpiritLeapSelection.MouseAction(true, Optional.of(target));
		checkRecordValue(targetedAction,
			new SpiritLeapSelection.MouseAction(true, Optional.of(new SpiritLeapSelection.Target(null, null, 13))),
			new SpiritLeapSelection.MouseAction(false, Optional.of(target)),
			"MouseAction[consumed=true, target=Optional[Target[playerName=null, dungeonClass=null, containerSlot=13]]]",
			"MouseAction with a target");
	}

	private static void checkModuleRecordCompatibility() {
		checkRecordComponents(SpiritLeapLayout.Bounds.class,
			new String[] { "x", "y", "width", "height" }, int.class, int.class, int.class, int.class);
		checkRecordComponents(SpiritLeapLayout.Layout.class,
			new String[] { "cardWidth", "cardHeight", "maxHoverGrowth", "topLeft", "topRight", "bottomLeft", "bottomRight" },
			int.class, int.class, int.class, SpiritLeapLayout.Bounds.class, SpiritLeapLayout.Bounds.class,
			SpiritLeapLayout.Bounds.class, SpiritLeapLayout.Bounds.class);
		checkRecordComponents(geiler.addons.client.dungeon.odin.SpiritLeapIncomingTracker.Position.class,
			new String[] { "playerName", "x", "y", "z" }, String.class, double.class, double.class, double.class);

		var recordBounds = new SpiritLeapLayout.Bounds(1, 2, 3, 4);
		checkRecordValue(recordBounds, new SpiritLeapLayout.Bounds(1, 2, 3, 4),
			new SpiritLeapLayout.Bounds(1, 2, 3, 5), "Bounds[x=1, y=2, width=3, height=4]", "Spirit Leap bounds");
		var recordLayout = new SpiritLeapLayout.Layout(3, 4, 5, recordBounds,
			new SpiritLeapLayout.Bounds(5, 6, 7, 8), new SpiritLeapLayout.Bounds(9, 10, 11, 12),
			new SpiritLeapLayout.Bounds(13, 14, 15, 16));
		checkRecordValue(recordLayout,
			new SpiritLeapLayout.Layout(3, 4, 5, new SpiritLeapLayout.Bounds(1, 2, 3, 4),
				new SpiritLeapLayout.Bounds(5, 6, 7, 8), new SpiritLeapLayout.Bounds(9, 10, 11, 12),
				new SpiritLeapLayout.Bounds(13, 14, 15, 16)),
			new SpiritLeapLayout.Layout(3, 4, 5, recordBounds, new SpiritLeapLayout.Bounds(5, 6, 7, 8),
				new SpiritLeapLayout.Bounds(9, 10, 11, 12), new SpiritLeapLayout.Bounds(13, 14, 15, 17)),
			"Layout[cardWidth=3, cardHeight=4, maxHoverGrowth=5, topLeft=Bounds[x=1, y=2, width=3, height=4], " +
				"topRight=Bounds[x=5, y=6, width=7, height=8], bottomLeft=Bounds[x=9, y=10, width=11, height=12], " +
				"bottomRight=Bounds[x=13, y=14, width=15, height=16]]", "Spirit Leap layout");
		var nullableLayout = new SpiritLeapLayout.Layout(-1, 0, 7, null, recordBounds, null, null);
		checkRecordValue(nullableLayout,
			new SpiritLeapLayout.Layout(-1, 0, 7, null, new SpiritLeapLayout.Bounds(1, 2, 3, 4), null, null),
			new SpiritLeapLayout.Layout(-1, 0, 7, null, new SpiritLeapLayout.Bounds(1, 2, 3, 5), null, null),
			"Layout[cardWidth=-1, cardHeight=0, maxHoverGrowth=7, topLeft=null, " +
				"topRight=Bounds[x=1, y=2, width=3, height=4], bottomLeft=null, bottomRight=null]",
			"Spirit Leap layout with nullable record components");
		require(nullableLayout.topLeft() == null && nullableLayout.bottomLeft() == null
			&& nullableLayout.bottomRight() == null && nullableLayout.quadrant(0) == null
			&& nullableLayout.quadrant(1).equals(recordBounds) && nullableLayout.quadrant(2) == null
			&& nullableLayout.quadrant(3) == null && nullableLayout.quadrant(-1) == null,
			"the canonical layout record constructor preserves nullable bounds and quadrant lookup behavior");
		var position = new geiler.addons.client.dungeon.odin.SpiritLeapIncomingTracker.Position(null, 1.0, 2.0, 3.0);
		checkRecordValue(position,
			new geiler.addons.client.dungeon.odin.SpiritLeapIncomingTracker.Position(null, 1.0, 2.0, 3.0),
			new geiler.addons.client.dungeon.odin.SpiritLeapIncomingTracker.Position(null, 1.0, 2.0, 4.0),
			"Position[playerName=null, x=1.0, y=2.0, z=3.0]", "Spirit Leap incoming position");

		checkRecordComponents(SpiritLeapCameraHud.Panel.class,
			new String[] { "quadrant", "targetName", "targetId", "bounds" },
			int.class, String.class, UUID.class, SpiritLeapLayout.Bounds.class);
		checkRecordComponents(SpiritLeapCameraHud.SamplePoint.class,
			new String[] { "x", "y" }, int.class, int.class);
		checkRecordComponents(SpiritLeapPovRenderer.PixelSampleRegion.class,
			new String[] { "x", "y", "width", "height" }, int.class, int.class, int.class, int.class);
		checkRecordComponents(SpiritLeapPovRenderer.PixelSampleSummary.class,
			new String[] { "pixels", "nonBlackPixels", "distinctColors" }, int.class, int.class, int.class);
		checkRecordComponents(SpiritLeapPovRenderer.CompatibilityDecision.class,
			new String[] { "maxFeeds", "mode", "reason" }, int.class, String.class, String.class);
		checkRecordComponents(SpiritLeapPovRenderer.PanelBlit.class,
			new String[] { "x0", "y0", "x1", "y1", "u0", "u1", "v0", "v1" },
			int.class, int.class, int.class, int.class, float.class, float.class, float.class, float.class);
		checkRecordComponents(SpiritLeapMenuModule.DiagnosticEventBudget.Rollover.class,
			new String[] { "tick", "suppressed" }, long.class, int.class);

		var panel = new SpiritLeapCameraHud.Panel(2, null, null, null);
		checkRecordValue(panel, new SpiritLeapCameraHud.Panel(2, null, null, null),
			new SpiritLeapCameraHud.Panel(2, "ArcherOne", null, null),
			"Panel[quadrant=2, targetName=null, targetId=null, bounds=null]", "Camera panel");
		checkRecordValue(new SpiritLeapCameraHud.SamplePoint(3, 4), new SpiritLeapCameraHud.SamplePoint(3, 4),
			new SpiritLeapCameraHud.SamplePoint(3, 5), "SamplePoint[x=3, y=4]", "Camera sample point");
		checkRecordValue(new SpiritLeapPovRenderer.PixelSampleRegion(1, 2, 3, 4),
			new SpiritLeapPovRenderer.PixelSampleRegion(1, 2, 3, 4),
			new SpiritLeapPovRenderer.PixelSampleRegion(1, 2, 3, 5),
			"PixelSampleRegion[x=1, y=2, width=3, height=4]", "Pixel sample region");
		checkRecordValue(new SpiritLeapPovRenderer.PixelSampleSummary(4, 2, 3),
			new SpiritLeapPovRenderer.PixelSampleSummary(4, 2, 3),
			new SpiritLeapPovRenderer.PixelSampleSummary(4, 1, 3),
			"PixelSampleSummary[pixels=4, nonBlackPixels=2, distinctColors=3]", "Pixel sample summary");
		checkRecordValue(new SpiritLeapPovRenderer.CompatibilityDecision(0, null, null),
			new SpiritLeapPovRenderer.CompatibilityDecision(0, null, null),
			new SpiritLeapPovRenderer.CompatibilityDecision(0, "closed", null),
			"CompatibilityDecision[maxFeeds=0, mode=null, reason=null]", "Renderer compatibility decision");
		checkRecordValue(new SpiritLeapPovRenderer.PanelBlit(1, 2, 3, 4, 0.0F, 1.0F, 1.0F, 0.0F),
			new SpiritLeapPovRenderer.PanelBlit(1, 2, 3, 4, 0.0F, 1.0F, 1.0F, 0.0F),
			new SpiritLeapPovRenderer.PanelBlit(1, 2, 3, 5, 0.0F, 1.0F, 1.0F, 0.0F),
			"PanelBlit[x0=1, y0=2, x1=3, y1=4, u0=0.0, u1=1.0, v0=1.0, v1=0.0]", "Camera panel blit");
		checkRecordValue(new SpiritLeapMenuModule.DiagnosticEventBudget.Rollover(8L, 3),
			new SpiritLeapMenuModule.DiagnosticEventBudget.Rollover(8L, 3),
			new SpiritLeapMenuModule.DiagnosticEventBudget.Rollover(8L, 4),
			"Rollover[tick=8, suppressed=3]", "Diagnostic event rollover");
	}

	private static void checkRecordComponents(Class<?> type, String[] names, Class<?>... componentTypes) {
		var components = type.getRecordComponents();
		require(type.isRecord() && components != null && components.length == names.length
			&& componentTypes.length == names.length,
			type.getSimpleName() + " retains Java record metadata and component count");
		for (int index = 0; index < names.length; index++) {
			require(components[index].getName().equals(names[index])
				&& components[index].getType() == componentTypes[index]
				&& components[index].getAccessor().getName().equals(names[index])
				&& components[index].getAccessor().getReturnType() == componentTypes[index],
				type.getSimpleName() + " retains record component " + index + " name, order, and type");
		}
		try {
			type.getConstructor(componentTypes);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(type.getSimpleName() + " retains its public canonical constructor", failure);
		}
	}

	private static void checkRecordValue(Object value, Object equalCopy, Object different,
		String expectedToString, String label) {
		require(value.equals(equalCopy) && equalCopy.equals(value)
			&& value.hashCode() == equalCopy.hashCode() && !value.equals(different)
			&& value.toString().equals(expectedToString),
			label + " preserves nullable Java record equality, hash, and toString behavior");
	}

	private static void checkClickDispatchAdapter() {
		BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		List<SpiritLeapRoster.Card> cards = List.of(new SpiritLeapRoster.Card(0,
			new SpiritLeapRoster.Member("Remote", "Archer", 100, true)));
		Optional<SpiritLeapSelection.Target> target = SpiritLeapSelection.targetForQuadrant(true,
			"Spirit Leap", 0, cards, List.of(new SpiritLeapSelection.MenuHead(14, "Remote", true)));
		require(target.isPresent() && target.orElseThrow().containerSlot() == 14,
			"the accepted card maps to its live player-head slot before adapter dispatch");

		LeapDispatchMenu menu = new LeapDispatchMenu();
		Slot expectedSlot = menu.slots.get(14);
		Slot[] dispatchedSlot = {null};
		int[] dispatchedSlotId = {-1};
		int[] dispatchedButton = {-1};
		ContainerInput[] dispatchedInput = {null};
		int[] dispatchCount = {0};
		boolean dispatched = DungeonOdinAssistModule.dispatchSpiritLeapTarget(menu, target,
			(slot, slotId, button, input) -> {
				dispatchedSlot[0] = slot;
				dispatchedSlotId[0] = slotId;
				dispatchedButton[0] = button;
				dispatchedInput[0] = input;
				dispatchCount[0]++;
			});
		require(expectedSlot.getItem().is(Items.PLAYER_HEAD) && dispatched && dispatchCount[0] == 1
			&& dispatchedSlot[0] == expectedSlot && dispatchedSlotId[0] == 14
			&& dispatchedButton[0] == 0 && dispatchedInput[0] == ContainerInput.PICKUP,
			"production adapter dispatches the resolved slot with button 0 and vanilla PICKUP exactly once");
	}

	private static void checkMouseQuadrantRouting() {
		List<SpiritLeapRoster.Card> cards = SpiritLeapRoster.odinCards(List.of(
			new SpiritLeapRoster.Member("Remote", "Archer", 100, true)));
		List<SpiritLeapSelection.MenuHead> heads = List.of(
			new SpiritLeapSelection.MenuHead(14, "Remote", true));
		boolean occupiedButtonsResolve = true;
		boolean emptyButtonsPassThrough = true;
		for (int button = 0; button <= 2; button++) {
			var occupied = SpiritLeapSelection.mouseAction(button, true, "Spirit Leap", 0, cards, heads,
				SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
			var empty = SpiritLeapSelection.mouseAction(button, true, "Spirit Leap", 1, cards, heads,
				SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
			occupiedButtonsResolve &= occupied.consumed() && occupied.target().isPresent()
				&& occupied.target().orElseThrow().containerSlot() == 14;
			emptyButtonsPassThrough &= !empty.consumed() && empty.target().isEmpty();
		}
		var occupiedWithoutSlot = SpiritLeapSelection.mouseAction(2, true, "Spirit Leap", 0, cards, List.of(),
			SpiritLeapSelection.MouseTiming.PRESS, SpiritLeapSelection.MousePhase.PRESS);
		require(occupiedButtonsResolve && emptyButtonsPassThrough
			&& occupiedWithoutSlot.consumed() && occupiedWithoutSlot.target().isEmpty(),
			"left, right, and middle clicks select populated quadrants, pass empty quadrants through, and swallow unresolved occupied cards");
	}

	private static void checkKeyWhitespaceCompatibility() {
		List<SpiritLeapRoster.Card> cards = List.of(new SpiritLeapRoster.Card(0,
			new SpiritLeapRoster.Member("Remote", "Archer", 100, true)));
		Optional<SpiritLeapSelection.Target> target = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "\u00a0",
			SpiritLeapSelection.KeyMode.CORNERS,
			Map.of(SpiritLeapSelection.Quadrant.TOP_LEFT, "\u00a0"), Map.of(), cards,
			List.of(new SpiritLeapSelection.MenuHead(14, "Remote", true)));
		require(target.isPresent() && target.orElseThrow().containerSlot() == 14,
			"key blankness preserves Java String.isBlank semantics for non-breaking-space bindings");
	}

	private static void checkDiagnosticEventBudget() {
		SpiritLeapMenuModule.DiagnosticEventBudget budget = new SpiritLeapMenuModule.DiagnosticEventBudget(16);
		budget.roll(40);
		for (int i = 0; i < 16; i++) require(budget.admit(), "diagnostic event " + i + " stays within the per-tick cap");
		require(!budget.admit() && !budget.admit() && budget.emitted() == 16 && budget.suppressed() == 2,
			"diagnostic events beyond the cap are suppressed and counted");
		SpiritLeapMenuModule.DiagnosticEventBudget.Rollover rollover = budget.roll(41);
		require(rollover.tick() == 40 && rollover.suppressed() == 2
			&& budget.admit() && budget.emitted() == 1 && budget.suppressed() == 0,
			"suppression is summarized at the next tick and the event budget resets");
	}

	private static void checkPovTargetLifecycleGuards() {
		SpiritLeapRoster.Card eligible = new SpiritLeapRoster.Card(2,
			new SpiritLeapRoster.Member("Remote", "Healer", 100, true, false));
		SpiritLeapRoster.Card moved = new SpiritLeapRoster.Card(1, eligible.member());
		SpiritLeapRoster.Card dead = new SpiritLeapRoster.Card(2,
			new SpiritLeapRoster.Member("Remote", "Healer", 0, true, true));
		SpiritLeapRoster.Card offline = new SpiritLeapRoster.Card(2,
			new SpiritLeapRoster.Member("Remote", "Healer", 100, false, false));
		require(SpiritLeapPovRenderer.isFeedEligible("remote", 2, List.of(eligible)),
			"the cached POV target stays valid only while present in its current quadrant");
		require(!SpiritLeapPovRenderer.isFeedEligible("Remote", 2, List.of(moved))
			&& !SpiritLeapPovRenderer.isFeedEligible("Remote", 2, List.of(dead))
			&& !SpiritLeapPovRenderer.isFeedEligible("Remote", 2, List.of(offline))
			&& !SpiritLeapPovRenderer.isFeedEligible("Remote", 2, List.of()),
			"the cached POV target is invalidated when moved, dead, offline, or absent");
		require(SpiritLeapPovRenderer.cameraTypeFor(true, CameraType.THIRD_PERSON_BACK) == CameraType.FIRST_PERSON
			&& SpiritLeapPovRenderer.cameraTypeFor(true, CameraType.THIRD_PERSON_FRONT) == CameraType.FIRST_PERSON
			&& SpiritLeapPovRenderer.cameraTypeFor(false, CameraType.THIRD_PERSON_BACK) == CameraType.THIRD_PERSON_BACK,
			"the secondary camera is forced to first person without changing the configured camera type");
		require(SpiritLeapPovRenderer.shouldReleaseForInactiveMenu(false, true)
			&& !SpiritLeapPovRenderer.shouldReleaseForInactiveMenu(true, true)
			&& !SpiritLeapPovRenderer.shouldReleaseForInactiveMenu(false, false),
			"POV renderer resources are released when the menu becomes inactive");
		String longFailureType = SpiritLeapPovRenderer.boundedFailureType("RenderFailure".repeat(8));
		require(longFailureType.length() <= 33 && longFailureType.endsWith("~"),
			"POV cleanup failure diagnostics cap and mark long exception type names");
	}

	private static void checkPovRendererCompatibility() {
		SpiritLeapPovRenderer.CompatibilityDecision vanilla = classifyProfile(null, null, true);
		require(vanilla.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& vanilla.mode().equals("four-feed-automatic")
			&& vanilla.reason().equals("vanilla-gpu-backend-capabilities"),
			"the pinned vanilla GPU capability profile enables four feeds automatically");
		SpiritLeapPovRenderer.CompatibilityDecision vulkan =
			SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				"Vulkan", null, null, true);
		require(vulkan.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& vulkan.reason().equals("vanilla-gpu-backend-capabilities"),
			"the backend-neutral private render path enables vanilla Vulkan after the same capability gate");

		SpiritLeapPovRenderer.CompatibilityDecision sodiumClosed = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, true);
		require(sodiumClosed.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& sodiumClosed.mode().equals("four-feed-automatic"),
			"the exact pinned Sodium renderer enables four feeds after target-capability preflight");
		SpiritLeapPovRenderer.CompatibilityDecision sodium091 = classifyProfile(
			SodiumWorldRendererSpiritLeapSupport.VERIFIED_0_9_1_VERSION, null, true);
		require(sodium091.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& sodium091.reason().equals("pinned-sodium-capabilities"),
			"verified Sodium 0.9.1 on Minecraft 26.2 opens the same preflight-gated Vulkan feed path");
		SpiritLeapPovRenderer.CompatibilityDecision sodiumAdapterClosed = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, false);
		require(sodiumAdapterClosed.maxFeeds() == 0
			&& sodiumAdapterClosed.reason().equals("sodium-target-adapter-unverified"),
			"Sodium stays closed when its terrain-target capability preflight fails");

		SpiritLeapPovRenderer.CompatibilityDecision nvidiumSupported = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true);
		require(nvidiumSupported.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& nvidiumSupported.mode().equals("four-feed-automatic")
			&& nvidiumSupported.reason().equals("pinned-sodium-nvidium-capabilities"),
			"the exact Nvidium 26.2 build opens POV feeds with the supported Sodium version");
		SpiritLeapPovRenderer.CompatibilityDecision nvidiumLegacy = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, "0.4.4-beta6-26.1", true);
		require(nvidiumLegacy.maxFeeds() == 0 && nvidiumLegacy.reason().equals("nvidium-version-unverified"),
			"the pinned 26.1 Nvidium build remains closed on Minecraft 26.2");
		SpiritLeapPovRenderer.CompatibilityDecision nvidiumVulkan =
			SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				"Vulkan", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION,
				SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true);
		require(nvidiumVulkan.maxFeeds() == 0 && nvidiumVulkan.reason().equals("nvidium-backend-unverified"),
			"Nvidium stays closed on Vulkan while its POV integration is OpenGL-only");

		require(classifyProfile("0.9.3+mc26.2", null, true).maxFeeds() == 0
			&& classifyProfile("0.9.2", null, true).maxFeeds() == 0
			&& classifyProfile(SodiumWorldRendererSpiritLeapSupport.VERIFIED_0_9_1_VERSION,
				SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true).maxFeeds() == 0
			&& classifyProfile(SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, "0.4.4-beta7-26.1", true).maxFeeds() == 0
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.1.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, true).maxFeeds() == 0
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.4", "0.161.0+26.2",
				"OpenGL", null, null, true).maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "unknown", "0.161.0+26.2",
				"OpenGL", null, null, true).maxFeeds() == 0
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				"Metal", null, null, true).maxFeeds() == 0
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				"unavailable", null, null, true).maxFeeds() == 0
			&& SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				null, null, null, true).maxFeeds() == 0
			&& classifyProfile(null, SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true).maxFeeds() == 0,
			"unknown renderer versions, missing loader metadata, unsupported backends, Minecraft mismatch, and Nvidium alone fail closed");
		require(SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"Vulkan", SodiumWorldRendererSpiritLeapSupport.VERIFIED_0_9_1_VERSION, null)
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", SodiumWorldRendererSpiritLeapSupport.VERIFIED_0_9_1_VERSION,
				SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION)
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, "0.4.4-beta6-26.1")
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"Vulkan", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.99", "0.161.99+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5", "0.161.0+26.2",
				"Vulkan", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "unknown", "0.161.0+26.2",
				"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null),
			"Sodium preflight checks required loader/API metadata without pinning their patch versions");
		for (String modId : List.of("iris", "canvas", "distanthorizons")) {
			SpiritLeapPovRenderer.CompatibilityDecision blockedModifier =
				SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
					"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, modId, true);
			require(SpiritLeapPovRenderer.firstUnverifiedRenderModifier(Set.of(modId)).equals(modId)
				&& blockedModifier.maxFeeds() == 0
				&& blockedModifier.reason().equals("unverified-render-modifier-" + modId)
				&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
					"0.161.0+26.2", "OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, modId),
				"unverified renderer modifier " + modId + " blocks activation and Sodium preflight");
		}
		require(SpiritLeapPovRenderer.firstUnverifiedRenderModifier(Set.of("unrelated-render-mod", "vulkanmod")) == null,
			"unrelated mods and the Vulkan backend provider stay outside the unverified modifier blocklist");
	}

	private static void checkPovSessionTransitions() {
		SpiritLeapPovRenderer.PovSessionState state = new SpiritLeapPovRenderer.PovSessionState();
		require(!state.publishAfterGuiPass(false, true, true) && !state.warmupPromoted(),
			"a private-render failure cannot publish or promote the warmup feed");
		require(!state.publishAfterGuiPass(true, false, true) && !state.warmupPromoted(),
			"a failed state restore cannot publish or promote the warmup feed");
		require(!state.publishAfterGuiPass(true, true, false) && !state.warmupPromoted(),
			"a completed private render waits for the deferred GUI pass before publication");
		require(state.publishAfterGuiPass(true, true, true) && state.warmupPromoted(),
			"a completed private render is promoted only after state restoration and GUI rendering");
		state.latchFailure();
		state.menuClosed();
		require(state.failureLatched() && !state.warmupPromoted()
			&& !state.publishAfterGuiPass(true, true, true),
			"menu close clears warmup promotion while retaining the failed-session latch");
		require(SpiritLeapPovRenderer.isToggleActivation(false, true, true, true)
			&& SpiritLeapPovRenderer.isToggleActivation(true, false, true, true)
			&& !SpiritLeapPovRenderer.isToggleActivation(true, true, true, true),
			"module or Show POVs off-to-on transitions define session reset boundaries");
		Object oldLevel = new Object();
		Object mainTarget = new Object();
		require(SpiritLeapPovRenderer.isSameRenderStateRestoreContext(oldLevel, mainTarget, 1920, 1080,
			oldLevel, mainTarget, 1920, 1080)
			&& !SpiritLeapPovRenderer.isSameRenderStateRestoreContext(oldLevel, mainTarget, 1920, 1080,
				new Object(), mainTarget, 1920, 1080)
			&& !SpiritLeapPovRenderer.isSameRenderStateRestoreContext(oldLevel, mainTarget, 1920, 1080,
				oldLevel, new Object(), 1920, 1080)
			&& !SpiritLeapPovRenderer.isSameRenderStateRestoreContext(oldLevel, mainTarget, 1920, 1080,
				oldLevel, mainTarget, 2560, 1440),
			"a saved render-state snapshot is discarded after world, main target, or window-size replacement");
		SpiritLeapPovRenderer.RenderStateRecoveryState restoreRecovery =
			new SpiritLeapPovRenderer.RenderStateRecoveryState();
		restoreRecovery.obsoleteSnapshotDiscarded();
		boolean staleSnapshotAllowsCleanup = restoreRecovery.acceptCompletedFrameBaseline(false, true);
		boolean failedBaselineAllowsCleanup = restoreRecovery.acceptCompletedFrameBaseline(true, false);
		boolean completedFrameAllowsCleanup = restoreRecovery.acceptCompletedFrameBaseline(true, true);
		require(!staleSnapshotAllowsCleanup && !failedBaselineAllowsCleanup && completedFrameAllowsCleanup
			&& !restoreRecovery.awaitingCompletedFrameBaseline(),
			"discarding a stale renderer-state snapshot blocks release until a completed frame verifies the new baseline");
		require(SpiritLeapPovRenderer.shouldRetryDeferredCleanup(100L, 0L)
			&& !SpiritLeapPovRenderer.shouldRetryDeferredCleanup(1_000_000_099L, 100L)
			&& SpiritLeapPovRenderer.shouldRetryDeferredCleanup(1_000_000_100L, 100L),
			"deferred cleanup retries are bounded to one attempt per second");
		state.resetAtLifecycleBoundary();
		require(!state.failureLatched() && !state.warmupPromoted()
			&& !state.publishAfterGuiPass(true, true, false)
			&& state.publishAfterGuiPass(true, true, true),
			"a lifecycle boundary clears the latch and allows a new warmup attempt");
		state.latchFailure();
		state.resourceReloadSucceeded();
		require(!state.failureLatched(), "a successful resource reload clears the failed-session latch");
	}

	private static void checkPovGuiFramePublication() {
		SpiritLeapPovRenderer.GuiFramePublication publication =
			new SpiritLeapPovRenderer.GuiFramePublication();
		require(!publication.pending() && publication.pendingQuadrant() == -1,
			"a GUI publication starts with no pending quadrant");
		require(!publication.queue(-1) && !publication.queue(SpiritLeapPovRenderer.FEED_COUNT),
			"invalid quadrants cannot enter the GUI publication queue");
		require(publication.queue(2) && publication.pendingQuadrant() == 2,
			"a completed POV target can queue its own quadrant for post-GUI publication");
		require(!publication.queue(3) && publication.pendingQuadrant() == 2,
			"a second POV target cannot replace a publication before the GUI pass completes");
		require(publication.takeAfterGuiRender() == 2 && !publication.pending(),
			"the GUI tail consumes and clears the queued quadrant");
		require(publication.queue(1) && publication.discard() == 1 && !publication.pending(),
			"lifecycle cleanup can discard a queued publication without exposing it");
		require(publication.discard() == -1,
			"discarding an empty publication queue is harmless");
	}

	private static SpiritLeapPovRenderer.CompatibilityDecision classifyProfile(String sodium, String nvidium,
		boolean sodiumAdapterVerified) {
		return SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
			"OpenGL", sodium, nvidium, sodiumAdapterVerified);
	}

	private static void checkFabricExtractionContextGate() {
		SpiritLeapPovRenderer.CompatibilityDecision enabled = new SpiritLeapPovRenderer.CompatibilityDecision(
			SpiritLeapPovRenderer.FEED_COUNT, "four-feed-automatic", "pinned-capabilities");
		SpiritLeapPovRenderer.CompatibilityDecision supported = SpiritLeapPovRenderer.gateFabricExtractionContext(
			enabled, SpiritLeapPovRenderer.PROBE_FABRIC_RENDERING_VERSION, true);
		SpiritLeapPovRenderer.CompatibilityDecision versionBlocked =
			SpiritLeapPovRenderer.gateFabricExtractionContext(enabled, "25.3.4+unknown", false);
		SpiritLeapPovRenderer.CompatibilityDecision mixinBlocked =
			SpiritLeapPovRenderer.gateFabricExtractionContext(
				enabled, SpiritLeapPovRenderer.PROBE_FABRIC_RENDERING_VERSION, false);
		require(supported.maxFeeds() == SpiritLeapPovRenderer.FEED_COUNT
			&& versionBlocked.maxFeeds() == 0
			&& versionBlocked.reason().equals("fabric-rendering-context-version-unverified")
			&& mixinBlocked.maxFeeds() == 0
			&& mixinBlocked.reason().equals("fabric-rendering-context-accessor-unavailable")
			&& !SpiritLeapPovRenderer.shouldApplyFabricContextAccessor(
				"geiler.addons.client.mixin.LevelExtractorSpiritLeapContextAccessor", "25.3.4+unknown")
			&& SpiritLeapPovRenderer.shouldApplyFabricContextAccessor(
				"geiler.addons.client.mixin.LevelExtractorSpiritLeapContextAccessor",
				SpiritLeapPovRenderer.PROBE_FABRIC_RENDERING_VERSION)
			&& SpiritLeapPovRenderer.shouldApplyFabricContextAccessor(
				"geiler.addons.client.mixin.LevelRendererSpiritLeapMixin", "25.3.4+unknown"),
			"only the exact 26.2 Fabric rendering module applies the context accessor and enables POVs");
		String diagnostic = SpiritLeapPovRenderer.formatRendererProfileWithExtractionContext(
			"base-profile", SpiritLeapPovRenderer.PROBE_FABRIC_RENDERING_VERSION, "applied");
		require(diagnostic.contains("fabricRenderingV1=" + SpiritLeapPovRenderer.PROBE_FABRIC_RENDERING_VERSION)
			&& diagnostic.contains("extractionContextAccessor=applied"),
			"renderer diagnostics identify the Fabric rendering version gate");
	}

	private static void checkFabricExtractionContextArgumentMapping() {
		GameRenderer gameRenderer = allocateWithoutConstructor(GameRenderer.class);
		LevelRenderer levelRenderer = allocateWithoutConstructor(LevelRenderer.class);
		LevelRenderState levelState = new LevelRenderState();
		ClientLevel level = allocateWithoutConstructor(ClientLevel.class);
		DeltaTracker deltaTracker = DeltaTracker.ZERO;
		Camera camera = new Camera();
		LevelExtractionContextImpl context = new LevelExtractionContextImpl();
		SpiritLeapPovRenderer.prepareFabricExtractionContext(context, gameRenderer, levelRenderer,
			levelState, level, deltaTracker, camera);
		require(context.gameRenderer() == gameRenderer && context.levelRenderer() == levelRenderer
			&& context.levelState() == levelState && context.level() == level
			&& context.deltaTracker() == deltaTracker && context.camera() == camera,
			"each private renderer context receives the matching renderer, render state, level, delta tracker, and POV camera");
	}

	private static <T> T allocateWithoutConstructor(Class<T> type) {
		try {
			Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
			Field singletonField = unsafeType.getDeclaredField("theUnsafe");
			require(singletonField.trySetAccessible(), "the focused harness can access its JDK allocation helper");
			Object unsafe = singletonField.get(null);
			return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("could not allocate a typed context argument without initializing its client state", failure);
		}
	}

	private static void checkPovRendererDiagnostics() {
		String sanitized = SpiritLeapPovRenderer.diagnosticVersion("0.9.2\nplayer=PrivateName");
		require(sanitized.length() <= 49 && sanitized.indexOf('\n') < 0 && sanitized.indexOf('\r') < 0,
			"detected renderer versions are length-bounded and cannot inject diagnostic lines");
		require(SpiritLeapPovRenderer.diagnosticVersion(null).equals("absent"),
			"missing renderer versions are explicit in the diagnostic profile");
		String diagnostic = classifyProfile(SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, true).mode();
		require(diagnostic.equals("four-feed-automatic"),
			"renderer diagnostics report automatic activation without a JVM opt-in");
		SpiritLeapPovRenderer.CompatibilityDecision decision = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null, true);
		String profile = SpiritLeapPovRenderer.formatRendererProfile("26.2", "0.19.5", "0.161.0+26.2",
			"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION,
			null, true, decision);
		require(profile.contains("minecraft=26.2") && profile.contains("fabricloader=0.19.5")
			&& profile.contains("fabricApi=0.161.0+26.2") && profile.contains("sodium=0.9.2+mc26.2")
			&& profile.contains("nvidium=absent")
			&& profile.contains("maxFeeds=4") && profile.contains("resourceRisk=active-world-pass-cost-unmeasured")
			&& profile.indexOf('\n') < 0 && !profile.contains("PrivateName"),
			"the diagnostic profile records exact Fabric and renderer versions, gate, resource risk, and feed count");
		SpiritLeapPovRenderer.CompatibilityDecision nvidiumDecision = classifyProfile(
			SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true);
		String nvidiumProfile = SpiritLeapPovRenderer.formatRendererProfile("26.2", "0.19.5", "0.161.0+26.2",
			"OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION,
			SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION, true, nvidiumDecision);
		require(nvidiumProfile.contains("nvidium=0.4.4-beta6-26.2")
			&& nvidiumProfile.contains("gate=four-feed-automatic(pinned-sodium-nvidium-capabilities)")
			&& nvidiumProfile.contains("maxFeeds=4"),
			"the runtime profile records the supported Nvidium 26.2 renderer path");
		SpiritLeapPovRenderer.CompatibilityDecision blockedModifier =
			SpiritLeapPovRenderer.classifyRendererCompatibility("26.2", "0.19.5", "0.161.0+26.2",
				"OpenGL", null, null, "iris", true);
		String blockedProfile = SpiritLeapPovRenderer.formatRendererProfile("26.2", "0.19.5",
			"0.161.0+26.2", "OpenGL", null, null, "iris", "1.8.1", true, blockedModifier);
		require(blockedProfile.contains("unverifiedRenderModifier=iris@1.8.1")
			&& blockedProfile.contains("gate=closed(unverified-render-modifier-iris)"),
			"renderer diagnostics include the bounded blocked-mod ID, version, and compatibility reason");
		require(SpiritLeapPovRenderer.boundedDurationMicros(-1L) == 0L
			&& SpiritLeapPovRenderer.boundedDurationMicros(Long.MAX_VALUE) == 999_999_999L,
			"diagnostic stage timings reject negative duration and cap extreme values");
	}

	private static void checkFixedCameraProbePolicy() {
		require(SpiritLeapPovRenderer.isFixedCameraProbeRequested("fixed-camera")
			&& !SpiritLeapPovRenderer.isFixedCameraProbeRequested(null)
			&& !SpiritLeapPovRenderer.isFixedCameraProbeRequested("fixed-camera-extra"),
			"the internal fixed-camera probe accepts only its exact JVM property value");
		String previousProbeProperty = System.getProperty(SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_PROPERTY);
		try {
			System.setProperty(SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_PROPERTY,
				SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_VALUE);
			require(SpiritLeapPovRenderer.fixedCameraProbeEnabled(),
				"the diagnostic probe reads its configured JVM property");
			System.setProperty(SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_PROPERTY, "disabled");
			require(!SpiritLeapPovRenderer.fixedCameraProbeEnabled(),
				"other property values leave the production teammate feeds enabled");
		} finally {
			if (previousProbeProperty == null) {
				System.clearProperty(SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_PROPERTY);
			} else {
				System.setProperty(SpiritLeapPovRenderer.FIXED_CAMERA_PROBE_PROPERTY, previousProbeProperty);
			}
		}
		require(SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(false, 0)
			&& SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(false, 3)
			&& SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(true, 0)
			&& !SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(true, 1)
			&& !SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(true, 2)
			&& !SpiritLeapPovRenderer.shouldRenderFixedCameraProbeQuadrant(true, 3),
			"fixed-camera probe mode preserves normal feeds but restricts the diagnostic run to q0");
		require(SpiritLeapPovRenderer.shouldDisplayFixedCameraProbeFrame(true, true, 0,
			SpiritLeapPovRenderer.FIXED_CAMERA_TARGET_NAME)
			&& !SpiritLeapPovRenderer.shouldDisplayFixedCameraProbeFrame(true, true, 1,
				SpiritLeapPovRenderer.FIXED_CAMERA_TARGET_NAME)
			&& !SpiritLeapPovRenderer.shouldDisplayFixedCameraProbeFrame(true, false, 0,
				SpiritLeapPovRenderer.FIXED_CAMERA_TARGET_NAME)
			&& !SpiritLeapPovRenderer.shouldDisplayFixedCameraProbeFrame(false, true, 0,
				SpiritLeapPovRenderer.FIXED_CAMERA_TARGET_NAME)
			&& !SpiritLeapPovRenderer.shouldDisplayFixedCameraProbeFrame(true, true, 0, "teammate"),
			"only a completed local probe frame can display in q0 while probe mode is active");
		require(SpiritLeapPovRenderer.fixedCameraProbeDiagnosticState(false, false).equals("off")
			&& SpiritLeapPovRenderer.fixedCameraProbeDiagnosticState(true, false)
				.equals("fixed-camera-q0-yaw90-requested")
			&& SpiritLeapPovRenderer.fixedCameraProbeDiagnosticState(true, true)
				.equals("fixed-camera-q0-yaw90-feed-assigned"),
			"diagnostics distinguish an unassigned launch probe from an assigned q0 feed");
		require(SpiritLeapPovRenderer.diagnosticMaxFeeds(false, 4) == 4
			&& SpiritLeapPovRenderer.diagnosticMaxFeeds(true, 4) == 1
			&& SpiritLeapPovRenderer.diagnosticMaxFeeds(true, 0) == 0,
			"probe diagnostics cap the reported render rate at one compatibility-approved feed");
		String mixinSource;
		try {
			mixinSource = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java",
				"geiler", "addons", "client", "mixin", "CameraSpiritLeapMixin.java"));
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect the fixed-camera mixin for the focused check", failure);
		}
		require(mixinSource.contains("target = \"Lnet/minecraft/world/entity/Entity;getViewYRot(F)F\"")
			&& mixinSource.contains("camera.entity() != Minecraft.getInstance().player")
			&& mixinSource.contains("fixedProbe ? Mth.wrapDegrees(entityYaw + 90.0f) : entityYaw"),
			"the 90-degree diagnostic yaw applies only to the local entity on a private POV camera");
		String mixinConfig;
		try {
			mixinConfig = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "resources",
				"geileraddons.client.mixins.json"));
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect the client mixin config for the fixed-camera mixin", failure);
		}
		require(mixinConfig.contains("\"CameraSpiritLeapMixin\""),
			"the fixed-camera redirect is registered in the client mixin configuration");
		require(mixinConfig.contains("\"ChunkSectionLayerGroupSpiritLeapMixin\"")
			&& mixinConfig.contains("\"ChunkSectionsToRenderSpiritLeapMixin\"")
			&& mixinConfig.contains("\"SodiumWorldRendererSpiritLeapMixin\"")
			&& mixinConfig.contains("\"NvidiumWorldRendererSpiritLeapMixin\""),
			"vanilla, Sodium, and Nvidium POV output diagnostics are registered");
		String povSource;
		try {
			java.nio.file.Path kotlinSource = java.nio.file.Path.of("src", "client", "kotlin", "geiler",
				"addons", "client", "module", "impl", "SpiritLeapPovRenderer.kt");
			java.nio.file.Path source = java.nio.file.Files.exists(kotlinSource) ? kotlinSource
				: java.nio.file.Path.of("src", "client", "java", "geiler", "addons", "client", "module", "impl",
					"SpiritLeapPovRenderer.java");
			povSource = java.nio.file.Files.readString(source);
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect POV global-uniform restoration", failure);
		}
		require(povSource.contains("bindPovGlobalSettingsUniform(target, deltaTracker, cameraState)")
			&& povSource.contains("RenderSystem.setGlobalSettingsUniform(")
			&& povSource.contains("originalGlobalSettingsUniform")
			&& povSource.contains("device.getDeviceInfo()")
			&& povSource.contains("deviceInfo.backendName()")
			&& !povSource.contains("device.getBackendName()")
			&& povSource.contains("copyTextureToBuffer")
			&& !povSource.contains("org.lwjgl.opengl") && !povSource.contains("GL11.")
			&& !povSource.contains("GL30.") && !povSource.contains("GlStateManager")
			&& povSource.contains("PIXEL_SAMPLE_INTERVAL_NANOS = 1_000_000_000L")
			&& povSource.contains("pendingPixelSample = null"),
			"POV output reads the 26.2 DeviceInfo backend, uses backend-neutral GPU copies, restores camera globals, and rate-limits pixel samples to one per second");
	}

	private static void checkRendererStateRestoration() {
		FakeRenderState state = new FakeRenderState();
		Object color = new Object();
		Object depth = new Object();
		Object globalSettings = new Object();
		Object shaderFog = new Object();
		Object projectionBuffer = new Object();
		Object projectionType = new Object();
		Matrix4f modelView = new Matrix4f().translation(7.0f, 9.0f, 3.0f);
		state.outputColor = color;
		state.outputDepth = depth;
		state.globalSettings = globalSettings;
		state.shaderFog = shaderFog;
		state.projectionBuffer = projectionBuffer;
		state.projectionType = projectionType;
		state.modelView = new Matrix4f(modelView);
		state.modelViewDepth = 4;
		SpiritLeapPovRenderer.RenderStateSnapshot saved = SpiritLeapPovRenderer.captureRenderState(state);
		require(saved.outputColorTextureOverride() == color && saved.outputDepthTextureOverride() == depth
			&& saved.globalSettingsUniform() == globalSettings && saved.shaderFog() == shaderFog
			&& saved.projectionMatrixBuffer() == projectionBuffer && saved.projectionType() == projectionType
			&& saved.modelViewStackDepth() == 4 && saved.modelViewMatrix().equals(modelView),
			"the POV snapshot captures backend-neutral output, fog, projection, global-uniform, and model-view state");

		state.outputColor = new Object();
		state.outputDepth = new Object();
		state.globalSettings = new Object();
		state.shaderFog = new Object();
		state.projectionBuffer = new Object();
		state.projectionType = new Object();
		state.modelView = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
		state.modelViewDepth = 1;
		SpiritLeapPovRenderer.restoreRenderState(state, saved);
		require(state.outputColor == color && state.outputDepth == depth && state.globalSettings == globalSettings
			&& state.shaderFog == shaderFog && state.projectionBuffer == projectionBuffer
			&& state.projectionType == projectionType && state.modelViewDepth == 4
			&& state.modelView.equals(modelView),
			"private render cleanup restores and verifies every captured backend-neutral render-state value");
	}

	private static void checkTemporaryCameraStateRestoration() {
		Object gameOriginal = new Object();
		Object rendererOriginal = new Object();
		Object cameraState = new Object();
		for (int owner : new int[] { 0, 1 }) for (boolean failBeforeWrite : new boolean[] { false, true }) {
			var game = new FakeCameraStateRef(gameOriginal);
			var renderer = new FakeCameraStateRef(rendererOriginal);
			var failing = owner == 0 ? game : renderer;
			failing.failOnWrite = 1;
			failing.failBeforeWrite = failBeforeWrite;
			boolean[] rendered = { false };
			RuntimeException observed = null;
			try {
				SpiritLeapPovRenderer.withTemporaryState(game, renderer, cameraState, () -> rendered[0] = true);
			} catch (RuntimeException expected) {
				observed = expected;
			}
			require(observed == failing.failure && !rendered[0]
				&& game.value == gameOriginal && renderer.value == rendererOriginal,
				"a partial state installation restores both main owners before any render is attempted");
		}
		var game = new FakeCameraStateRef(gameOriginal);
		var renderer = new FakeCameraStateRef(rendererOriginal);
		RuntimeException renderFailure = new IllegalStateException("injected-world-render-failure");
		try {
			SpiritLeapPovRenderer.withTemporaryState(game, renderer, cameraState, () -> {
				require(game.value == cameraState && renderer.value == cameraState,
					"the camera pass binds the same private state to both renderer owners");
				throw renderFailure;
			});
			throw new AssertionError("the injected world render failure was swallowed");
		} catch (RuntimeException observed) {
			require(observed == renderFailure && game.value == gameOriginal && renderer.value == rendererOriginal,
				"a failed world pass restores both original state identities and preserves the failure");
		}
		game.failOnWrite = 4;
		renderer.failOnWrite = 4;
		try {
			SpiritLeapPovRenderer.withTemporaryState(game, renderer, cameraState, () -> { });
			throw new AssertionError("the injected state restoration failures were swallowed");
		} catch (RuntimeException observed) {
			require(game.value == gameOriginal && renderer.value == rendererOriginal
				&& observed.getSuppressed().length == 1,
				"both main-state restores are attempted and failures are combined when either restore throws");
		}
	}

	private static final class FakeCameraStateRef implements SpiritLeapPovRenderer.MutableStateRef<Object> {
		Object value;
		int writes;
		int failOnWrite;
		boolean failBeforeWrite;
		final RuntimeException failure = new IllegalStateException("injected-state-setter-failure");
		FakeCameraStateRef(Object initial) { value = initial; }
		@Override public Object get() { return value; }
		@Override public void set(Object next) {
			boolean fail = ++writes == failOnWrite;
			if (fail && failBeforeWrite) throw failure;
			value = next;
			if (fail) throw failure;
		}
	}

	private static void checkPovTargetCleanupLifecycle() {
		int[] releases = { 0 };
		FakeRenderState failedRestore = new FakeRenderState();
		failedRestore.outputColor = new Object();
		failedRestore.outputDepth = new Object();
		failedRestore.modelView = new Matrix4f().translation(7.0f, 9.0f, 3.0f);
		failedRestore.modelViewDepth = 4;
		var failedSnapshot = SpiritLeapPovRenderer.captureRenderState(failedRestore);
		failedRestore.outputColor = new Object();
		failedRestore.outputDepth = new Object();
		failedRestore.globalSettings = new Object();
		failedRestore.shaderFog = new Object();
		failedRestore.projectionBuffer = new Object();
		failedRestore.projectionType = new Object();
		failedRestore.modelView = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
		failedRestore.modelViewDepth = 1;
		failedRestore.rejectRestore = true;
		boolean releasedWithoutRestore = SpiritLeapPovRenderer.releaseTargetsAfterStateRestore(
			failedRestore, failedSnapshot, () -> releases[0]++);
		require(!releasedWithoutRestore && releases[0] == 0,
			"the shared renderer and feed targets stay alive when verified state restoration fails");
		require(SpiritLeapPovRenderer.shouldAttemptDeferredTargetCleanup(true, true)
			&& !SpiritLeapPovRenderer.shouldAttemptDeferredTargetCleanup(false, true)
			&& !SpiritLeapPovRenderer.shouldAttemptDeferredTargetCleanup(true, false),
			"deferred target cleanup waits for a later completed main render pass");
		FakeRenderState restoredState = new FakeRenderState();
		restoredState.outputColor = new Object();
		restoredState.outputDepth = new Object();
		restoredState.modelView = new Matrix4f().translation(7.0f, 9.0f, 3.0f);
		restoredState.modelViewDepth = 4;
		var restoredSnapshot = SpiritLeapPovRenderer.captureRenderState(restoredState);
		boolean releasedAfterRestore = SpiritLeapPovRenderer.releaseTargetsAfterStateRestore(
			restoredState, restoredSnapshot, () -> releases[0]++);
		require(releasedAfterRestore && releases[0] == 1,
			"POV feed targets release exactly after a successful state restoration");

		FakeRenderState deferredState = new FakeRenderState();
		deferredState.outputColor = new Object();
		deferredState.outputDepth = new Object();
		deferredState.modelView = new Matrix4f().translation(3.0f, 5.0f, 9.0f);
		deferredState.modelViewDepth = 2;
		var originalSnapshot = SpiritLeapPovRenderer.captureRenderState(deferredState);
		deferredState.outputColor = new Object();
		deferredState.outputDepth = new Object();
		deferredState.globalSettings = new Object();
		deferredState.shaderFog = new Object();
		deferredState.projectionBuffer = new Object();
		deferredState.projectionType = new Object();
		deferredState.modelView = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
		deferredState.modelViewDepth = 1;
		deferredState.rejectRestore = true;
		var pendingRestore = new SpiritLeapPovRenderer.PendingRenderStateRestore();
		boolean renderRestoreFailed = false;
		try {
			SpiritLeapPovRenderer.restoreRenderState(deferredState, originalSnapshot);
		} catch (RuntimeException expected) {
			renderRestoreFailed = true;
		}
		SpiritLeapPovRenderer.retainFailedRenderStateRestore(pendingRestore, originalSnapshot);
		boolean cleanupStarted = false;
		if (SpiritLeapPovRenderer.retryPendingRenderStateRestoreBeforeClose(pendingRestore, deferredState)) {
			cleanupStarted = true;
		}
		require(renderRestoreFailed && !cleanupStarted && pendingRestore.pending(),
			"a final render restore failure is retained and disconnect cleanup waits when its retry fails");
		deferredState.outputColor = new Object();
		deferredState.outputDepth = new Object();
		deferredState.modelView = new Matrix4f().translation(11.0f, 12.0f, 6.0f);
		deferredState.modelViewDepth = 1;
		deferredState.rejectRestore = false;
		if (SpiritLeapPovRenderer.retryPendingRenderStateRestoreBeforeClose(pendingRestore, deferredState)) {
			cleanupStarted = true;
		}
		require(cleanupStarted && !pendingRestore.pending()
			&& deferredState.outputColor == originalSnapshot.outputColorTextureOverride()
			&& deferredState.outputDepth == originalSnapshot.outputDepthTextureOverride()
			&& deferredState.modelViewDepth == originalSnapshot.modelViewStackDepth()
			&& deferredState.modelView.equals(originalSnapshot.modelViewMatrix()),
			"disconnect cleanup restores the original render snapshot before allowing resource release");

		Object[] retainedHandle = { new Object() };
		Object originalHandle = retainedHandle[0];
		int[] closeAttempts = { 0 };
		RuntimeException firstCloseFailure = SpiritLeapPovRenderer.attemptCleanupAndClearAfterSuccess(null, () -> {
			closeAttempts[0]++;
			throw new IllegalStateException("simulated close failure");
		}, () -> retainedHandle[0] = null);
		require(firstCloseFailure != null && retainedHandle[0] == originalHandle,
			"a failed resource close retains its handle for a retry");
		SpiritLeapPovRenderer.attemptCleanupAndClearAfterSuccess(firstCloseFailure, () -> closeAttempts[0]++,
			() -> retainedHandle[0] = null);
		require(closeAttempts[0] == 2 && retainedHandle[0] == null,
			"a retained resource handle clears only after the retried close succeeds");
	}

	private static void checkPovResourceReloadDeferral() {
		Object firstResources = new Object();
		Object latestResources = new Object();
		var pending = new SpiritLeapPovRenderer.PendingResourceReload<Object>();
		pending.retain(firstResources);
		pending.retain(latestResources);
		require(SpiritLeapPovRenderer.shouldDeferResourceReload(true, false, false)
			&& SpiritLeapPovRenderer.shouldDeferResourceReload(false, true, false)
			&& SpiritLeapPovRenderer.shouldDeferResourceReload(false, false, true)
			&& pending.takeIfReady(true, false, false) == null && pending.pending()
			&& pending.takeIfReady(false, false, true) == null && pending.pending(),
			"resource reload waits through failed state restoration, private rendering, and deferred cleanup");
		require(pending.takeIfReady(false, false, false) == latestResources && !pending.pending(),
			"the most recent deferred resource reload is replayed and cleared after all guards pass");
	}

	private static final class FakeRenderState implements SpiritLeapPovRenderer.RenderStateAccess {
		Object outputColor;
		Object outputDepth;
		Object globalSettings;
		Object shaderFog;
		Object projectionBuffer;
		Object projectionType;
		Matrix4f modelView = new Matrix4f();
		int modelViewDepth;
		boolean rejectRestore;

		@Override public Object outputColorTextureOverride() { return outputColor; }
		@Override public Object outputDepthTextureOverride() { return outputDepth; }
		@Override public Object globalSettingsUniform() { return globalSettings; }
		@Override public Object shaderFog() { return shaderFog; }
		@Override public Object projectionMatrixBuffer() { return projectionBuffer; }
		@Override public Object projectionType() { return projectionType; }
		@Override public void outputTextureOverrides(Object color, Object depth) {
			if (!rejectRestore) {
				outputColor = color;
				outputDepth = depth;
			}
		}
		@Override public void globalSettingsUniform(Object value) { if (!rejectRestore) globalSettings = value; }
		@Override public void shaderFog(Object value) { if (!rejectRestore) shaderFog = value; }
		@Override public void projection(Object buffer, Object type) {
			if (!rejectRestore) {
				projectionBuffer = buffer;
				projectionType = type;
			}
		}
		@Override public Matrix4f modelViewMatrix() { return new Matrix4f(modelView); }
		@Override public int modelViewStackDepth() { return modelViewDepth; }
		@Override public void modelViewStack(int depth, Matrix4f matrix) {
			if (!rejectRestore) {
				modelViewDepth = depth;
				modelView = new Matrix4f(matrix);
			}
		}
	}

	private static void checkPovVisibilityAndFreeLookGuards() {
		require(SpiritLeapMenuModule.shouldRenderLeapCards(true, true, true, true)
			&& !SpiritLeapMenuModule.shouldRenderLeapCards(false, true, true, true)
			&& !SpiritLeapMenuModule.shouldRenderLeapCards(true, false, true, true)
			&& !SpiritLeapMenuModule.shouldRenderLeapCards(true, true, false, true)
			&& !SpiritLeapMenuModule.shouldRenderLeapCards(true, true, true, false),
			"a valid menu head is required to acquire the custom Leap surface");
		require(!SpiritLeapSurfaceOwner.shouldOwnSurface(false, false, true)
			&& !SpiritLeapSurfaceOwner.shouldOwnSurface(true, false, false)
			&& SpiritLeapSurfaceOwner.shouldOwnSurface(true, false, true)
			&& SpiritLeapSurfaceOwner.shouldOwnSurface(true, true, false)
			&& !SpiritLeapSurfaceOwner.shouldOwnSurface(false, true, false)
			&& !SpiritLeapSurfaceOwner.shouldOwnSurface(true, false, false),
			"only a validated Leap screen or its positively identified owner can acquire the custom surface");
		require(SpiritLeapSurfaceOwner.shouldRetainSurface(true, true, true)
			&& !SpiritLeapSurfaceOwner.shouldRetainSurface(false, true, true)
			&& !SpiritLeapSurfaceOwner.shouldRetainSurface(true, false, true)
			&& !SpiritLeapSurfaceOwner.shouldRetainSurface(true, true, false),
			"an acquired Leap surface survives dungeon or title detection gaps until its screen closes or the feature is disabled");
		checkLeapTransitionGuard();
		boolean[] dispatchRan = { false };
		try {
			SpiritLeapSurfaceOwner.runDispatchedClick(() -> {
				dispatchRan[0] = SpiritLeapSurfaceOwner.isDispatchingClick();
				throw new IllegalStateException("simulated click dispatch failure");
			});
		} catch (IllegalStateException expected) {
			// The dispatch marker must be restored even if vanilla slot handling throws.
		}
		require(dispatchRan[0] && !SpiritLeapSurfaceOwner.isDispatchingClick(),
			"custom keyboard and mouse dispatches bypass the slot guard only inside a try/finally scope");
		require(!SpiritLeapPovRenderer.shouldApplyFreeLook(true)
			&& SpiritLeapPovRenderer.shouldApplyFreeLook(false),
			"FreeLook is skipped for the teammate POV camera and retained for the local camera");
	}

	private static void checkLeapTransitionGuard() {
		long dispatchedAt = 1_000_000_000L;
		Object source = new Object();
		Object replacement = new Object();
		Object unrelated = new Object();
		SpiritLeapTransitionGuard guard = new SpiritLeapTransitionGuard();
		require(guard.evaluate(replacement, true, true, dispatchedAt) == SpiritLeapTransitionGuard.Decision.INACTIVE
			&& guard.arm(source, dispatchedAt)
			&& guard.evaluate(source, true, true, dispatchedAt + 1L)
				== SpiritLeapTransitionGuard.Decision.SOURCE_SCREEN,
			"the post-leap guard remains inactive until a validated dispatch arms it and ignores its source screen");
		require(guard.evaluate(unrelated, true, false, dispatchedAt + 2L)
			== SpiritLeapTransitionGuard.Decision.CLEARED_UNRELATED && !guard.isArmed(),
			"an unrelated first replacement clears the guard without suppressing its screen");
		require(guard.arm(source, dispatchedAt)
			&& guard.evaluate(replacement, true, true, dispatchedAt + 3L)
				== SpiritLeapTransitionGuard.Decision.SUPPRESS_FIRST_REPLACEMENT
			&& guard.evaluate(replacement, true, true, dispatchedAt + 4L)
				== SpiritLeapTransitionGuard.Decision.SUPPRESS_SAME_REPLACEMENT
			&& guard.isSuppressing(replacement, true, dispatchedAt + 5L),
			"only the first recognized Leap replacement is suppressed, including repeated renders of that screen");
		require(guard.evaluate(unrelated, true, false, dispatchedAt + 6L)
			== SpiritLeapTransitionGuard.Decision.CLEARED_SCREEN_CHANGED && !guard.isArmed(),
			"a later replacement screen clears the one-screen transition guard");
		require(guard.arm(source, dispatchedAt) && guard.clearForValidatedLeapScreen(replacement)
			&& !guard.isArmed() && !guard.clearForValidatedLeapScreen(source),
			"a newly validated Leap surface clears a pending transition without clearing on the source screen");
		require(guard.arm(source, dispatchedAt) && guard.evaluate(replacement, false, true, dispatchedAt + 1L)
			== SpiritLeapTransitionGuard.Decision.CLEARED_DISABLED
			&& guard.arm(source, dispatchedAt)
			&& guard.evaluate(replacement, true, true, dispatchedAt + SpiritLeapTransitionGuard.WINDOW_NANOS)
				== SpiritLeapTransitionGuard.Decision.CLEARED_EXPIRED
			&& guard.arm(source, dispatchedAt)
			&& guard.evaluate(replacement, true, true, dispatchedAt - 1L)
				== SpiritLeapTransitionGuard.Decision.CLEARED_EXPIRED,
			"feature disable, the 1.5 second deadline, and a backwards monotonic clock reading fail open and clear state");
		require(guard.arm(source, dispatchedAt)
			&& guard.evaluate(replacement, true, true, dispatchedAt + 1L)
				== SpiritLeapTransitionGuard.Decision.SUPPRESS_FIRST_REPLACEMENT
			&& guard.clearForRemovedScreen(replacement) && !guard.isArmed()
			&& guard.arm(source, dispatchedAt) && !guard.clearForRemovedScreen(source)
			&& !guard.expireIfStale(dispatchedAt + 100_000_000L)
			&& guard.evaluate(null, true, false, dispatchedAt + 101_000_000L)
				== SpiritLeapTransitionGuard.Decision.INACTIVE && guard.isArmed()
			&& guard.evaluate(replacement, true, true, dispatchedAt + 102_000_000L)
				== SpiritLeapTransitionGuard.Decision.SUPPRESS_FIRST_REPLACEMENT
			&& guard.clearForRemovedScreen(replacement) && !guard.isArmed(),
			"a brief null-screen gap preserves the dispatch guard for the delayed Leap replacement, then replacement removal clears it");
		require(guard.arm(source, dispatchedAt)
			&& guard.expireIfStale(dispatchedAt + SpiritLeapTransitionGuard.WINDOW_NANOS)
			&& !guard.isArmed()
			&& guard.arm(source, dispatchedAt) && guard.expireIfStale(dispatchedAt - 1L)
			&& !guard.isArmed(),
			"the end-tick cleanup expires an unused guard at its deadline and fails open on a backwards monotonic clock reading");
	}

	private static void checkSkyblockerCompatibilityHook() {
		String mixin = SkyblockerSpiritLeapSupport.RENDER_HELPER_MIXIN;
		String pinned = SkyblockerSpiritLeapSupport.SUPPORTED_VERSION;
		SpiritLeapPovRenderer.CompatibilityDecision open =
			new SpiritLeapPovRenderer.CompatibilityDecision(4, "open", "verified-renderer");
		require(pinned.equals("6.10.4+26.2")
			&& SkyblockerSpiritLeapSupport.shouldApplyHook(mixin, pinned)
			&& !SkyblockerSpiritLeapSupport.shouldApplyHook(mixin, "6.10.5+26.2")
			&& SkyblockerSpiritLeapSupport.shouldApplyHook("geiler.addons.client.mixin.OtherMixin", "unknown"),
			"the Skyblocker bridge is restricted to its pinned 26.2 release and helper mixin");
		require(SpiritLeapPovRenderer.gateSkyblockerCompatibility(open, false, "absent", false) == open
			&& SpiritLeapPovRenderer.gateSkyblockerCompatibility(open, true, pinned, true) == open,
			"the private callback gate leaves the verified renderer open and does not affect installs without Skyblocker");
		SpiritLeapPovRenderer.CompatibilityDecision unknown =
			SpiritLeapPovRenderer.gateSkyblockerCompatibility(open, true, "unknown", true);
		SpiritLeapPovRenderer.CompatibilityDecision missingHook =
			SpiritLeapPovRenderer.gateSkyblockerCompatibility(open, true, pinned, false);
		require(unknown.maxFeeds() == 0 && unknown.reason().equals("skyblocker-callback-version-unverified")
			&& missingHook.maxFeeds() == 0
			&& missingHook.reason().equals("skyblocker-private-render-hook-unavailable"),
			"unsupported Skyblocker builds and unavailable private hooks fail closed for POVs");
		try {
			String source = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "SkyblockerRenderHelperSpiritLeapMixin.java"));
			require(source.contains("method = \"startExtraction\"")
				&& source.contains("method = \"dispatchSubmits\"")
				&& !source.contains("submitVanillaSubmittables") && !source.contains("executeDraws"),
				"Skyblocker 26.2 POV suppression uses the verified extraction and dispatch callback names");
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect the Skyblocker 26.2 callback bridge", failure);
		}
	}

	private static void checkSodiumWorldRendererCompatibilityHook() {
		String mixin = SodiumWorldRendererSpiritLeapSupport.WORLD_RENDERER_MIXIN;
		String terrainMixin = SodiumWorldRendererSpiritLeapSupport.TERRAIN_RENDER_PASS_MIXIN;
		String pinned = SodiumWorldRendererSpiritLeapSupport.SUPPORTED_VERSION;
		String verified091 = SodiumWorldRendererSpiritLeapSupport.VERIFIED_0_9_1_VERSION;
		require(pinned.equals("0.9.2+mc26.2")
			&& verified091.equals("0.9.1+mc26.2")
			&& pinned.equals(SpiritLeapPovRenderer.PROBE_SODIUM_VERSION)
			&& SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixin, pinned)
			&& SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixin, verified091)
			&& SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(terrainMixin, pinned)
			&& SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(terrainMixin, verified091)
			&& !SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixin, "0.9.0+mc26.2")
			&& !SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixin, "0.9.3+mc26.2")
			&& !SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(terrainMixin, "0.9.3+mc26.2")
			&& !SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixin, "absent")
			&& SodiumWorldRendererSpiritLeapSupport.shouldApplyHook("geiler.addons.client.mixin.OtherMixin", "unknown"),
			"both version-sensitive Sodium hooks are limited to the exact verified 26.2 builds");
		try {
			String worldRenderer = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "SodiumWorldRendererSpiritLeapMixin.java"));
			String terrainPass = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "SodiumTerrainRenderPassSpiritLeapMixin.java"));
			require(worldRenderer.contains("@Mixin(targets = \"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer\"")
				&& worldRenderer.contains("public abstract int getVisibleChunkCount();")
				&& worldRenderer.contains("@Inject(method = \"drawChunkLayer\", at = @At(\"TAIL\")")
				&& worldRenderer.contains("noteSodiumLayerCompleted(getVisibleChunkCount())")
				&& terrainPass.contains("@Mixin(targets = \"net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass\"")
				&& terrainPass.contains("method = \"getTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;\"")
				&& terrainPass.contains("isSodiumTranslucentBranch(this,")
				&& terrainPass.contains("cir.setReturnValue(target)"),
				"Sodium hooks retain the 0.9.1-verified draw and terrain-target method shapes");
		} catch (java.io.IOException failure) {
			throw new AssertionError("could not inspect the Sodium 26.2 renderer hooks", failure);
		}
	}

	private static void checkPovQuadrantFeedsAndStaggering() {
		Object mainTarget = new Object();
		Object privateOpaqueTarget = new Object();
		Object privateTranslucentTarget = new Object();
		Object unexpectedTarget = new Object();
		require(SpiritLeapPovRenderer.isPrivateTargetRoute(privateOpaqueTarget, privateOpaqueTarget, mainTarget)
			&& SpiritLeapPovRenderer.isPrivateTargetRoute(privateTranslucentTarget, privateTranslucentTarget, mainTarget)
			&& !SpiritLeapPovRenderer.isPrivateTargetRoute(mainTarget, privateOpaqueTarget, mainTarget)
			&& !SpiritLeapPovRenderer.isPrivateTargetRoute(new Object(), privateOpaqueTarget, mainTarget)
			&& !SpiritLeapPovRenderer.isPrivateTargetRoute(null, privateTranslucentTarget, mainTarget),
			"Sodium route validation accepts only the actual private target identity and rejects null or main targets");
		require(SpiritLeapPovRenderer.renderTargetRouteState(privateOpaqueTarget, privateOpaqueTarget, mainTarget)
				.equals("private-scratch")
			&& SpiritLeapPovRenderer.renderTargetRouteState(mainTarget, privateOpaqueTarget, mainTarget)
				.equals("main-target")
			&& SpiritLeapPovRenderer.renderTargetRouteState(unexpectedTarget, privateOpaqueTarget, mainTarget)
				.equals("unexpected-target")
			&& SpiritLeapPovRenderer.renderTargetRouteState(null, privateOpaqueTarget, mainTarget).equals("none"),
			"automatic route diagnostics distinguish private, main, unexpected, and absent target bindings");
		require(!SpiritLeapPovRenderer.shouldAbortPrivateTargetRoute(true,
			privateOpaqueTarget, privateOpaqueTarget, mainTarget)
			&& SpiritLeapPovRenderer.shouldAbortPrivateTargetRoute(true, mainTarget, privateOpaqueTarget, mainTarget)
			&& SpiritLeapPovRenderer.shouldAbortPrivateTargetRoute(true, unexpectedTarget, privateOpaqueTarget, mainTarget)
			&& !SpiritLeapPovRenderer.shouldAbortPrivateTargetRoute(false, mainTarget, privateOpaqueTarget, mainTarget),
			"private-feed target hooks abort before a main or unexpected route can contaminate the main renderer");
		require(SpiritLeapPovRenderer.privateRenderTargetRouteFailure(true, false, true, false) == null
			&& "level-target-not-observed".equals(
				SpiritLeapPovRenderer.privateRenderTargetRouteFailure(false, false, true, false))
			&& "level-target-mismatch".equals(
				SpiritLeapPovRenderer.privateRenderTargetRouteFailure(true, true, true, false))
			&& "frame-clear-not-observed".equals(
				SpiritLeapPovRenderer.privateRenderTargetRouteFailure(true, false, false, false))
			&& "frame-clear-mismatch".equals(
				SpiritLeapPovRenderer.privateRenderTargetRouteFailure(true, false, true, true)),
			"a POV frame is accepted only when its LevelRenderer and frame-graph clear routes are observed on scratch");
		require(!SpiritLeapPovRenderer.hasObservedWrongSodiumRoute(false, false, false, false)
			&& !SpiritLeapPovRenderer.hasObservedWrongSodiumRoute(true, true, false, false)
			&& SpiritLeapPovRenderer.hasObservedWrongSodiumRoute(false, false, true, false)
			&& SpiritLeapPovRenderer.hasObservedWrongSodiumRoute(true, false, false, false),
			"unobserved Sodium routes are allowed after preflight, while any observed wrong target fails");
		require(SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
			"0.161.0+26.2", "OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.99",
				"0.161.99+26.2", "OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
				"0.161.0+26.2", "Vulkan", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION, null)
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
				"0.161.0+26.2", "OpenGL", "0.9.3+mc26.2", null)
			&& SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
				"0.161.0+26.2", "OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION,
				SpiritLeapPovRenderer.PROBE_NVIDIUM_VERSION)
			&& !SpiritLeapPovRenderer.isSodiumAdapterPreflightEligible("26.2", "0.19.5",
				"0.161.0+26.2", "OpenGL", SpiritLeapPovRenderer.PROBE_SODIUM_VERSION,
				"0.4.4-beta6-26.1"),
			"the private-target preflight requires loader/API metadata and exact renderer pins");
		require(SpiritLeapPovRenderer.isFeedTargetAllocationWithinBudget(2048, 1024, 4)
			&& !SpiritLeapPovRenderer.isFeedTargetAllocationWithinBudget(2049, 1024, 4)
			&& !SpiritLeapPovRenderer.isFeedTargetAllocationWithinBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, 4)
			&& !SpiritLeapPovRenderer.isFeedTargetAllocationWithinBudget(1, 1, 0),
			"four cached POV targets plus the shared scratch target preserve the existing half-window pixel limit and reject invalid dimensions or feed counts");
		require(SpiritLeapPovRenderer.sodiumTerrainTargetSelection(false, false, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.ORIGINAL
			&& SpiritLeapPovRenderer.sodiumTerrainTargetSelection(true, false, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.FEED
			&& SpiritLeapPovRenderer.sodiumTerrainTargetSelection(true, true, false)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.FEED
			&& SpiritLeapPovRenderer.sodiumTerrainTargetSelection(true, true, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.PRIVATE_TRANSLUCENT,
			"Sodium target routing preserves the main target outside POV renders and asks for a transient target only in transparent private passes");
		require(SpiritLeapPovRenderer.layerGroupTargetSelection(false, false, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.ORIGINAL
			&& SpiritLeapPovRenderer.layerGroupTargetSelection(true, false, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.FEED
			&& SpiritLeapPovRenderer.layerGroupTargetSelection(true, true, false)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.FEED
			&& SpiritLeapPovRenderer.layerGroupTargetSelection(true, true, true)
			== SpiritLeapPovRenderer.SodiumTerrainTargetSelection.PRIVATE_TRANSLUCENT,
			"vanilla chunk-layer target lookups remain on the game target outside POV renders and use private opaque/translucent targets inside");
		SpiritLeapPovRenderer.PixelSampleRegion topLeftSample = SpiritLeapPovRenderer.screenPixelSampleRegion(
			0, 1280, 720, 2560, 1440, 16);
		SpiritLeapPovRenderer.PixelSampleRegion bottomRightSample = SpiritLeapPovRenderer.screenPixelSampleRegion(
			3, 1280, 720, 2560, 1440, 16);
		require(topLeftSample != null && bottomRightSample != null
			&& topLeftSample.x() + topLeftSample.width() < 1280
			&& bottomRightSample.x() > 1280
			&& topLeftSample.y() > bottomRightSample.y(),
			"post-GUI GPU samples map to the same point within their own top-left or bottom-right quadrant");
		require(SpiritLeapPovRenderer.screenPixelSampleRegion(0, 10, 10, 20, 20, 16) == null,
			"sampling is skipped when the panel cannot fit an exposed diagnostic patch");
		java.nio.ByteBuffer sampledPixels = java.nio.ByteBuffer.allocate(16);
		sampledPixels.put(new byte[] { 0, 0, 0, -1, -1, 0, 0, -1, 0, -1, 0, -1, 32, 32, 32, -1 }).flip();
		SpiritLeapPovRenderer.PixelSampleSummary sampleSummary =
			SpiritLeapPovRenderer.summarizePixelBytes(sampledPixels, 4);
		require(sampleSummary != null && sampleSummary.pixels() == 4
			&& sampleSummary.nonBlackPixels() == 3 && sampleSummary.distinctColors() == 4
			&& SpiritLeapPovRenderer.summarizePixelBytes(sampledPixels, 5) == null
			&& SpiritLeapPovRenderer.shouldAwaitScreenPixelSample(false, "frame=1/pixels=256")
			&& !SpiritLeapPovRenderer.shouldAwaitScreenPixelSample(false, "failed(short-read)")
			&& !SpiritLeapPovRenderer.shouldAwaitScreenPixelSample(true, "frame=1/pixels=256")
			&& SpiritLeapPovRenderer.shouldSamplePixel(0L, 10L)
			&& SpiritLeapPovRenderer.shouldSamplePixel(1_000_000_000L, 2_000_000_000L)
			&& !SpiritLeapPovRenderer.shouldSamplePixel(1_000_000_000L, 1_999_999_999L),
			"GPU samples are bounded per feed, report only color counts, and compare the screen only after a successful source sample");
		require(SpiritLeapPovRenderer.shouldUsePrivateTransparencyChain(true, true)
			&& !SpiritLeapPovRenderer.shouldUsePrivateTransparencyChain(true, false)
			&& !SpiritLeapPovRenderer.shouldUsePrivateTransparencyChain(false, true),
			"a private renderer uses its transparency chain only while shader transparency is currently enabled");
		require(SpiritLeapPovRenderer.sodiumPreflightReadyForAttempt(true, true)
			&& !SpiritLeapPovRenderer.sodiumPreflightReadyForAttempt(false, true)
			&& !SpiritLeapPovRenderer.sodiumPreflightReadyForAttempt(true, false),
			"Sodium preflight is not committed until both pixel budget and private target readiness pass");
		List<SpiritLeapRoster.Card> cards = List.of(
			new SpiritLeapRoster.Card(0, new SpiritLeapRoster.Member("Archer", "Archer", 100, true, false)),
			new SpiritLeapRoster.Card(1, new SpiritLeapRoster.Member("Berserk", "Berserk", 100, true, false)),
			new SpiritLeapRoster.Card(2, new SpiritLeapRoster.Member("Healer", "Healer", 100, true, false)),
			new SpiritLeapRoster.Card(3, new SpiritLeapRoster.Member("Tank", "Tank", 100, true, false)));
		require(SpiritLeapPovRenderer.firstEligibleLoadedQuadrant(cards, Set.of("healer")) == 2,
			"warmup chooses the first eligible loaded teammate quadrant instead of requiring quadrant zero");
		SpiritLeapPovRenderer.FeedState[] sparseFeeds = new SpiritLeapPovRenderer.FeedState[4];
		for (int quadrant = 0; quadrant < sparseFeeds.length; quadrant++)
			sparseFeeds[quadrant] = new SpiritLeapPovRenderer.FeedState(quadrant);
		sparseFeeds[2].targetName = "Healer";
		sparseFeeds[2].chunkNeighborhoodLoaded = true;
		require(SpiritLeapPovRenderer.firstReadyQuadrant(4, sparseFeeds) == 2,
			"warmup render scheduling selects the first ready nonzero quadrant");
		for (int quadrant = 0; quadrant < SpiritLeapPovRenderer.FEED_COUNT; quadrant++) {
			String name = cards.get(quadrant).member().name();
			require(SpiritLeapPovRenderer.isFeedEligible(name, quadrant, cards),
				"each independent POV feed selects only the card in quadrant " + quadrant);
			for (int otherQuadrant = 0; otherQuadrant < SpiritLeapPovRenderer.FEED_COUNT; otherQuadrant++) {
				if (quadrant == otherQuadrant) continue;
				require(!SpiritLeapPovRenderer.isFeedEligible(name, otherQuadrant, cards),
					"a POV target cannot leak into another card quadrant");
			}
		}

		SpiritLeapPovRenderer.FeedScheduler scheduler = new SpiritLeapPovRenderer.FeedScheduler();
		require(scheduler.nextQuadrant() == 0 && scheduler.nextQuadrant() == 1
			&& scheduler.nextQuadrant() == 2 && scheduler.nextQuadrant() == 3
			&& scheduler.nextQuadrant() == 0,
			"separate POV render work is staggered round-robin across all four quadrants");
		scheduler.reset();
		require(scheduler.nextQuadrant() == 0, "POV scheduler reset starts at the first quadrant");
		scheduler.reset();
		SpiritLeapPovRenderer.FeedState[] scheduledFeeds = new SpiritLeapPovRenderer.FeedState[4];
		for (int quadrant = 0; quadrant < scheduledFeeds.length; quadrant++) {
			scheduledFeeds[quadrant] = new SpiritLeapPovRenderer.FeedState(quadrant);
			scheduledFeeds[quadrant].targetName = "target-" + quadrant;
			scheduledFeeds[quadrant].chunkNeighborhoodLoaded = true;
		}
		long interval = 1_000_000_000L / SpiritLeapPovRenderer.MAX_UPDATES_PER_FEED_PER_SECOND;
		for (int quadrant = 0; quadrant < scheduledFeeds.length; quadrant++) {
			int due = scheduler.nextDueQuadrant(4, scheduledFeeds, 0L);
			require(due == quadrant,
				"the scheduler staggers the first render of each available feed, expected q" + quadrant);
			scheduledFeeds[due].hasUpdated = true;
			scheduledFeeds[due].lastUpdateNanos = 0L;
		}
		require(scheduler.nextDueQuadrant(4, scheduledFeeds, interval - 1L) == -1,
			"no feed can refresh before its own 4 Hz interval elapses");
		require(scheduler.nextDueQuadrant(4, scheduledFeeds, interval) == 0,
			"the first feed refreshes at its own 4 Hz boundary");
		scheduledFeeds[0].lastUpdateNanos = interval;
		require(scheduler.nextDueQuadrant(4, scheduledFeeds, interval + 1L) == 1,
			"the next overdue feed is selected independently while the just-rendered feed remains capped");
		for (int activeFeedCount : new int[] { 1, 2 }) {
			SpiritLeapPovRenderer.FeedScheduler limitedScheduler = new SpiritLeapPovRenderer.FeedScheduler();
			SpiritLeapPovRenderer.FeedState[] limitedFeeds = new SpiritLeapPovRenderer.FeedState[4];
			for (int quadrant = 0; quadrant < limitedFeeds.length; quadrant++) {
				limitedFeeds[quadrant] = new SpiritLeapPovRenderer.FeedState(quadrant);
				limitedFeeds[quadrant].targetName = "target-" + quadrant;
				limitedFeeds[quadrant].chunkNeighborhoodLoaded = true;
			}
			int firstDue = limitedScheduler.nextDueQuadrant(activeFeedCount, limitedFeeds, 0L);
			require(firstDue == 0, "a " + activeFeedCount + "-feed session starts its round robin at q0");
			limitedFeeds[firstDue].lastUpdateNanos = 0L;
			limitedFeeds[firstDue].hasUpdated = true;
			int delayedDue = limitedScheduler.nextDueQuadrant(activeFeedCount, limitedFeeds, 10_000_000_000L);
			require(delayedDue >= 0 && delayedDue < activeFeedCount,
				"a delayed " + activeFeedCount + "-feed session skips missed slots and selects one due feed");
			limitedFeeds[delayedDue].lastUpdateNanos = 10_000_000_000L;
			limitedFeeds[delayedDue].hasUpdated = true;
			limitedScheduler.renderAttemptCompleted(10_000_000_000L);
			long sharedRenderGapNanos = 1_000_000_000L
				/ (SpiritLeapPovRenderer.MAX_UPDATES_PER_FEED_PER_SECOND * SpiritLeapPovRenderer.FEED_COUNT);
			require(!limitedScheduler.canStartRender(10_000_000_000L + sharedRenderGapNanos - 1L)
				&& limitedScheduler.canStartRender(10_000_000_000L + sharedRenderGapNanos),
				"a " + activeFeedCount + "-feed session resumes only after the shared render gap");
		}
		long framePublishedNanos = 10_000_000_000L;
		long frameBeforeExpiryNanos = framePublishedNanos
			+ SpiritLeapPovRenderer.MAX_CACHED_FRAME_AGE_NANOS - 1L;
		long frameAtExpiryNanos = framePublishedNanos
			+ SpiritLeapPovRenderer.MAX_CACHED_FRAME_AGE_NANOS;
		require(SpiritLeapPovRenderer.isCachedFrameFresh(true, framePublishedNanos, frameBeforeExpiryNanos)
			&& !SpiritLeapPovRenderer.isCachedFrameFresh(true, framePublishedNanos, frameAtExpiryNanos)
			&& !SpiritLeapPovRenderer.isCachedFrameFresh(false, framePublishedNanos, framePublishedNanos),
			"a published POV stays fresh until the exact two-second expiry and a missing first frame stays hidden");
		require(SpiritLeapPovRenderer.cachedFrameAgeMillis(true, framePublishedNanos,
			framePublishedNanos + 1_500_000_000L) == 1_500L,
			"POV diagnostics age a frame from successful publication rather than refresh-attempt start");
		require(SpiritLeapPovRenderer.shouldDisplayQuadrant(true, true, framePublishedNanos,
			frameBeforeExpiryNanos, true, 2, 2)
			&& !SpiritLeapPovRenderer.shouldDisplayQuadrant(true, true, framePublishedNanos,
				frameBeforeExpiryNanos, true, 1, 2)
			&& !SpiritLeapPovRenderer.shouldDisplayQuadrant(true, true, framePublishedNanos,
				frameAtExpiryNanos, true, 2, 2)
			&& !SpiritLeapPovRenderer.shouldDisplayQuadrant(true, false, framePublishedNanos,
				frameBeforeExpiryNanos, true, 2, 2)
			&& !SpiritLeapPovRenderer.shouldDisplayQuadrant(true, true, framePublishedNanos,
				frameBeforeExpiryNanos, false, 2, 2)
			&& !SpiritLeapPovRenderer.shouldDisplayQuadrant(false, true, framePublishedNanos,
				frameBeforeExpiryNanos, true, 2, 2),
			"POV displays only an unexpired cached frame in its own quadrant while enabled");
		List<SpiritLeapRoster.Card> oneMemberCard = List.of(new SpiritLeapRoster.Card(2,
			new SpiritLeapRoster.Member("Solo", "Healer", 100, false, false)));
		for (int quadrant = 0; quadrant < SpiritLeapPovRenderer.FEED_COUNT; quadrant++) {
			require(SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", quadrant,
				oneMemberCard, false, true, quadrant == 2 ? "Solo" : null) == (quadrant == 2),
				"a cached teammate POV can display only in that teammate's current quadrant");
		}
		require(SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", 2, List.of(), false,
			true, "Solo")
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", 2, List.of(), false,
				false, null)
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", 2, List.of(), false,
				true, null)
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", 2, List.of(), true,
				true, "Solo")
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Other", 2, oneMemberCard,
				false, true, "Solo"),
			"an empty-card gap retains only a cache with a positively observed matching quadrant identity");
		List<SpiritLeapRoster.Card> previousCards = List.of(
			new SpiritLeapRoster.Card(0, new SpiritLeapRoster.Member("FormerZero", "Tank", 100, false, false)),
			new SpiritLeapRoster.Card(1, new SpiritLeapRoster.Member("FormerOne", "Mage", 100, false, false)),
			new SpiritLeapRoster.Card(2, new SpiritLeapRoster.Member("Solo", "Healer", 100, false, false)));
		SpiritLeapPovRenderer.FeedState previousQuadrantZero = new SpiritLeapPovRenderer.FeedState(0);
		SpiritLeapPovRenderer.FeedState previousQuadrantOne = new SpiritLeapPovRenderer.FeedState(1);
		SpiritLeapPovRenderer.FeedState soloQuadrant = new SpiritLeapPovRenderer.FeedState(2);
		previousQuadrantZero.frameAvailable = true;
		previousQuadrantZero.cachedFrameTargetName = "FormerZero";
		previousQuadrantZero.observeCardMapping(previousCards);
		previousQuadrantOne.frameAvailable = true;
		previousQuadrantOne.cachedFrameTargetName = "FormerOne";
		previousQuadrantOne.observeCardMapping(previousCards);
		soloQuadrant.frameAvailable = true;
		soloQuadrant.cachedFrameTargetName = "Solo";
		soloQuadrant.observeCardMapping(previousCards);
		previousQuadrantZero.observeCardMapping(oneMemberCard);
		previousQuadrantOne.observeCardMapping(oneMemberCard);
		soloQuadrant.observeCardMapping(oneMemberCard);
		require(previousQuadrantZero.cachedFrameIdentityInvalidated
			&& previousQuadrantOne.cachedFrameIdentityInvalidated
			&& !soloQuadrant.cachedFrameIdentityInvalidated
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("FormerZero", 0, List.of(),
				previousQuadrantZero.cachedFrameIdentityInvalidated,
				previousQuadrantZero.hasObservedCardMapping, previousQuadrantZero.lastObservedCardTargetName)
			&& !SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("FormerOne", 1, List.of(),
				previousQuadrantOne.cachedFrameIdentityInvalidated,
				previousQuadrantOne.hasObservedCardMapping, previousQuadrantOne.lastObservedCardTargetName)
			&& SpiritLeapPovRenderer.shouldDisplayCachedFrameIdentity("Solo", 2, List.of(),
				soloQuadrant.cachedFrameIdentityInvalidated,
				soloQuadrant.hasObservedCardMapping, soloQuadrant.lastObservedCardTargetName),
			"a newer one-member mapping hides multiple stale quadrant caches but retains its own frame through an empty gap");
		require(SpiritLeapPovRenderer.shouldRenderFeed(true, true, false)
			&& !SpiritLeapPovRenderer.shouldRenderFeed(true, false, false)
			&& !SpiritLeapPovRenderer.shouldRenderFeed(false, true, false)
			&& !SpiritLeapPovRenderer.shouldRenderFeed(true, true, true),
			"a POV refresh requires its target and chunk coverage and stays disabled after cleanup failure");
		require(SpiritLeapPovRenderer.shouldRoutePrivateClearTarget(true, true)
			&& !SpiritLeapPovRenderer.shouldRoutePrivateClearTarget(false, true)
			&& !SpiritLeapPovRenderer.shouldRoutePrivateClearTarget(true, false),
			"frame-graph clears use a private target only for an active POV renderer with a target");
		require(!SpiritLeapPovRenderer.isCompleteLoadedViewNeighborhood(0)
			&& !SpiritLeapPovRenderer.isCompleteLoadedViewNeighborhood(8)
			&& SpiritLeapPovRenderer.isCompleteLoadedViewNeighborhood(9),
			"a POV render requires all nine locally loaded chunks around its camera target");
		require(SpiritLeapPovRenderer.shouldUseMainCrosshair(false)
			&& !SpiritLeapPovRenderer.shouldUseMainCrosshair(true),
			"the main player's crosshair target is suppressed in teammate POV extraction");
		Camera povCamera = new Camera();
		Camera mainCamera = new Camera();
		require(SpiritLeapPovRenderer.cameraFor(true, povCamera, mainCamera) == povCamera
			&& SpiritLeapPovRenderer.cameraFor(false, povCamera, mainCamera) == mainCamera,
			"private LevelRenderer repositioning uses the teammate camera while the main renderer keeps its camera");
		long chunkProbeInterval = 250_000_000L;
		require(SpiritLeapPovRenderer.shouldProbeChunks(0L, 0L, false, 0, 0, 0, 0)
			&& !SpiritLeapPovRenderer.shouldProbeChunks(100L, 100L, true, 0, 0, 0, 0)
			&& SpiritLeapPovRenderer.shouldProbeChunks(100L + chunkProbeInterval, 100L, true, 0, 0, 0, 0)
			&& SpiritLeapPovRenderer.shouldProbeChunks(101L, 100L, true, 1, 0, 0, 0),
			"each POV checks nearby chunks initially, every 250 ms, or immediately after entering another chunk");

		require(SpiritLeapPovRenderer.shouldUpdateFeed(0L, 0L, false)
			&& !SpiritLeapPovRenderer.shouldUpdateFeed(100L + interval - 1L, 100L, true)
			&& SpiritLeapPovRenderer.shouldUpdateFeed(100L + interval, 100L, true),
			"each POV feed observes the 4 Hz minimum update interval");
	}

	private static void checkConfiguredCameraCadence() {
		var sparseFeeds = new SpiritLeapPovRenderer.FeedState[4];
		sparseFeeds[3] = new SpiritLeapPovRenderer.FeedState(3);
		sparseFeeds[3].targetName = "only-card";
		sparseFeeds[3].chunkNeighborhoodLoaded = true;
		require(SpiritLeapPovRenderer.firstReadyQuadrant(1, sparseFeeds) == 3
			&& new SpiritLeapPovRenderer.FeedScheduler().nextDueQuadrant(1, sparseFeeds, 0L, 4) == 3,
			"a one-feed compatibility budget selects the occupied card even when only q3 is populated");
		for (int fps : new int[] { 1, 4, 30 }) {
			long interval = SpiritLeapPovRenderer.updateIntervalNanos(fps);
			long gap = SpiritLeapPovRenderer.minimumRenderGapNanos(fps);
			require(interval >= 1_000_000_000L / fps && gap > 0,
				"each configured rate provides a positive paced interval");
			for (int occupied : new int[] { 1, 2, 4 }) {
				var scheduler = new SpiritLeapPovRenderer.FeedScheduler();
				var feeds = new SpiritLeapPovRenderer.FeedState[4];
				int[] counts = new int[4];
				long[] lastStarts = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };
				for (int i = 0; i < occupied; i++) {
					int q = occupied == 1 ? 3 : i;
					feeds[q] = new SpiritLeapPovRenderer.FeedState(q);
					feeds[q].targetName = "camera-" + q;
					feeds[q].chunkNeighborhoodLoaded = true;
				}
				long lastCompletion = Long.MIN_VALUE;
				for (long now = 0; now < 3_000_000_000L; now += 1_000_000L) {
					if (now >= 600_000_000L && now < 1_300_000_000L) continue;
					if (!scheduler.canStartRender(now, fps)) continue;
					int q = scheduler.nextDueQuadrant(4, feeds, now, fps);
					if (q < 0) continue;
					require(feeds[q] != null, "empty quadrants are never scheduled at any configured rate");
					if (lastCompletion != Long.MIN_VALUE) require(now - lastCompletion >= gap,
						"delayed frames never produce back-to-back render attempts");
					if (lastStarts[q] != Long.MIN_VALUE) require(now - lastStarts[q] >= interval,
						"a feed never exceeds its individual configured update rate");
					lastStarts[q] = now;
					counts[q]++;
					feeds[q].lastUpdateNanos = now;
					feeds[q].hasUpdated = true;
					lastCompletion = now + 2_000_000L;
					scheduler.renderAttemptCompleted(lastCompletion);
				}
				for (int q = 0; q < 4; q++) require(feeds[q] == null ? counts[q] == 0
					: counts[q] > 0 && counts[q] <= 3 * fps,
					"one, two, and four occupied feeds stay capped and every real feed makes progress");
			}
		}
	}

	private static void checkPovCameraHistoryLifecycle() {
		SpiritLeapPovRenderer.FeedState firstFeed = new SpiritLeapPovRenderer.FeedState(0);
		SpiritLeapPovRenderer.FeedState secondFeed = new SpiritLeapPovRenderer.FeedState(1);
		Object firstWorld = new Object();
		Object firstPlayer = new Object();
		Object replacementPlayer = new Object();
		Object nextWorld = new Object();
		int[] primed = { 0 };
		int[] cameraTicks = { 0 };
		var history = firstFeed.cameraHistory;

		require(firstFeed.camera != secondFeed.camera
			&& history.bind(firstWorld, firstPlayer, () -> primed[0]++)
			&& history.isBound() && history.isBoundTo(firstWorld, firstPlayer)
			&& !history.isBoundTo(firstWorld, replacementPlayer),
			"each feed owns a camera whose eye-height history is initialized on its first player binding");
		require(!history.bind(firstWorld, firstPlayer, () -> primed[0]++)
			&& history.advance(10L, () -> cameraTicks[0]++)
			&& !history.advance(10L, () -> cameraTicks[0]++)
			&& history.advance(11L, () -> cameraTicks[0]++)
			&& primed[0] == 1 && cameraTicks[0] == 2 && history.advancedTicks() == 2,
			"the production camera history primes once and advances exactly once for each distinct client tick");
		require(history.bind(firstWorld, replacementPlayer, () -> primed[0]++)
			&& history.advancedTicks() == 0
			&& history.advance(12L, () -> cameraTicks[0]++)
			&& history.bind(nextWorld, replacementPlayer, () -> primed[0]++)
			&& history.advance(13L, () -> cameraTicks[0]++)
			&& primed[0] == 3 && cameraTicks[0] == 4,
			"a target or world identity change re-primes history before the replacement camera advances");
		boolean[] cameraReset = { false };
		history.clear(() -> cameraReset[0] = true);
		require(cameraReset[0] && !history.isBound()
			&& !history.advance(14L, () -> cameraTicks[0]++),
			"cleanup drops camera ownership before reset and prevents stale feeds from ticking");
	}

	private static void checkPovRenderAttemptLifecycle() {
		SpiritLeapPovRenderAttempt<String> successfulAttempt = new SpiritLeapPovRenderAttempt<>();
		FakePovRenderAttemptOperations successfulOperations = new FakePovRenderAttemptOperations();
		successfulAttempt.execute(successfulOperations);
		require(successfulAttempt.failure() == null && successfulAttempt.failureStage().equals("none")
			&& successfulOperations.stages.equals(List.of(SpiritLeapPovRenderAttempt.CAMERA_STAGE,
				SpiritLeapPovRenderAttempt.FOG_SETUP_STAGE, SpiritLeapPovRenderAttempt.FOG_BUFFER_STAGE,
				SpiritLeapPovRenderAttempt.EXTRACTION_STAGE,
				SpiritLeapPovRenderAttempt.CONTEXT_PREPARATION_STAGE,
				SpiritLeapPovRenderAttempt.EXTRACTION_STAGE, SpiritLeapPovRenderAttempt.RENDER_STAGE,
				SpiritLeapPovRenderAttempt.FOG_CLEANUP_STAGE))
			&& successfulOperations.actions.equals(List.of("camera-update", "camera-extract", "fog-setup",
				"update-fog-buffer", "read-fog-buffer", "renderer-update", "prepare-context", "renderer-extract",
				"render:fog-slice", "end-fog-frame")),
			"the POV coordinator prepares Fabric context between renderer update and extraction, then ends the fog buffer");

		checkPovRenderAttemptFailure("camera-update", SpiritLeapPovRenderAttempt.CAMERA_STAGE,
			List.of("camera-update"), false, false);
		checkPovRenderAttemptFailure("camera-extract", SpiritLeapPovRenderAttempt.CAMERA_STAGE,
			List.of("camera-update", "camera-extract"), false, false);
		checkPovRenderAttemptFailure("fog-setup", SpiritLeapPovRenderAttempt.FOG_SETUP_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup"), false, false);
		checkPovRenderAttemptFailure("update-fog-buffer", SpiritLeapPovRenderAttempt.FOG_BUFFER_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "end-fog-frame"),
			true, false);
		checkPovRenderAttemptFailure("read-fog-buffer", SpiritLeapPovRenderAttempt.FOG_BUFFER_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "read-fog-buffer",
				"end-fog-frame"), true, false);
		checkPovRenderAttemptFailure("renderer-update", SpiritLeapPovRenderAttempt.EXTRACTION_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "read-fog-buffer",
				"renderer-update", "end-fog-frame"), true, false);
		checkPovRenderAttemptFailure("prepare-context", SpiritLeapPovRenderAttempt.CONTEXT_PREPARATION_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "read-fog-buffer",
				"renderer-update", "prepare-context", "end-fog-frame"), true, false);
		checkPovRenderAttemptFailure("renderer-extract", SpiritLeapPovRenderAttempt.EXTRACTION_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "read-fog-buffer",
				"renderer-update", "prepare-context", "renderer-extract", "end-fog-frame"), true, false);
		checkPovRenderAttemptFailure("render:fog-slice", SpiritLeapPovRenderAttempt.RENDER_STAGE,
			List.of("camera-update", "camera-extract", "fog-setup", "update-fog-buffer", "read-fog-buffer",
				"renderer-update", "prepare-context", "renderer-extract", "render:fog-slice", "end-fog-frame"), true, true);

		RuntimeException renderFailure = povRenderAttemptFailure("render-fog-slice");
		RuntimeException fogCleanupFailure = new IllegalArgumentException("PRIVATE_CLEANUP_TEXT");
		SpiritLeapPovRenderAttempt<String> failingAttempt = new SpiritLeapPovRenderAttempt<>();
		FakePovRenderAttemptOperations failingOperations = new FakePovRenderAttemptOperations();
		failingOperations.failureAt = "render:fog-slice";
		failingOperations.injectedFailure = renderFailure;
		failingOperations.endFailure = fogCleanupFailure;
		failingAttempt.execute(failingOperations);
		require(failingAttempt.failure() == renderFailure
			&& failingAttempt.failureStage().equals(SpiritLeapPovRenderAttempt.RENDER_STAGE)
			&& SpiritLeapPovRenderAttempt.failureTopSite(failingAttempt.failure()).equals(
				"checks.PovAttemptFailure.render-fog-slice(PovAttemptChecks.java:83)")
			&& renderFailure.getSuppressed().length == 1
			&& renderFailure.getSuppressed()[0] == fogCleanupFailure
			&& failingOperations.endFrameCalls == 1,
			"cleanup failure is suppressed onto the original render failure without changing its stage or top site");

		RuntimeException combinedPrimary = new IllegalStateException("PRIVATE_PRIMARY_TEXT");
		Throwable[] cleanupFailures = new Throwable[SpiritLeapPovRenderAttempt.MAX_SUPPRESSED_CLEANUP_FAILURES + 3];
		boolean primaryPreserved = true;
		for (int index = 0; index < cleanupFailures.length; index++) {
			cleanupFailures[index] = new IllegalArgumentException("PRIVATE_CLEANUP_TEXT_" + index);
			primaryPreserved &= SpiritLeapPovRenderAttempt.combineFailure(combinedPrimary, cleanupFailures[index])
				== combinedPrimary;
		}
		primaryPreserved &= SpiritLeapPovRenderAttempt.combineFailure(combinedPrimary, combinedPrimary)
			== combinedPrimary;
		Throwable[] retainedCleanupFailures = combinedPrimary.getSuppressed();
		boolean firstCleanupFailuresRetained = retainedCleanupFailures.length
			== SpiritLeapPovRenderAttempt.MAX_SUPPRESSED_CLEANUP_FAILURES;
		for (int index = 0; firstCleanupFailuresRetained && index < retainedCleanupFailures.length; index++) {
			firstCleanupFailuresRetained &= retainedCleanupFailures[index] == cleanupFailures[index];
		}
		Throwable standaloneCleanupFailure = new IllegalStateException("standalone-cleanup");
		require(primaryPreserved && firstCleanupFailuresRetained
			&& SpiritLeapPovRenderAttempt.combineFailure(null, standaloneCleanupFailure) == standaloneCleanupFailure,
			"repeated cleanup aggregation retains the primary, caps suppressed failures, and ignores self-suppression");

		RuntimeException diagnosticFailure = new RuntimeException("PRIVATE_PLAYER_NAME");
		diagnosticFailure.initCause(new IllegalStateException("PRIVATE_CAUSE_TEXT"));
		diagnosticFailure.addSuppressed(new IllegalArgumentException("PRIVATE_SUPPRESSED_TEXT"));
		StackTraceElement[] deepStack = new StackTraceElement[12];
		for (int index = 0; index < deepStack.length; index++) {
			deepStack[index] = new StackTraceElement("sample.PrivateRenderPass", "draw", "PrivateRenderPass.java", index + 1);
		}
		diagnosticFailure.setStackTrace(deepStack);
		String summary = SpiritLeapPovRenderAttempt.failureSummary(SpiritLeapPovRenderAttempt.RENDER_STAGE,
			diagnosticFailure);
		require(summary.length() <= SpiritLeapPovRenderAttempt.MAX_FAILURE_SUMMARY_CHARS
			&& summary.contains("stage=render-attempt") && summary.contains("root=java.lang.RuntimeException")
			&& summary.contains("cause=java.lang.IllegalStateException")
			&& summary.contains("suppressed=java.lang.IllegalArgumentException")
			&& !summary.contains("PRIVATE_PLAYER_NAME") && !summary.contains("PRIVATE_CAUSE_TEXT")
			&& !summary.contains("PRIVATE_SUPPRESSED_TEXT"),
			"failure diagnostics bound frames and causes while omitting exception messages");
	}

	private static void checkPovRenderAttemptFailure(String failureAt, String expectedStage,
		List<String> expectedActions, boolean fogBufferEntered, boolean includesRender) {
		RuntimeException failure = povRenderAttemptFailure(failureAt.replace(':', '-'));
		FakePovRenderAttemptOperations operations = new FakePovRenderAttemptOperations();
		operations.failureAt = failureAt;
		operations.injectedFailure = failure;
		SpiritLeapPovRenderAttempt<String> attempt = new SpiritLeapPovRenderAttempt<>();
		attempt.execute(operations);
		String expectedMethod = failureAt.replace(':', '-');
		require(attempt.failure() == failure && attempt.failureStage().equals(expectedStage)
			&& SpiritLeapPovRenderAttempt.failureTopSite(attempt.failure()).equals(
				"checks.PovAttemptFailure." + expectedMethod + "(PovAttemptChecks.java:83)")
			&& operations.actions.equals(expectedActions)
			&& operations.endFrameCalls == (fogBufferEntered ? 1 : 0)
			&& (operations.actions.contains("render:fog-slice") == includesRender),
			"failure at " + failureAt + " preserves top site and phase, stops later callbacks, and respects fog cleanup boundary");
	}

	private static RuntimeException povRenderAttemptFailure(String method) {
		RuntimeException failure = new IllegalStateException("PRIVATE_FAILURE_MESSAGE");
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("checks.PovAttemptFailure", method, "PovAttemptChecks.java", 83)
		});
		return failure;
	}

	private static final class FakePovRenderAttemptOperations implements SpiritLeapPovRenderAttempt.Operations<String> {
		final List<String> stages = new ArrayList<>();
		final List<String> actions = new ArrayList<>();
		String failureAt;
		RuntimeException injectedFailure;
		RuntimeException endFailure;
		int endFrameCalls;

		@Override public void stage(String stage) { stages.add(stage); }
		@Override public void updateCamera() { record("camera-update"); }
		@Override public void extractCamera() { record("camera-extract"); }
		@Override public void setupFog() { record("fog-setup"); }
		@Override public void updateFogBuffer() { record("update-fog-buffer"); }
		@Override public String readFogBuffer() { record("read-fog-buffer"); return "fog-slice"; }
		@Override public void updateRenderer() { record("renderer-update"); }
		@Override public void prepareExtractionContext() { record("prepare-context"); }
		@Override public void extractLevel() { record("renderer-extract"); }
		@Override public void render(String fogBuffer) { record("render:" + fogBuffer); }
		@Override public void endFogFrame() {
			endFrameCalls++;
			actions.add("end-fog-frame");
			if (endFailure != null) throw endFailure;
		}

		private void record(String action) {
			actions.add(action);
			if (action.equals(failureAt)) throw injectedFailure;
		}
	}

	private static void checkSodiumAccessorWarmup() {
		var accessor = new SpiritLeapPovRenderer.SodiumTerrainPassAccessor();
		Object privateTarget = new Object();
		Object mainTarget = new Object();
		FakeTerrainPass opaquePass = new FakeTerrainPass(false, privateTarget);
		FakeTerrainPass translucentPass = new FakeTerrainPass(true, privateTarget);
		boolean unavailableBeforePreflight = false;
		try {
			accessor.isTranslucentBranch(opaquePass, true);
		} catch (IllegalStateException expected) {
			unavailableBeforePreflight = true;
		}
		boolean opaqueRouteVerified;
		try {
			opaqueRouteVerified = accessor.verifyOpaqueProbeRoute(
				FakeTerrainPass.class, opaquePass, privateTarget, mainTarget);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("test Sodium preflight methods must be discoverable", failure);
		}
		require(unavailableBeforePreflight && opaqueRouteVerified
			&& !accessor.isTranslucentBranch(opaquePass, true)
			&& accessor.isTranslucentBranch(translucentPass, true),
			"shader-transparent opaque preflight initializes the shared hook accessor before getTarget and verifies target identity");
		accessor.clear();
		require(!accessor.isTranslucentBranch(translucentPass, false),
			"shader transparency off does not resolve or require an absent translucent target");
	}

	public static final class FakeTerrainPass {
		private final boolean translucent;
		private final Object target;

		FakeTerrainPass(boolean translucent, Object target) {
			this.translucent = translucent;
			this.target = target;
		}

		public boolean isTranslucent() { return translucent; }
		public Object getTarget() { return target; }
	}

	private static void checkPovModelViewRestoration() {
		var outputColorOverride = RenderSystem.outputColorTextureOverride;
		var outputDepthOverride = RenderSystem.outputDepthTextureOverride;
		try {
			RenderSystem.outputColorTextureOverride = null;
			RenderSystem.outputDepthTextureOverride = null;
			SpiritLeapPovRenderer.restoreOutputTextureOverrides(outputColorOverride, outputDepthOverride);
			require(RenderSystem.outputColorTextureOverride == outputColorOverride
				&& RenderSystem.outputDepthTextureOverride == outputDepthOverride,
				"POV cleanup restores the exact color and depth output texture override references");
		} finally {
			RenderSystem.outputColorTextureOverride = outputColorOverride;
			RenderSystem.outputDepthTextureOverride = outputDepthOverride;
		}

		Matrix4fStack stack = new Matrix4fStack(8);
		stack.translate(1.0f, 2.0f, 3.0f);
		Matrix4f before = new Matrix4f(stack);
		try {
			Field depthField = Matrix4fStack.class.getDeclaredField("curr");
			require(depthField.trySetAccessible(), "the pinned JOML stack depth is accessible to the focused check");
			Matrix4fStackAccess depthAccess = new Matrix4fStackAccess() {
				@Override public int geiler$getStackDepth() {
					try { return depthField.getInt(stack); }
					catch (IllegalAccessException failure) { throw new AssertionError(failure); }
				}
				@Override public void geiler$setStackDepth(int depth) {
					try { depthField.setInt(stack, depth); }
					catch (IllegalAccessException failure) { throw new AssertionError(failure); }
				}
			};
			int depth = depthAccess.geiler$getStackDepth();
			try {
				stack.pushMatrix();
				stack.translate(7.0f, 8.0f, 9.0f);
				stack.pushMatrix();
				stack.scale(2.0f);
				throw new IllegalStateException("simulated secondary-render failure");
			} catch (IllegalStateException expected) {
				SpiritLeapPovRenderer.restoreModelViewStack(stack, depthAccess, depth, before);
			}
			require(depthAccess.geiler$getStackDepth() == depth && stack.equals(before),
				"secondary-render cleanup restores the exact model-view matrix and stack depth after failure");
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("focused JOML stack restoration check could not inspect its pinned field", failure);
		}
	}

	private static final class LeapDispatchMenu extends AbstractContainerMenu {
		private LeapDispatchMenu() {
			super(null, 1);
			SimpleContainer inventory = new SimpleContainer(16);
			inventory.setItem(14, new ItemStack(Items.PLAYER_HEAD));
			for (int slot = 0; slot < 16; slot++) addSlot(new Slot(inventory, slot, 0, 0));
		}

		@Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
		@Override public boolean stillValid(Player player) { return true; }
	}

	private static void checkHoverTiming() {
		SpiritLeapLayout.HoverAnimation hover = new SpiritLeapLayout.HoverAnimation();
		hover.advance(-1, 0L);
		hover.advance(2, 100_000_000L);
		require(Math.abs(hover.amount(2) - 0.5f) < 0.001f,
			"card hover expands linearly over the 200ms Odin timing");
		hover.advance(2, 200_000_000L);
		require(hover.amount(2) == 1.0f, "card hover reaches full expansion after 200ms");
		hover.advance(-1, 400_000_000L);
		require(hover.amount(2) == 0.0f, "card hover contracts over the same duration");
	}

	private static NumberSetting number(SpiritLeapMenuModule module, String name) {
		return module.numberSettings().stream().filter(setting -> setting.name().equals(name))
			.findFirst().orElseThrow();
	}

	private static ColorSetting color(SpiritLeapMenuModule module, String name) {
		return module.colorSettings().stream().filter(setting -> setting.name().equals(name))
			.findFirst().orElseThrow();
	}

	private static BooleanSetting bool(SpiritLeapMenuModule module, String name) {
		return module.booleanSettings().stream().filter(setting -> setting.name().equals(name))
			.findFirst().orElseThrow();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
