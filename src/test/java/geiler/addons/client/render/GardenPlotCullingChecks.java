package geiler.addons.client.render;

import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import geiler.addons.client.farming.TabWidgetText;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline checks for the Garden border renderer's culling.
 *
 * <p>A full grid is 25 boxes, each twelve segments before any dashing, projected and drawn every
 * frame. These checks pin the cut-offs that keep that bounded, and pin the one thing a cut-off can
 * get wrong: culling a plot the player is standing beside, because a 96-block plot is far from its
 * own centre.
 */
public final class GardenPlotCullingChecks {
	private GardenPlotCullingChecks() { }

	public static void run() {
		checkSummaryRecordShapes();
		checkRange();
		checkLabels();
		checkDegenerateInput();
	}

	private static void checkSummaryRecordShapes() {
		check(GardenPlotBorderRenderer.WorldRenderSummary.class.isRecord(),
			"world render summaries keep their Java record ABI");
		check(GardenPlotBorderRenderer.LabelRenderSummary.class.isRecord(),
			"label render summaries keep their Java record ABI");
	}

	private static void checkRange() {
		// The camera sits in the middle of plot 0, which spans -48..48 on both axes.
		Vec3 centre = new Vec3(0, 70, 0);
		List<GardenPlotState.PlotStatus> all = allPlots();

		List<GardenPlotState.PlotStatus> own = GardenPlotBorderRenderer.withinRange(all, centre, 10.0);
		check(own.size() == 1 && own.getFirst().plotId() == 0,
			"the plot the camera stands in is never culled, however small the range");

		// Plot 1 is the -144..-48 column of the same row: its nearest edge is 48 blocks away, so a
		// range of 47 must drop it and a range of 49 must keep it.
		check(!GardenPlotBorderRenderer.withinRange(all, centre, 47.0).stream()
				.anyMatch(status -> status.plotId() == 1),
			"a plot beyond the range is dropped");
		check(GardenPlotBorderRenderer.withinRange(all, centre, 49.0).stream()
				.anyMatch(status -> status.plotId() == 1),
			"a plot whose edge is inside the range is kept");

		// Distance is measured to the plot's edge, so a plot stays visible while the camera is beside
		// it even though its centre is most of a plot away. At x = 40 the camera is inside plot 0 and
		// eight blocks from plot 3's nearest edge, while plot 1's edge is 88 blocks off.
		Vec3 nearEdge = new Vec3(40, 70, 0);
		List<GardenPlotState.PlotStatus> beside = GardenPlotBorderRenderer.withinRange(all, nearEdge, 20.0);
		check(beside.stream().anyMatch(status -> status.plotId() == 0),
			"the plot the camera is standing in survives a range smaller than its own size");
		check(beside.stream().anyMatch(status -> status.plotId() == 3),
			"a plot whose nearest edge is inside the range is kept even though its centre is 104 blocks away");
		check(beside.stream().noneMatch(status -> status.plotId() == 1),
			"a plot whose edge is out of range is still dropped");
		// At x = 200 and z = 100, plot 0's nearest edge is about 161 blocks away, while plot 3's is
		// about 76 blocks away. Measuring to the edge keeps the plot the player is beside on screen.
		Vec3 outside = new Vec3(200, 70, 100);
		List<GardenPlotState.PlotStatus> fromOutside = GardenPlotBorderRenderer.withinRange(all, outside, 150.0);
		check(fromOutside.stream().noneMatch(status -> status.plotId() == 0),
			"a plot whose edge is beyond the range is dropped even with the camera outside the grid");
		check(fromOutside.stream().anyMatch(status -> status.plotId() == 3),
			"the plot the camera is beside survives although its centre is 188 blocks away");
		check(!GardenPlotBorderRenderer.withinRange(all, outside, 30.0).isEmpty(),
			"a plot with an edge inside a small range is kept from outside the grid");

		// From the centre of plot 21, the opposite corner plot is beyond both the old 384-block
		// limit and a 16-chunk radius. The wider range applies to infested plots only.
		Vec3 nearCorner = new Vec3(-192, 70, -192);
		GardenPlotState.PlotStatus farCorner = all.stream().filter(status -> status.plotId() == 24)
			.findFirst().orElseThrow();
		double farCornerDistance = edgeDistance(farCorner, nearCorner);
		check(farCornerDistance > 384.0, "the opposite-corner plot is beyond the old 384-block cull");
		check(farCornerDistance > 16 * 16, "the opposite-corner plot is beyond a 16-chunk radius");
		check(GardenPlotBorderRenderer.plotFarthestCornerDistanceSquared(24, nearCorner, 72.0, 82.0)
				> 128.0 * 128.0,
			"the far-corner box triggers the extended projection beyond a low render-distance far plane");
		check(GardenPlotBorderRenderer.plotFarthestCornerDistanceSquared(21, nearCorner, 72.0, 82.0)
				< 128.0 * 128.0,
			"a nearby plot stays on the normal projection");
		check(GardenPlotBorderRenderer.worldCandidates(all, nearCorner).stream()
				.noneMatch(status -> status.plotId() == 24),
			"clear and unknown plots retain the bounded local range");
		List<GardenPlotState.PlotStatus> infested = allInfestedPlots();
		double gardenDiagonal = GardenPlotGrid.PLOT_SIZE * GardenPlotGrid.GRID_SIZE * Math.sqrt(2.0);
		check(GardenPlotBorderRenderer.MAX_INFESTED_PLOT_DISTANCE >= gardenDiagonal + 24.0
				&& GardenPlotBorderRenderer.MAX_INFESTED_PLOT_DISTANCE <= 704.0,
			"the infested range covers the Garden diagonal with a finite margin");
		List<GardenPlotState.PlotStatus> gardenWide = GardenPlotBorderRenderer.worldCandidates(infested, nearCorner);
		check(gardenWide.size() == 25 && gardenWide.stream().anyMatch(status -> status.plotId() == 24),
			"the infested world range keeps every plot, including the far corner");
		GardenPlotState widgetState = new GardenPlotState();
		Set<Integer> widgetPlotIds = TabWidgetText.parse(List.of("Players (80)", "Plots: 24")).orElseThrow();
		check(widgetState.observePestsWidgetSnapshot(widgetPlotIds, 1_000L),
			"a standalone authoritative tab row supplies a complete Garden snapshot");
		List<GardenPlotState.PlotStatus> widgetSnapshot = widgetState.snapshot(1_001L, 120_000L);
		check(GardenPlotBorderRenderer.worldCandidates(widgetSnapshot, nearCorner).stream()
				.anyMatch(status -> status.plotId() == 24),
			"the distant plot named by the tab row survives world-border culling");
		check(GardenPlotBorderRenderer.labelCandidates(widgetSnapshot, nearCorner).stream()
				.anyMatch(status -> status.plotId() == 24),
			"the distant plot named by the tab row remains eligible for its label");

		List<GardenPlotState.PlotStatus> ordered = GardenPlotBorderRenderer.withinRange(all, centre, 4000.0);
		check(ordered.size() == 25, "the whole grid stays visible from the middle");
		check(ordered.equals(all), "culling preserves the snapshot's own order");
	}

