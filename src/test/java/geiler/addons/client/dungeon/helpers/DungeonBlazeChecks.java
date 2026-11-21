package geiler.addons.client.dungeon.helpers;

import java.util.List;
import java.util.Set;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Dependency-light acceptance checks for loaded-room gating and display-only Blaze ordering. */
public final class DungeonBlazeChecks {
	private DungeonBlazeChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		loadedRoomFootprintIsComplete();
		labelSelectionAndOrderFailClosed();
		visualSettingsAndCompletionEdgeAreStable();
		javaBoundaryAbiIsStable();
		blazeRulesJavaAbiIsStable();
	}

	private static void blazeRulesJavaAbiIsStable() {
		try {
			Class<?> rules = DungeonBlazeRules.class;
			check(rules.getField("MAX_ORDER_LINKS").getInt(null) == 3,
				"Blaze rule cap remains a public static Java constant");
			checkPublicStatic(rules.getMethod("allRoomChunksLoaded", int.class, int.class, int.class,
				java.util.function.BiPredicate.class), boolean.class);
			checkPublicStatic(rules.getMethod("orderTargets", List.class, boolean.class,
				DungeonBlazeRules.Position.class), List.class);
			checkPublicStatic(rules.getMethod("parsePosition", String.class), DungeonBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("positionForRoomName", String.class), DungeonBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("resolvePosition", String.class, String.class),
				DungeonBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("orderUnavailableMessage", String.class, String.class), String.class);
			checkPublicStatic(rules.getMethod("orderLinkCount", List.class, DungeonBlazeRules.Position.class),
				int.class);
			check(Arrays.stream(DungeonBlazeRules.Candidate.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList().equals(List.of("stableId", "health", "source",
					"alive", "loaded", "insideRoom", "blazeBodyResolved")),
				"Blaze Candidate retains its Java record components and order");
			check(Arrays.equals(DungeonBlazeRules.Position.values(), new DungeonBlazeRules.Position[] {
				DungeonBlazeRules.Position.UNSET, DungeonBlazeRules.Position.LOWER,
				DungeonBlazeRules.Position.HIGHER }),
				"Blaze Position retains its Java enum order");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Blaze rule Java ABI changed", exception);
		}
	}

	private static void checkPublicStatic(Method method, Class<?> returnType) {
		check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
			&& method.getReturnType() == returnType,
			"Blaze rule retains public static signature " + method.getName());
	}

	private static void javaBoundaryAbiIsStable() {
		try {
			var overlay = DungeonBlazeOverlay.class;
			check(Modifier.isPublic(overlay.getConstructor().getModifiers()),
				"Blaze overlay retains its public no-argument constructor");
			Method render = overlay.getMethod("render",
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.class,
				DungeonBlazeOverlay.Settings.class);
			check(Modifier.isPublic(render.getModifiers())
				&& render.getReturnType() == DungeonBlazeOverlay.Frame.class,
				"Blaze overlay retains the public render entrypoint and frame return type");
			check(Modifier.isPublic(overlay.getMethod("reset").getModifiers()),
				"Blaze overlay retains its public reset entrypoint");
			checkStaticSignature(overlay.getDeclaredMethod("colorFor", DungeonBlazeOverlay.Settings.class, int.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("fillFor", int.class, DungeonPuzzleVisualRenderer.BoxStyle.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("lastLinkIndex", DungeonBlazeOverlay.Settings.class, int.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("isLastTargetTransition", int.class, int.class), boolean.class);
			check(!DungeonBlazeOverlay.Settings.class.isRecord() && !DungeonBlazeOverlay.Frame.class.isRecord(),
				"Kotlin value wrappers retain their constructors and value behavior; native record metadata is the documented migration tradeoff");
			check(Modifier.isPublic(DungeonBlazeOverlay.Settings.class.getConstructor(boolean.class, int.class,
				float.class, DungeonPuzzleVisualRenderer.BoxStyle.class, int.class, int.class, int.class, int.class,
				boolean.class).getModifiers())
				&& Modifier.isPublic(DungeonBlazeOverlay.Settings.class
					.getMethod("shouldSendComplete", DungeonBlazeOverlay.Frame.class).getModifiers())
				&& DungeonBlazeOverlay.Settings.class.getMethod("lineCount").getReturnType() == int.class
				&& DungeonBlazeOverlay.Settings.class.getMethod("lineWidth").getReturnType() == float.class
				&& DungeonBlazeOverlay.Settings.class.getMethod("style").getReturnType() == DungeonPuzzleVisualRenderer.BoxStyle.class,
				"Blaze settings retains its canonical constructor, component accessors, and public completion predicate");
			check(Modifier.isPublic(DungeonBlazeOverlay.Frame.class.getConstructor(
				String.class, int.class, boolean.class).getModifiers())
				&& Modifier.isStatic(DungeonBlazeOverlay.Frame.class.getMethod("none").getModifiers())
				&& DungeonBlazeOverlay.Frame.class.getMethod("roomName").getReturnType() == String.class
				&& DungeonBlazeOverlay.Frame.class.getMethod("targetCount").getReturnType() == int.class
				&& DungeonBlazeOverlay.Frame.class.getMethod("completedLastTarget").getReturnType() == boolean.class,
				"Blaze frame retains its canonical constructor and static none factory");
			check(DungeonBlazeOverlay.Frame.none().roomName() == null
				&& DungeonBlazeOverlay.Frame.none().targetCount() == -1
				&& !DungeonBlazeOverlay.Frame.none().completedLastTarget(),
				"Blaze no-frame sentinel retains its source values");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Blaze Java boundary ABI changed", exception);
		}
	}

	private static void checkStaticSignature(Method method, Class<?> returnType) {
		check(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == returnType,
			"Blaze static helper retains its method name and descriptor: " + method.getName());
	}

	private static void visualSettingsAndCompletionEdgeAreStable() {
		int green = 0xBF55FF55;
		int gold = 0xBFFFAA00;
		int red = 0xBFFF5555;
		int white = 0x4DFFFFFF;
		var settings = new DungeonBlazeOverlay.Settings(true, 12, 8.0f,
			DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, green, gold, red, white, true);
		check(settings.lineCount() == 10 && settings.lineWidth() == 5.0f,
			"Blaze line count and width are clamped to their complete supported ranges");
		check(DungeonBlazeOverlay.colorFor(settings, 0) == green
			&& DungeonBlazeOverlay.colorFor(settings, 1) == gold
			&& DungeonBlazeOverlay.colorFor(settings, 2) == red
			&& DungeonBlazeOverlay.colorFor(settings, 3) == white
			&& DungeonBlazeOverlay.colorFor(null, 0) == 0xFFFFFFFF,
			"Blaze target colors use the configured first, second, third, and remaining colors");
		check(DungeonBlazeOverlay.lastLinkIndex(settings, 11) == 10
			&& DungeonBlazeOverlay.lastLinkIndex(settings, 2) == 1
			&& DungeonBlazeOverlay.lastLinkIndex(new DungeonBlazeOverlay.Settings(false, 10, 2.0f,
				settings.style(), green, gold, red, white, false), 8) == 0,
			"next-line links honor the toggle, setting cap, and observed target count");
		check(DungeonBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE) == 0
			&& DungeonBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.FILLED) == green
			&& DungeonBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE) == 0x5F55FF55
			&& DungeonBlazeOverlay.fillFor(green, null) == 0x5F55FF55,
			"Blaze fill rendering follows the selected box style and preserves half-alpha fill");
		check(DungeonBlazeOverlay.isLastTargetTransition(1, 0)
			&& !DungeonBlazeOverlay.isLastTargetTransition(2, 0)
			&& !DungeonBlazeOverlay.isLastTargetTransition(0, 0)
			&& !DungeonBlazeOverlay.isLastTargetTransition(-1, 0),
			"completion fires only for the observed one-target-to-zero edge");
		check(settings.shouldSendComplete(new DungeonBlazeOverlay.Frame("Higher Blaze", 0, true))
			&& !settings.shouldSendComplete(new DungeonBlazeOverlay.Frame("Higher Blaze", 0, false))
			&& !settings.shouldSendComplete(null)
			&& !new DungeonBlazeOverlay.Settings(true, 1, 2.0f, settings.style(), green, gold, red, white, false)
				.shouldSendComplete(new DungeonBlazeOverlay.Frame("Higher Blaze", 0, true)),
			"Send Complete gates only the source-confirmed completion signal");
		var fallback = new DungeonBlazeOverlay.Settings(true, -2, Float.NaN, null, green, gold, red, white, false);
		check(fallback.lineCount() == 1 && fallback.lineWidth() == 2.0f
			&& fallback.style() == DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE,
			"invalid Blaze settings fail back to source defaults");
		var equalSettings = new DungeonBlazeOverlay.Settings(true, 10, 5.0f,
			DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, green, gold, red, white, true);
		check(settings.equals(equalSettings) && settings.hashCode() == equalSettings.hashCode()
			&& settings.toString().startsWith("Settings[nextLine=true, lineCount=10, lineWidth=5.0"),
			"Kotlin Blaze values retain record-style equality, hashing, and display after normalization");
	}

	private static void loadedRoomFootprintIsComplete() {
		Set<String> allNine = Set.of("-13,-13", "-13,-12", "-13,-11", "-12,-13", "-12,-12",
			"-12,-11", "-11,-13", "-11,-12", "-11,-11");
		check(DungeonBlazeRules.allRoomChunksLoaded(-200, -200, 31,
			(x, z) -> allNine.contains(x + "," + z)), "a room spanning nine loaded chunks is complete");
		check(!DungeonBlazeRules.allRoomChunksLoaded(-200, -200, 31,
			(x, z) -> !"-12,-12".equals(x + "," + z)), "one missing footprint chunk rejects the whole room");
		check(!DungeonBlazeRules.allRoomChunksLoaded(-200, -200, 0, (x, z) -> true),
			"empty footprints are rejected");
		check(!DungeonBlazeRules.allRoomChunksLoaded(-200, -200, 31, null),
			"missing loaded-state probes fail closed");
	}

	private static void labelSelectionAndOrderFailClosed() {
		var high = candidate("z-blaze", 300, DungeonBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var low = candidate("a-blaze", 100, DungeonBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var standLow = candidate("stand-target", 90, DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE,
			true, true, true, true);
		var unresolved = candidate("unresolved", 10, DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE,
			true, true, true, false);
		var unconfirmedDirect = candidate("unconfirmed-direct", 9, DungeonBlazeRules.Source.BLAZE_ENTITY,
			true, true, true, false);
		var unloaded = candidate("unloaded", 1, DungeonBlazeRules.Source.BLAZE_ENTITY, true, false, true, true);
		var outside = candidate("outside", 2, DungeonBlazeRules.Source.BLAZE_ENTITY, true, true, false, true);
		var dead = candidate("dead", 3, DungeonBlazeRules.Source.BLAZE_ENTITY, false, true, true, true);
		check(DungeonBlazeRules.orderTargets(List.of(high, low, standLow), false,
			DungeonBlazeRules.Position.LOWER).isEmpty(), "partial observations produce no targets");
		List<DungeonBlazeRules.Candidate> lower = DungeonBlazeRules.orderTargets(
			List.of(high, low, standLow, unresolved, unconfirmedDirect, unloaded, outside, dead), true,
			DungeonBlazeRules.Position.LOWER);
		check(lower.size() == 1 && lower.getFirst().stableId().equals("stand-target"),
			"resolved armor-stand labels take precedence and invalid or outside candidates are skipped");
		List<DungeonBlazeRules.Candidate> higher = DungeonBlazeRules.orderTargets(
			List.of(high, low), true, DungeonBlazeRules.Position.HIGHER);
		check(higher.stream().map(DungeonBlazeRules.Candidate::stableId).toList().equals(List.of("a-blaze", "z-blaze")),
			"Higher Blaze orders lower health first");
		check(DungeonBlazeRules.orderTargets(List.of(high, low), true, DungeonBlazeRules.Position.LOWER)
			.stream().map(DungeonBlazeRules.Candidate::stableId).toList().equals(List.of("z-blaze", "a-blaze")),
			"Lower Blaze orders higher health first");
		var tiedZ = candidate("tie-z", 200, DungeonBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var tiedA = candidate("tie-a", 200, DungeonBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		check(DungeonBlazeRules.orderTargets(List.of(tiedZ, tiedA), true, DungeonBlazeRules.Position.LOWER)
			.stream().map(DungeonBlazeRules.Candidate::stableId).toList().equals(List.of("tie-a", "tie-z")),
			"equal-health targets use deterministic stable-id ordering");
		check(DungeonBlazeRules.orderTargets(List.of(
			candidate("duplicate", 200, DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE, true, true, true, true),
			candidate("duplicate", 190, DungeonBlazeRules.Source.ARMOR_STAND_NAMEPLATE, true, true, true, true)),
			true, DungeonBlazeRules.Position.LOWER).isEmpty(), "conflicting labels for one body are suppressed");
		List<DungeonBlazeRules.Candidate> unset = DungeonBlazeRules.orderTargets(
			List.of(high, low), true, DungeonBlazeRules.Position.UNSET);
		check(unset.stream().map(DungeonBlazeRules.Candidate::stableId).toList().equals(List.of("a-blaze", "z-blaze")),
			"unset room orientation uses stable neutral ordering");
		check(DungeonBlazeRules.orderLinkCount(unset, DungeonBlazeRules.Position.UNSET) == 0
			&& DungeonBlazeRules.orderLinkCount(higher, DungeonBlazeRules.Position.HIGHER) == 1,
			"order links are hidden until a room orientation is selected");
		List<DungeonBlazeRules.Candidate> many = java.util.stream.IntStream.range(0, 8)
			.mapToObj(index -> candidate("target-" + index, index, DungeonBlazeRules.Source.BLAZE_ENTITY,
				true, true, true, true)).toList();
		check(DungeonBlazeRules.orderLinkCount(many, DungeonBlazeRules.Position.HIGHER)
			== DungeonBlazeRules.MAX_ORDER_LINKS, "the visual route is capped at three links");
		check(DungeonBlazeRules.parsePosition("lower") == DungeonBlazeRules.Position.LOWER
			&& DungeonBlazeRules.parsePosition("unknown") == DungeonBlazeRules.Position.UNSET,
			"settings parse known orientations and fail closed on unknown values");
		check(DungeonBlazeRules.positionForRoomName("Lower Blaze") == DungeonBlazeRules.Position.LOWER
			&& DungeonBlazeRules.positionForRoomName("Higher Blaze") == DungeonBlazeRules.Position.HIGHER
			&& DungeonBlazeRules.positionForRoomName("Blaze") == DungeonBlazeRules.Position.UNSET,
			"only exact catalog room names infer Blaze ordering orientation");
		check(DungeonBlazeRules.resolvePosition("Lower Blaze", "HIGHER") == DungeonBlazeRules.Position.LOWER
			&& DungeonBlazeRules.resolvePosition("Higher Blaze", "LOWER") == DungeonBlazeRules.Position.HIGHER,
			"exact catalog room orientation auto-solves despite a conflicting manual fallback");
		check(DungeonBlazeRules.resolvePosition("Blaze", "HIGHER") == DungeonBlazeRules.Position.HIGHER
			&& DungeonBlazeRules.resolvePosition("Unknown", "UNSET") == DungeonBlazeRules.Position.UNSET,
			"manual orientation remains available only when room identity is not exact");
		String exactRoomMessage = DungeonBlazeRules.orderUnavailableMessage("Lower Blaze", "HIGHER");
		String unknownRoomMessage = DungeonBlazeRules.orderUnavailableMessage("Blaze", "UNSET");
		check(exactRoomMessage.startsWith("No ordered Blaze targets") && !exactRoomMessage.contains("set Blaze Room Position")
			&& unknownRoomMessage.startsWith("Orientation unknown")
			&& unknownRoomMessage.contains("set Blaze Room Position"),
			"display guidance distinguishes a missing order from unknown orientation and never requests override for an exact room");
		check(DungeonPuzzleLogic.parseBlazeHealth("§8[§7Lv100§8]  §cBlaze §a1,234/2,000§c❤") == 2000
			&& DungeonPuzzleLogic.parseBlazeHealth("§cBlaze 1,234/2,000❤") == null,
			"Blaze ordering uses the exact rendered ArmorStand nameplate and its maximum-health field");
	}

	private static DungeonBlazeRules.Candidate candidate(String id, int health,
		DungeonBlazeRules.Source source, boolean alive, boolean loaded, boolean inside, boolean resolved) {
		return new DungeonBlazeRules.Candidate(id, health, source, alive, loaded, inside, resolved);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
