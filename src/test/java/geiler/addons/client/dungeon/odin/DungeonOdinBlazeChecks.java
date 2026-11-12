package geiler.addons.client.dungeon.odin;

import java.util.List;
import java.util.Set;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Dependency-light acceptance checks for loaded-room gating and display-only Blaze ordering. */
public final class DungeonOdinBlazeChecks {
	private DungeonOdinBlazeChecks() { }

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
			Class<?> rules = DungeonOdinBlazeRules.class;
			check(rules.getField("MAX_ORDER_LINKS").getInt(null) == 3,
				"Blaze rule cap remains a public static Java constant");
			checkPublicStatic(rules.getMethod("allRoomChunksLoaded", int.class, int.class, int.class,
				java.util.function.BiPredicate.class), boolean.class);
			checkPublicStatic(rules.getMethod("orderTargets", List.class, boolean.class,
				DungeonOdinBlazeRules.Position.class), List.class);
			checkPublicStatic(rules.getMethod("parsePosition", String.class), DungeonOdinBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("positionForRoomName", String.class), DungeonOdinBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("resolvePosition", String.class, String.class),
				DungeonOdinBlazeRules.Position.class);
			checkPublicStatic(rules.getMethod("orderUnavailableMessage", String.class, String.class), String.class);
			checkPublicStatic(rules.getMethod("orderLinkCount", List.class, DungeonOdinBlazeRules.Position.class),
				int.class);
			check(Arrays.stream(DungeonOdinBlazeRules.Candidate.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList().equals(List.of("stableId", "health", "source",
					"alive", "loaded", "insideRoom", "blazeBodyResolved")),
				"Blaze Candidate retains its Java record components and order");
			check(Arrays.equals(DungeonOdinBlazeRules.Position.values(), new DungeonOdinBlazeRules.Position[] {
				DungeonOdinBlazeRules.Position.UNSET, DungeonOdinBlazeRules.Position.LOWER,
				DungeonOdinBlazeRules.Position.HIGHER }),
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
			var overlay = DungeonOdinBlazeOverlay.class;
			check(Modifier.isPublic(overlay.getConstructor().getModifiers()),
				"Blaze overlay retains its public no-argument constructor");
			Method render = overlay.getMethod("render",
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.class,
				DungeonOdinBlazeOverlay.Settings.class);
			check(Modifier.isPublic(render.getModifiers())
				&& render.getReturnType() == DungeonOdinBlazeOverlay.Frame.class,
				"Blaze overlay retains the public render entrypoint and frame return type");
			check(Modifier.isPublic(overlay.getMethod("reset").getModifiers()),
				"Blaze overlay retains its public reset entrypoint");
			checkStaticSignature(overlay.getDeclaredMethod("colorFor", DungeonOdinBlazeOverlay.Settings.class, int.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("fillFor", int.class, DungeonPuzzleVisualRenderer.BoxStyle.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("lastLinkIndex", DungeonOdinBlazeOverlay.Settings.class, int.class), int.class);
			checkStaticSignature(overlay.getDeclaredMethod("isLastTargetTransition", int.class, int.class), boolean.class);
			check(!DungeonOdinBlazeOverlay.Settings.class.isRecord() && !DungeonOdinBlazeOverlay.Frame.class.isRecord(),
				"Kotlin value wrappers retain their constructors and value behavior; native record metadata is the documented migration tradeoff");
			check(Modifier.isPublic(DungeonOdinBlazeOverlay.Settings.class.getConstructor(boolean.class, int.class,
				float.class, DungeonPuzzleVisualRenderer.BoxStyle.class, int.class, int.class, int.class, int.class,
				boolean.class).getModifiers())
				&& Modifier.isPublic(DungeonOdinBlazeOverlay.Settings.class
					.getMethod("shouldSendComplete", DungeonOdinBlazeOverlay.Frame.class).getModifiers())
				&& DungeonOdinBlazeOverlay.Settings.class.getMethod("lineCount").getReturnType() == int.class
				&& DungeonOdinBlazeOverlay.Settings.class.getMethod("lineWidth").getReturnType() == float.class
				&& DungeonOdinBlazeOverlay.Settings.class.getMethod("style").getReturnType() == DungeonPuzzleVisualRenderer.BoxStyle.class,
				"Blaze settings retains its canonical constructor, component accessors, and public completion predicate");
			check(Modifier.isPublic(DungeonOdinBlazeOverlay.Frame.class.getConstructor(
				String.class, int.class, boolean.class).getModifiers())
				&& Modifier.isStatic(DungeonOdinBlazeOverlay.Frame.class.getMethod("none").getModifiers())
				&& DungeonOdinBlazeOverlay.Frame.class.getMethod("roomName").getReturnType() == String.class
				&& DungeonOdinBlazeOverlay.Frame.class.getMethod("targetCount").getReturnType() == int.class
				&& DungeonOdinBlazeOverlay.Frame.class.getMethod("completedLastTarget").getReturnType() == boolean.class,
				"Blaze frame retains its canonical constructor and static none factory");
			check(DungeonOdinBlazeOverlay.Frame.none().roomName() == null
				&& DungeonOdinBlazeOverlay.Frame.none().targetCount() == -1
				&& !DungeonOdinBlazeOverlay.Frame.none().completedLastTarget(),
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
		var settings = new DungeonOdinBlazeOverlay.Settings(true, 12, 8.0f,
			DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, green, gold, red, white, true);
		check(settings.lineCount() == 10 && settings.lineWidth() == 5.0f,
			"Blaze line count and width are clamped to their complete supported ranges");
		check(DungeonOdinBlazeOverlay.colorFor(settings, 0) == green
			&& DungeonOdinBlazeOverlay.colorFor(settings, 1) == gold
			&& DungeonOdinBlazeOverlay.colorFor(settings, 2) == red
			&& DungeonOdinBlazeOverlay.colorFor(settings, 3) == white
			&& DungeonOdinBlazeOverlay.colorFor(null, 0) == 0xFFFFFFFF,
			"Blaze target colors use the configured first, second, third, and remaining colors");
		check(DungeonOdinBlazeOverlay.lastLinkIndex(settings, 11) == 10
			&& DungeonOdinBlazeOverlay.lastLinkIndex(settings, 2) == 1
			&& DungeonOdinBlazeOverlay.lastLinkIndex(new DungeonOdinBlazeOverlay.Settings(false, 10, 2.0f,
				settings.style(), green, gold, red, white, false), 8) == 0,
			"next-line links honor the toggle, setting cap, and observed target count");
		check(DungeonOdinBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE) == 0
			&& DungeonOdinBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.FILLED) == green
			&& DungeonOdinBlazeOverlay.fillFor(green, DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE) == 0x5F55FF55
			&& DungeonOdinBlazeOverlay.fillFor(green, null) == 0x5F55FF55,
			"Blaze fill rendering follows the selected box style and preserves half-alpha fill");
		check(DungeonOdinBlazeOverlay.isLastTargetTransition(1, 0)
			&& !DungeonOdinBlazeOverlay.isLastTargetTransition(2, 0)
			&& !DungeonOdinBlazeOverlay.isLastTargetTransition(0, 0)
			&& !DungeonOdinBlazeOverlay.isLastTargetTransition(-1, 0),
			"completion fires only for the observed one-target-to-zero edge");
		check(settings.shouldSendComplete(new DungeonOdinBlazeOverlay.Frame("Higher Blaze", 0, true))
			&& !settings.shouldSendComplete(new DungeonOdinBlazeOverlay.Frame("Higher Blaze", 0, false))
			&& !settings.shouldSendComplete(null)
			&& !new DungeonOdinBlazeOverlay.Settings(true, 1, 2.0f, settings.style(), green, gold, red, white, false)
				.shouldSendComplete(new DungeonOdinBlazeOverlay.Frame("Higher Blaze", 0, true)),
			"Send Complete gates only the source-confirmed completion signal");
		var fallback = new DungeonOdinBlazeOverlay.Settings(true, -2, Float.NaN, null, green, gold, red, white, false);
		check(fallback.lineCount() == 1 && fallback.lineWidth() == 2.0f
			&& fallback.style() == DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE,
			"invalid Blaze settings fail back to source defaults");
		var equalSettings = new DungeonOdinBlazeOverlay.Settings(true, 10, 5.0f,
			DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, green, gold, red, white, true);
		check(settings.equals(equalSettings) && settings.hashCode() == equalSettings.hashCode()
			&& settings.toString().startsWith("Settings[nextLine=true, lineCount=10, lineWidth=5.0"),
			"Kotlin Blaze values retain record-style equality, hashing, and display after normalization");
	}

	private static void loadedRoomFootprintIsComplete() {
		Set<String> allNine = Set.of("-13,-13", "-13,-12", "-13,-11", "-12,-13", "-12,-12",
			"-12,-11", "-11,-13", "-11,-12", "-11,-11");
		check(DungeonOdinBlazeRules.allRoomChunksLoaded(-200, -200, 31,
			(x, z) -> allNine.contains(x + "," + z)), "a room spanning nine loaded chunks is complete");
		check(!DungeonOdinBlazeRules.allRoomChunksLoaded(-200, -200, 31,
			(x, z) -> !"-12,-12".equals(x + "," + z)), "one missing footprint chunk rejects the whole room");
		check(!DungeonOdinBlazeRules.allRoomChunksLoaded(-200, -200, 0, (x, z) -> true),
			"empty footprints are rejected");
		check(!DungeonOdinBlazeRules.allRoomChunksLoaded(-200, -200, 31, null),
			"missing loaded-state probes fail closed");
	}

	private static void labelSelectionAndOrderFailClosed() {
		var high = candidate("z-blaze", 300, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var low = candidate("a-blaze", 100, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var standLow = candidate("stand-target", 90, DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE,
			true, true, true, true);
		var unresolved = candidate("unresolved", 10, DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE,
			true, true, true, false);
		var unconfirmedDirect = candidate("unconfirmed-direct", 9, DungeonOdinBlazeRules.Source.BLAZE_ENTITY,
			true, true, true, false);
		var unloaded = candidate("unloaded", 1, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, false, true, true);
		var outside = candidate("outside", 2, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, true, false, true);
		var dead = candidate("dead", 3, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, false, true, true, true);
		check(DungeonOdinBlazeRules.orderTargets(List.of(high, low, standLow), false,
			DungeonOdinBlazeRules.Position.LOWER).isEmpty(), "partial observations produce no targets");
		List<DungeonOdinBlazeRules.Candidate> lower = DungeonOdinBlazeRules.orderTargets(
			List.of(high, low, standLow, unresolved, unconfirmedDirect, unloaded, outside, dead), true,
			DungeonOdinBlazeRules.Position.LOWER);
		check(lower.size() == 1 && lower.getFirst().stableId().equals("stand-target"),
			"resolved armor-stand labels take precedence and invalid or outside candidates are skipped");
		List<DungeonOdinBlazeRules.Candidate> higher = DungeonOdinBlazeRules.orderTargets(
			List.of(high, low), true, DungeonOdinBlazeRules.Position.HIGHER);
		check(higher.stream().map(DungeonOdinBlazeRules.Candidate::stableId).toList().equals(List.of("a-blaze", "z-blaze")),
			"Higher Blaze orders lower health first");
		check(DungeonOdinBlazeRules.orderTargets(List.of(high, low), true, DungeonOdinBlazeRules.Position.LOWER)
			.stream().map(DungeonOdinBlazeRules.Candidate::stableId).toList().equals(List.of("z-blaze", "a-blaze")),
			"Lower Blaze orders higher health first");
		var tiedZ = candidate("tie-z", 200, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		var tiedA = candidate("tie-a", 200, DungeonOdinBlazeRules.Source.BLAZE_ENTITY, true, true, true, true);
		check(DungeonOdinBlazeRules.orderTargets(List.of(tiedZ, tiedA), true, DungeonOdinBlazeRules.Position.LOWER)
			.stream().map(DungeonOdinBlazeRules.Candidate::stableId).toList().equals(List.of("tie-a", "tie-z")),
			"equal-health targets use deterministic stable-id ordering");
		check(DungeonOdinBlazeRules.orderTargets(List.of(
			candidate("duplicate", 200, DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE, true, true, true, true),
			candidate("duplicate", 190, DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE, true, true, true, true)),
			true, DungeonOdinBlazeRules.Position.LOWER).isEmpty(), "conflicting labels for one body are suppressed");
		List<DungeonOdinBlazeRules.Candidate> unset = DungeonOdinBlazeRules.orderTargets(
			List.of(high, low), true, DungeonOdinBlazeRules.Position.UNSET);
		check(unset.stream().map(DungeonOdinBlazeRules.Candidate::stableId).toList().equals(List.of("a-blaze", "z-blaze")),
			"unset room orientation uses stable neutral ordering");
		check(DungeonOdinBlazeRules.orderLinkCount(unset, DungeonOdinBlazeRules.Position.UNSET) == 0
			&& DungeonOdinBlazeRules.orderLinkCount(higher, DungeonOdinBlazeRules.Position.HIGHER) == 1,
			"order links are hidden until a room orientation is selected");
		List<DungeonOdinBlazeRules.Candidate> many = java.util.stream.IntStream.range(0, 8)
			.mapToObj(index -> candidate("target-" + index, index, DungeonOdinBlazeRules.Source.BLAZE_ENTITY,
				true, true, true, true)).toList();
		check(DungeonOdinBlazeRules.orderLinkCount(many, DungeonOdinBlazeRules.Position.HIGHER)
			== DungeonOdinBlazeRules.MAX_ORDER_LINKS, "the visual route is capped at three links");
		check(DungeonOdinBlazeRules.parsePosition("lower") == DungeonOdinBlazeRules.Position.LOWER
			&& DungeonOdinBlazeRules.parsePosition("unknown") == DungeonOdinBlazeRules.Position.UNSET,
			"settings parse known orientations and fail closed on unknown values");
		check(DungeonOdinBlazeRules.positionForRoomName("Lower Blaze") == DungeonOdinBlazeRules.Position.LOWER
			&& DungeonOdinBlazeRules.positionForRoomName("Higher Blaze") == DungeonOdinBlazeRules.Position.HIGHER
			&& DungeonOdinBlazeRules.positionForRoomName("Blaze") == DungeonOdinBlazeRules.Position.UNSET,
			"only exact catalog room names infer Blaze ordering orientation");
		check(DungeonOdinBlazeRules.resolvePosition("Lower Blaze", "HIGHER") == DungeonOdinBlazeRules.Position.LOWER
			&& DungeonOdinBlazeRules.resolvePosition("Higher Blaze", "LOWER") == DungeonOdinBlazeRules.Position.HIGHER,
			"exact catalog room orientation auto-solves despite a conflicting manual fallback");
		check(DungeonOdinBlazeRules.resolvePosition("Blaze", "HIGHER") == DungeonOdinBlazeRules.Position.HIGHER
			&& DungeonOdinBlazeRules.resolvePosition("Unknown", "UNSET") == DungeonOdinBlazeRules.Position.UNSET,
			"manual orientation remains available only when room identity is not exact");
		String exactRoomMessage = DungeonOdinBlazeRules.orderUnavailableMessage("Lower Blaze", "HIGHER");
		String unknownRoomMessage = DungeonOdinBlazeRules.orderUnavailableMessage("Blaze", "UNSET");
		check(exactRoomMessage.startsWith("No ordered Blaze targets") && !exactRoomMessage.contains("set Blaze Room Position")
			&& unknownRoomMessage.startsWith("Orientation unknown")
			&& unknownRoomMessage.contains("set Blaze Room Position"),
			"display guidance distinguishes a missing order from unknown orientation and never requests override for an exact room");
		check(DungeonPuzzleLogic.parseBlazeHealth("§8[§7Lv100§8]  §cBlaze §a1,234/2,000§c❤") == 2000
			&& DungeonPuzzleLogic.parseBlazeHealth("§cBlaze 1,234/2,000❤") == null,
			"Blaze ordering uses the exact rendered ArmorStand nameplate and its maximum-health field");
	}

	private static DungeonOdinBlazeRules.Candidate candidate(String id, int health,
		DungeonOdinBlazeRules.Source source, boolean alive, boolean loaded, boolean inside, boolean resolved) {
		return new DungeonOdinBlazeRules.Candidate(id, health, source, alive, loaded, inside, resolved);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