	private static void checkLabels() {
		Vec3 nearCorner = new Vec3(-192, 70, -192);
		List<GardenPlotState.PlotStatus> labelInput = new ArrayList<>(25);
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			Integer pestCount = plot.id() == 0 ? Integer.valueOf(1) : null;
			labelInput.add(new GardenPlotState.PlotStatus(plot.id(), GardenPlotState.Status.INFESTED, pestCount,
				1_000L, false, GardenPlotState.Source.NONE));
		}
		List<GardenPlotState.PlotStatus> labelled = GardenPlotBorderRenderer.labelCandidates(labelInput, nearCorner);
		check(labelled.size() == 25, "every confirmed infested plot gets a label candidate, got " + labelled.size());
		check(labelled.equals(labelInput), "all infested plots, including missing counts, keep snapshot order");
		check(GardenPlotBorderRenderer.labelCandidates(allPlots(), nearCorner).isEmpty(),
			"unknown plots are not labelled");
		check(labelled.stream().anyMatch(status -> status.plotId() == 24),
			"the opposite-corner plot receives a label candidate");
		GardenPlotState.PlotStatus farCorner = labelled.stream().filter(status -> status.plotId() == 24)
			.findFirst().orElseThrow();
		check(edgeDistance(farCorner, nearCorner) > 384.0,
			"the opposite-corner label is not limited by the former 384-block cull");

