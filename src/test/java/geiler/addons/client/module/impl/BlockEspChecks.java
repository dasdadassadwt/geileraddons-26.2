package geiler.addons.client.module.impl;

import geiler.addons.client.gui.BlockPickerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Offline checks for exact matching, loaded-space bounds, clustering, island defaults, and uncapped collection. */
public final class BlockEspChecks {
	private BlockEspChecks() { }

	public static void run() {
		checkJavaInteropAbi();
		checkBlockPickerCatalog();
		checkRenderCacheImmutability();
		checkScanCachePolicy();
		check(BlockEspRules.matches("minecraft:stone", Blocks.STONE), "registered block id matches its exact block type");
		check(BlockEspRules.resolve("mod_missing:not_registered") == null,
			"unknown saved block ids do not fall back to air or another registry default");
		check(!BlockEspRules.matches("minecraft:dirt", Blocks.STONE), "different registered block ids never match");
		check(!BlockEspRules.matches("", Blocks.STONE), "blank selection matches nothing");

		BlockEspEntry entry = BlockEspModule.INSTANCE.blank(50_000);
		check(entry.id() == 50_000 && entry.blockId().isEmpty() && entry.folderId() == null,
			"new entries retain the supplied identity and start without a block or folder");
		check(blockSelectionLabel(entry).equals("Block: Not selected"),
			"an entry shows that no block has been selected yet");
		entry.setBlockId("minecraft:stone");
		check(blockSelectionLabel(entry).equals("Block: minecraft:stone"),
			"an entry's picker action continues to show its selected registry ID");
		BlockEspEntry normalized = BlockEspModule.INSTANCE.blank(50_001);
		normalized.setBlockId(" \tminecraft:stone\r\n");
		check(normalized.blockId().equals("minecraft:stone"),
			"block identifiers trim Java-compatible ASCII control whitespace");
		normalized.setBlockId("\u00A0minecraft:stone\u00A0");
		check(normalized.blockId().equals("\u00A0minecraft:stone\u00A0"),
			"block identifiers preserve non-ASCII edge whitespace as Java String.trim() did");
		BlockEspEntry whitespace = BlockEspModule.INSTANCE.blank(50_002);
		whitespace.setFolderId(" \t\r\n");
		check(whitespace.folderId() == null, "Java whitespace-only folder IDs normalize to no folder");
		whitespace.setFolderId("\u00A0");
		check("\u00A0".equals(whitespace.folderId()),
			"non-breaking-space folder IDs retain the frozen Java String.isBlank() behavior");
		whitespace.setBlockId("\u00A0");
		check(blockSelectionLabel(whitespace).equals("Block: \u00A0"),
			"non-breaking-space block IDs remain selected in the setting label");
		whitespace.displayName().setValue("\u00A0");
		check(entryGroupName(whitespace).equals("#50002 \u00A0"),
			"non-breaking-space display names retain the frozen Java String.isBlank() behavior");
		check(entry.box().value() && entry.fill().value() && entry.outline().value(),
			"new block ESP entries expose box, fill, and outline independently and enabled");
		entry.fill().setValue(false);
		check(!entry.fill().value() && entry.box().value() && entry.outline().value(),
			"fill can be hidden without disabling the exposed outline shape");
		check(entry.islands().values().stream().noneMatch(setting -> setting.value()),
			"new block ESP entries start with every island filter disabled");
		check(entry.showLabel().value() && !entry.tracer().value() && entry.connectTouching().value()
			&& entry.depthCheck().value(), "new entries preserve the default label, tracer, connection, and depth rules");
		check(entry.outlineColor().argb() == 0xFFFFBE00 && entry.fillColor().argb() == 0x30FFBE00,
			"new entries preserve the default outline and translucent fill colors");
		check(entry.displayName().value().equals("New Block") && entry.matchCount() == 0,
			"new entries retain the default label and empty-scan status");
		check(BlockEspModule.MAX_ENTRIES == 32, "the config-backed entry limit stays at 32");
		check(BlockEspScanner.SECTION_BLOCK_COUNT == 4096,
			"26.2 section scanning keeps the previous 4,096-cell section budget");
		check(!entry.useCustomRange().value() && entry.customRange().intValue() == 32,
			"new entries use full render distance by default and retain a 32-block custom radius");
		check(BlockEspScanner.effectiveRadius(192, false, 32) == 192,
			"default radius reaches the full effective render distance");
		check(BlockEspScanner.effectiveRadius(512, false, 32) == 512,
			"default scan radius is not capped at 64 blocks");
		check(BlockEspScanner.effectiveRadius(192, true, 32) == 32,
			"custom radius narrows a specific entry");
		check(BlockEspScanner.effectiveRadius(16, true, 32) == 16,
			"custom radius never extends past effective render distance");
		check(BlockEspScanner.effectiveRadius(0, false, 32) == 0,
			"zero render distance never scans outside the available range");
		BlockPos origin = new BlockPos(0, 64, 0);
		check(BlockEspScanner.withinRadius(origin, new BlockPos(3, 64, 0), 3),
			"scan sphere includes its radius boundary");
		check(!BlockEspScanner.withinRadius(origin, new BlockPos(2, 66, 0), 2),
			"scan sphere excludes positions outside its radius");
		check(!BlockEspScanner.mayInspectChunk(false), "unloaded chunks are skipped without requesting them");
		check(BlockEspScanner.mayInspectChunk(true), "already-loaded chunks may be inspected");
		check(BlockEspScanner.chunkIntersectsRadius(15, 0, 0, 0, 0)
			&& !BlockEspScanner.chunkIntersectsRadius(15, 0, 1, 0, 0)
			&& BlockEspScanner.chunkIntersectsRadius(15, 15, 1, 1, 2)
			&& !BlockEspScanner.chunkIntersectsRadius(15, 15, 1, 1, 1),
			"chunk invalidation uses exact circle-to-chunk distance, including diagonal boundaries");

		Vec3 camera = new Vec3(13.25, 72.0, -8.5);
		checkTracerRay(camera, new Vector3f(0, 0, -1), new Vec3(24.5, 65.5, 3.5), 0.5,
			"forward-facing camera starts its tracer just ahead of the crosshair");
		checkTracerRay(camera, new Vector3f(1, 0, 0), new Vec3(-2.5, 79.0, 18.0), 0.5,
			"positive yaw direction keeps the tracer start on the camera ray");
		checkTracerRay(camera, new Vector3f(-1, 0, 0), new Vec3(0.5, 42.0, -22.0), 0.5,
			"negative yaw direction keeps the tracer start on the camera ray");
		checkTracerRay(camera, new Vector3f(1, 2, -3), new Vec3(100.5, 10.0, 41.5), 0.5,
			"combined yaw and pitch use a normalized camera-relative start and exact cluster target");
		checkTracerRay(camera, new Vector3f(0.37f, -0.21f, 0.89f), new Vec3(-22.5, 91.0, 44.5), 0.75,
			"non-axis-aligned tracer start preserves the original float-vector normalization");
		BlockEspTracerGeometry.Endpoints noDirection = BlockEspTracerGeometry.endpoints(camera,
			new Vector3f(), new Vec3(15, 70, -4), 0.5);
		check(noDirection.start().equals(Vec3.ZERO)
			&& noDirection.end().equals(new Vec3(15, 70, -4).subtract(camera)),
			"a degenerate direction falls back to a finite camera-to-cluster line");

		List<BlockPos> chain = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 1, 0));
		List<BlockClusterer.Cluster> clusters = BlockClusterer.cluster(chain, true);
		check(clusters.size() == 1 && clusters.getFirst().blocks().size() == 3,
			"face-adjacent selected blocks form one cluster");
		check(clusters.getFirst().center().equals(new Vec3(7.0 / 6.0, 5.0 / 6.0, 0.5)),
			"cluster uses the mean block center for its label and tracer");
		check(BlockClusterer.cluster(chain, false).size() == 3,
			"disabling connected blocks leaves one cluster per exact match");
		checkClusterListImmutability(BlockClusterer.cluster(chain, true),
			"connected clustering returns an immutable result list");
		checkClusterListImmutability(BlockClusterer.cluster(chain, false),
			"disconnected clustering returns an immutable result list");
		check(BlockClusterer.cluster(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0)), true).size() == 2,
			"edge/corner contact does not connect blocks");
		check(BlockClusterer.cluster(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
			new BlockPos(4, 0, 0)), true).size() == 2, "disconnected voxel groups retain separate labels and tracers");
		List<BlockPos> unorderedPositions = List.of(new BlockPos(5, 1, 0), new BlockPos(-3, 0, 0),
			new BlockPos(6, 0, 0), new BlockPos(5, 0, 0), new BlockPos(-4, 0, 0));
		List<BlockPos> reorderedPositions = List.of(new BlockPos(-4, 0, 0), new BlockPos(5, 0, 0),
			new BlockPos(6, 0, 0), new BlockPos(-3, 0, 0), new BlockPos(5, 1, 0));
		Set<Set<BlockPos>> expectedGroups = Set.of(
			Set.of(new BlockPos(-4, 0, 0), new BlockPos(-3, 0, 0)),
			Set.of(new BlockPos(5, 0, 0), new BlockPos(6, 0, 0), new BlockPos(5, 1, 0)));
		Set<Set<BlockPos>> groupsFromFirstInput = new java.util.HashSet<>();
		for (BlockClusterer.Cluster cluster : BlockClusterer.cluster(unorderedPositions, true)) {
			groupsFromFirstInput.add(Set.copyOf(cluster.blocks()));
		}
		Set<Set<BlockPos>> groupsFromReorderedInput = new java.util.HashSet<>();
		for (BlockClusterer.Cluster cluster : BlockClusterer.cluster(reorderedPositions, true)) {
			groupsFromReorderedInput.add(Set.copyOf(cluster.blocks()));
		}
		check(groupsFromFirstInput.equals(expectedGroups) && groupsFromReorderedInput.equals(expectedGroups),
			"face-adjacent grouping is independent of source iteration order while component order remains unspecified");
		check(BlockClusterer.cluster(null, false).isEmpty() && BlockClusterer.cluster(null, true).isEmpty(),
			"a null source produces no clusters in either connection mode");
		List<BlockPos> nullOnly = Arrays.asList(null, null);
		check(BlockClusterer.cluster(nullOnly, false).isEmpty() && BlockClusterer.cluster(nullOnly, true).isEmpty(),
			"null positions are skipped in either connection mode");
		BlockPos repeated = new BlockPos(10, 0, 0);
		BlockPos adjacent = new BlockPos(11, 0, 0);
		List<BlockPos> duplicateInput = Arrays.asList(repeated, null, new BlockPos(10, 0, 0), adjacent, null);
		List<BlockClusterer.Cluster> disconnected = BlockClusterer.cluster(duplicateInput, false);
		check(disconnected.size() == 2 && disconnected.stream().allMatch(cluster -> cluster.blocks().size() == 1)
			&& disconnected.stream().anyMatch(cluster -> cluster.blocks().contains(repeated))
			&& disconnected.stream().anyMatch(cluster -> cluster.blocks().contains(adjacent)),
			"disconnected mode skips nulls and emits one singleton for each unique position");
		List<BlockClusterer.Cluster> connected = BlockClusterer.cluster(duplicateInput, true);
		check(connected.size() == 1 && connected.getFirst().blocks().size() == 2
			&& connected.getFirst().blocks().contains(repeated) && connected.getFirst().blocks().contains(adjacent),
			"connected mode skips nulls and counts each duplicate position once");
		checkClusterCancellation();

		BlockEspScanner.Candidates candidates = new BlockEspScanner.Candidates();
		for (int index = 2_048; index >= 0; index--) {
			candidates.offer(new BlockPos(index, 0, 0));
		}
		check(candidates.positions.size() == 2_049,
			"scanner retains every match beyond the former 1,024-position cap");
		check(candidates.positions.contains(new BlockPos(0, 0, 0))
			&& candidates.positions.contains(new BlockPos(2_048, 0, 0)),
			"uncapped collection keeps matches regardless of scan order");
	}

	private static void checkBlockPickerCatalog() {
		List<String> blockIds = BlockPickerScreen.registeredBlockIds();
		check(blockIds.contains("minecraft:stone"), "block picker includes valid registered block IDs");
		check(blockIds.stream().noneMatch(id -> id.equals("minecraft:air")
			|| id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")),
			"block picker excludes every air block");
		check(blockIds.size() == new java.util.HashSet<>(blockIds).size(),
			"block picker registry catalog contains no duplicate IDs");
		boolean immutable = false;
		try {
			blockIds.add("minecraft:stone");
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(immutable, "block picker exposes an immutable catalog to callers");
	}

	private static void checkScanCachePolicy() {
		BlockEspScanCachePolicy policy = new BlockEspScanCachePolicy();
		check(policy.ticksUntilRefresh() == 0 && !policy.consumeWaitTick(),
			"a cold scan cache is immediately due");
		policy.completedScan();
		check(policy.ticksUntilRefresh() == BlockEspScanCachePolicy.MAX_RESCAN_INTERVAL_TICKS
			&& "rescan-interval".equals(policy.refreshReason()),
			"a completed scan is reused for the bounded refresh interval");
		for (int tick = 1; tick < BlockEspScanCachePolicy.MAX_RESCAN_INTERVAL_TICKS; tick++) {
			check(policy.consumeWaitTick(), "the completed snapshot remains reusable before its maximum age");
		}
		check(policy.ticksUntilRefresh() == 1 && !policy.consumeWaitTick() && policy.ticksUntilRefresh() == 0,
			"the bounded timer expires on the exact configured tick instead of extending by one idle tick");
		policy.completedScan();
		policy.invalidate("chunk-loaded");
		check(policy.ticksUntilRefresh() == 0 && "chunk-loaded".equals(policy.refreshReason())
			&& policy.invalidationCount() == 1,
			"a relevant chunk change immediately invalidates the reusable snapshot and is counted");
		policy.failedScan();
		check(policy.ticksUntilRefresh() == BlockEspScanCachePolicy.MAX_RESCAN_INTERVAL_TICKS
			&& "snapshot-retry".equals(policy.refreshReason()),
			"a worker failure preserves the last complete result and bounds the next retry");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static String blockSelectionLabel(BlockEspEntry entry) {
		var module = BlockEspModule.INSTANCE;
		List<BlockEspEntry> previousEntries = module.entries();
		try {
			module.restore(List.of(entry));
			SettingGroup group = module.groups().stream()
				.filter(candidate -> candidate.toggle() == entry.enabled()).findFirst().orElseThrow();
			return group.settings().stream().filter(ModuleAction.class::isInstance)
				.map(ModuleAction.class::cast).findFirst().orElseThrow().name();
		} finally {
			module.restore(previousEntries);
		}
	}

	private static String entryGroupName(BlockEspEntry entry) {
		var module = BlockEspModule.INSTANCE;
		List<BlockEspEntry> previousEntries = module.entries();
		try {
			module.restore(List.of(entry));
			return module.groups().stream()
				.filter(candidate -> candidate.toggle() == entry.enabled())
				.findFirst().orElseThrow().name();
		} finally {
			module.restore(previousEntries);
		}
	}

	private static void checkClusterListImmutability(List<BlockClusterer.Cluster> clusters, String message) {
		boolean immutable = false;
		try {
			clusters.add(new BlockClusterer.Cluster(List.of(new BlockPos(0, 0, 0)), Vec3.ZERO));
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(immutable, message);
	}

	private static void checkClusterCancellation() {
		boolean wasInterrupted = Thread.interrupted();
		boolean cancelled = false;
		try {
			Thread.currentThread().interrupt();
			try {
				BlockClusterer.cluster(List.of(new BlockPos(0, 0, 0)), true);
			} catch (CancellationException expected) {
				cancelled = true;
			}
		} finally {
			Thread.interrupted();
			if (wasInterrupted) Thread.currentThread().interrupt();
		}
		check(cancelled, "connected clustering stops promptly when its worker is interrupted");
	}

	private static void checkJavaInteropAbi() {
		try {
			var clusterMethod = BlockClusterer.class.getDeclaredMethod("cluster", java.util.Collection.class, boolean.class);
			var blocksMethod = BlockClusterer.Cluster.class.getDeclaredMethod("blocks");
			var centerMethod = BlockClusterer.Cluster.class.getDeclaredMethod("center");
			var constructor = BlockClusterer.Cluster.class.getConstructor(List.class, Vec3.class);
			check(java.lang.reflect.Modifier.isPublic(blocksMethod.getModifiers())
				&& blocksMethod.getReturnType() == List.class
				&& java.lang.reflect.Modifier.isPublic(centerMethod.getModifiers())
				&& centerMethod.getReturnType() == Vec3.class,
				"Kotlin cluster result retains the Java blocks()/center() accessors");
			check(java.lang.reflect.Modifier.isPublic(clusterMethod.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(clusterMethod.getModifiers())
				&& clusterMethod.getReturnType() == List.class,
				"cluster entrypoint remains a public static List-returning method");
			check(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
				"cluster result retains its public List/Vec3 constructor");
			var endpointComponents = BlockEspTracerGeometry.Endpoints.class.getRecordComponents();
			check(BlockEspTracerGeometry.Endpoints.class.isRecord() && endpointComponents != null
				&& endpointComponents.length == 2 && endpointComponents[0].getName().equals("start")
				&& endpointComponents[0].getType() == Vec3.class && endpointComponents[1].getName().equals("end")
				&& endpointComponents[1].getType() == Vec3.class,
				"tracer endpoints preserve their Java record component metadata");

			BlockPos position = new BlockPos(9, 8, 7);
			List<BlockPos> mutableInput = new ArrayList<>(List.of(position));
			Vec3 center = new Vec3(1, 2, 3);
			BlockClusterer.Cluster copied = new BlockClusterer.Cluster(mutableInput, center);
			mutableInput.clear();
			check(copied.blocks().equals(List.of(position)),
				"cluster result snapshots its input block list");
			BlockClusterer.Cluster equivalent = new BlockClusterer.Cluster(List.of(position), center);
			check(copied.equals(equivalent) && copied.hashCode() == equivalent.hashCode()
				&& copied.toString().startsWith("Cluster[blocks=") && copied.toString().endsWith("]"),
				"cluster retains value equality, hashing, and record-style string form");
			check(!copied.equals(new BlockClusterer.Cluster(List.of(position), new Vec3(4, 5, 6))),
				"cluster equality includes its center component");
			check(new BlockClusterer.Cluster(List.of(position), null).center() == null,
				"cluster constructor preserves the Java record's nullable center component");
			boolean immutable = false;
			try {
				copied.blocks().add(new BlockPos(0, 0, 0));
			} catch (UnsupportedOperationException expected) {
				immutable = true;
			}
			check(immutable, "cluster exposes an immutable block list");
			boolean rejectsNullList = false;
			try {
				new BlockClusterer.Cluster(null, center);
			} catch (NullPointerException expected) {
				rejectsNullList = true;
			}
			check(rejectsNullList, "cluster keeps List.copyOf null-list validation");
			boolean rejectsNullPosition = false;
			try {
				new BlockClusterer.Cluster(Arrays.asList(position, null), center);
			} catch (NullPointerException expected) {
				rejectsNullPosition = true;
			}
			check(rejectsNullPosition, "cluster keeps List.copyOf null-element validation");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Block ESP Java ABI reflection failed", exception);
		}
	}

	private static void checkRenderCacheImmutability() {
		BlockEspRenderCache cache = BlockEspRenderCache.Companion.prepare(Set.of(new BlockPos(9, 8, 7)));
		check(!cache.outline().isEmpty(), "connected rendering prepares its outside outline");
		boolean immutable = false;
		try {
			cache.outline().clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(immutable, "connected rendering exposes an immutable outline snapshot");
	}

	private static void checkTracerRay(Vec3 camera, Vector3f direction, Vec3 target,
		double distance, String message) {
		BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(camera, direction, target, distance);
		float lengthSquared = direction.x() * direction.x() + direction.y() * direction.y()
			+ direction.z() * direction.z();
		double length = Math.sqrt(lengthSquared);
		Vec3 expectedStart = new Vec3(direction.x() / length * distance, direction.y() / length * distance,
			direction.z() / length * distance);
		Vec3 expectedEnd = target.subtract(camera);
		check(close(endpoints.start(), expectedStart), message + " (start)");
		check(close(endpoints.end(), expectedEnd), message + " (cluster target)");
		check(close(endpoints.end().add(camera), target), message + " (world reconstruction)");
	}

	private static boolean close(Vec3 first, Vec3 second) {
		return Math.abs(first.x - second.x) < 1.0e-6
			&& Math.abs(first.y - second.y) < 1.0e-6
			&& Math.abs(first.z - second.z) < 1.0e-6;
	}
}