		GardenPlotState.PlotStatus onePest = labelled.stream().filter(status -> status.plotId() == 0)
			.findFirst().orElseThrow();
		check("Plot 0: 1 pest".equals(GardenPlotBorderRenderer.labelText(onePest)),
			"labels include the plot ID and exact singular pest count");
		check("Plot 24".equals(GardenPlotBorderRenderer.labelText(farCorner)),
			"labels with unavailable counts still include the plot ID");
		GardenPlotState.PlotStatus twoPests = new GardenPlotState.PlotStatus(24,
			GardenPlotState.Status.INFESTED, 2, 1_000L, false, GardenPlotState.Source.NONE);
		check("Plot 24: 2 pests".equals(GardenPlotBorderRenderer.labelText(twoPests)),
			"labels include the exact plural pest count when available");
		check(GardenPlotBorderRenderer.labelCandidates(labelInput, new Vec3(2_000, 70, 2_000)).isEmpty(),
			"labels remain bounded beyond Garden distance");

		// The output contains one entry for each unique confirmed plot ID.
		Set<Integer> chosen = new HashSet<>();
		for (GardenPlotState.PlotStatus status : labelled) chosen.add(status.plotId());
		check(chosen.size() == GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE,
			"labels cover each unique Garden plot ID");
	}

	private static void checkDegenerateInput() {
		Vec3 centre = new Vec3(0, 70, 0);
		check(GardenPlotBorderRenderer.withinRange(null, centre, 100.0).isEmpty(),
			"a missing snapshot yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(List.of(), centre, 100.0).isEmpty(),
			"an empty snapshot yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), null, 100.0).isEmpty(),
			"a missing camera position yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), centre, -1.0).isEmpty(),
			"a negative range yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), centre, Double.NaN).isEmpty(),
			"a non-finite range yields nothing to draw");
		check(GardenPlotBorderRenderer.labelCandidates(allPlots(), null).isEmpty(),
			"labels need a camera position too");
	}

	private static double edgeDistance(GardenPlotState.PlotStatus status, Vec3 cameraPosition) {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(status.plotId()).orElseThrow();
		double dx = Math.max(0.0, Math.max(plot.minX() - cameraPosition.x, cameraPosition.x - plot.maxX()));
		double dz = Math.max(0.0, Math.max(plot.minZ() - cameraPosition.z, cameraPosition.z - plot.maxZ()));
		return Math.hypot(dx, dz);
	}

	private static List<GardenPlotState.PlotStatus> allPlots() {
		List<GardenPlotState.PlotStatus> statuses = new ArrayList<>(25);
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			statuses.add(new GardenPlotState.PlotStatus(plot.id(), GardenPlotState.Status.UNKNOWN, null,
				1_000L, false, GardenPlotState.Source.NONE));
		}
		return List.copyOf(statuses);
	}

	private static List<GardenPlotState.PlotStatus> allInfestedPlots() {
		List<GardenPlotState.PlotStatus> statuses = new ArrayList<>(25);
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			statuses.add(new GardenPlotState.PlotStatus(plot.id(), GardenPlotState.Status.INFESTED, null,
				1_000L, false, GardenPlotState.Source.NONE));
		}
		return List.copyOf(statuses);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
