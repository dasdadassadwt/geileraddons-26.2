package geiler.addons.client.farming;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.impl.GardenPlotBordersModule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Offline geometry and evidence-lifetime checks for Garden plot borders. */
public final class GardenPlotChecks {
	private static final long FRESH_FOR = 120_000L;

	private GardenPlotChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkJavaRecordShapes();
		checkModuleContract();
		checkGridMapping();
		checkGeometry();
		checkEvidenceStates();
		checkVisibleSourcesAndChat();
		checkEvidenceFreshnessAndPartialWidgets();
		checkScoreboardParsing();
		checkPestWidgetParsing();
		checkWidgetClearAndLifetime();
	}

	private static void checkJavaRecordShapes() {
		check(GardenPlotGrid.Plot.class.isRecord(), "Garden plots keep their Java record ABI");
		check(GardenPlotGeometry.Point.class.isRecord() && GardenPlotGeometry.Segment.class.isRecord(),
			"Garden geometry values keep their Java record ABI");
		check(GardenPlotScoreboardParser.PlotCount.class.isRecord(),
			"scoreboard counts keep their Java record ABI");
		check(GardenPlotState.PlotStatus.class.isRecord(),
			"plot status snapshots keep their Java record ABI");
	}

	/** Locks this module's persisted keys, defaults, and settings-panel layout to the legacy feature. */
	private static void checkModuleContract() {
		GardenPlotBordersModule module = GardenPlotBordersModule.INSTANCE;
		check("Infested plot".equals(module.name()) && "Garden Plot Borders".equals(module.configName()),
			"the module keeps its display name and stable persisted config name");
		check("Finds exact infested Garden plots and draws labeled 3D borders.".equals(module.description()),
			"the module keeps its feature description");
		check(module.category() == geiler.addons.client.module.Category.FARMING && !module.defaultEnabled(),
			"the module stays under Farming and remains off on a fresh install");
		check(module.booleanSettings().stream().map(BooleanSetting::name).toList().equals(
				List.of("Show Clear Plots", "Show Unknown Plots", "Show Labels", "Depth Check", "Fill Plots")),
			"all Garden boolean setting keys remain registered in their original order");
		check(module.numberSettings().stream().map(setting -> setting.name()).toList().equals(
				List.of("Border Width", "Border Y", "Wall Height", "Label Scale", "Data Max Age")),
			"all Garden number setting keys remain registered in constructor order");
		check(module.colorSettings().stream().map(setting -> setting.name()).toList().equals(
				List.of("Infested Colour", "Clear Colour", "Unknown Colour", "Fill Colour")),
			"all Garden colour setting keys remain registered in their original order");
		check(module.fillColor() == module.colorSettings().get(3),
			"legacy fill-opacity migration still reaches the registered Fill Colour setting");
		check(module.actions().size() == 1 && "Capture Current Y".equals(module.actions().getFirst().name())
				&& "Store your current block height as the border's fixed world height."
					.equals(module.actions().getFirst().description()),
			"the manual border-height capture action remains available");
		check(module.groups().stream().map(SettingGroup::name).toList().equals(
				List.of("Display", "Border Style", "Labels", "Status Colours", "Fill")),
			"the settings panel retains its original five groups and order");
		check(settingNames(module.groups().get(0).settings()).equals(
				List.of("Show Clear Plots", "Show Unknown Plots", "Show Labels", "Depth Check"))
				&& settingNames(module.groups().get(1).settings()).equals(
					List.of("Border Width", "Wall Height", "Border Y", "Capture Current Y", "Data Max Age"))
				&& settingNames(module.groups().get(2).settings()).equals(List.of("Label Scale"))
				&& settingNames(module.groups().get(3).settings()).equals(
					List.of("Infested Colour", "Clear Colour", "Unknown Colour"))
				&& settingNames(module.groups().get(4).settings()).equals(List.of("Fill Plots", "Fill Colour")),
			"settings and the capture action stay in their original groups");

		List<Boolean> savedBooleans = module.booleanSettings().stream().map(BooleanSetting::rawValue).toList();
		List<Float> savedNumbers = module.numberSettings().stream().map(setting -> setting.value()).toList();
		List<int[]> savedColors = module.colorSettings().stream()
			.map(setting -> new int[] { setting.red(), setting.green(), setting.blue(), setting.alpha() }).toList();
		try {
			module.booleanSettings().forEach(BooleanSetting::reset);
			module.numberSettings().forEach(setting -> setting.reset());
			module.colorSettings().forEach(setting -> setting.reset());
			check(module.booleanSettings().stream().map(BooleanSetting::rawValue).toList()
					.equals(List.of(false, false, true, true, false)),
				"Garden boolean defaults remain unchanged");
			check(numberIs(module, 0, 0.5f, 5.0f, 4.410061f, false)
				&& numberIs(module, 1, 0.0f, 320.0f, 72.0f, true)
				&& numberIs(module, 2, 0.5f, 16.0f, 10.943598f, false)
				&& numberIs(module, 3, 0.55f, 2.0f, 1.4208841f, false)
				&& numberIs(module, 4, 15.0f, 600.0f, 120.0f, true),
				"Garden number ranges, defaults, and integer modes remain unchanged");
			check(colorIs(module.colorSettings().get(0), 255, 94, 80, 255)
				&& colorIs(module.colorSettings().get(1), 65, 218, 167, 205)
				&& colorIs(module.colorSettings().get(2), 249, 183, 75, 205)
				&& colorIs(module.colorSettings().get(3), 255, 94, 80, 51),
				"Garden colour defaults remain unchanged");
		} finally {
			for (int i = 0; i < savedBooleans.size(); i++) {
				module.booleanSettings().get(i).setValue(savedBooleans.get(i));
			}
			for (int i = 0; i < savedNumbers.size(); i++) {
				module.numberSettings().get(i).setValue(savedNumbers.get(i));
			}
			for (int i = 0; i < savedColors.size(); i++) {
				int[] color = savedColors.get(i);
				module.colorSettings().get(i).set(color[0], color[1], color[2], color[3]);
			}
		}
	}

	private static List<String> settingNames(List<Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static boolean numberIs(GardenPlotBordersModule module, int index, float minimum, float maximum,
		float value, boolean integer) {
		var setting = module.numberSettings().get(index);
		return setting.min() == minimum && setting.max() == maximum && setting.value() == value
			&& setting.isInteger() == integer;
	}

	private static boolean colorIs(ColorSetting setting, int red, int green, int blue, int alpha) {
		return setting.red() == red && setting.green() == green && setting.blue() == blue
			&& setting.alpha() == alpha && setting.alphaEditable();
	}

	private static void checkGridMapping() {
		check(GardenPlotGrid.plots().size() == 25, "Garden grid contains exactly 25 plots");
		check(idAt(-192, 64, -192) == 21, "north-west plot center maps to server plot 21");
		check(idAt(0, 64, 0) == 0, "center plot maps to server plot 0");
		check(idAt(192, 64, 192) == 24, "south-east plot center maps to server plot 24");
		check(idAt(-240, 64, -240) == 21, "minimum Garden coordinate belongs to the outer plot");
		check(idAt(240, 64, 240) == 24, "maximum Garden coordinate is included in the final plot");
		check(idAt(-144, 64, 0) == 2, "an exact internal X boundary selects the plot to its east");
		// Increasing Z is south, so the boundary at z = -144 belongs to the row north of it: that is
		// the row holding server id 1, which is why the first entry of IDS_BY_ROW is the north edge.
		check(idAt(0, 64, -144) == 1, "an exact internal Z boundary selects the plot to its north");
		check(GardenPlotGrid.plotAt(240.001, 64, 0).isEmpty(), "coordinates past the grid do not map to plots");
		check(GardenPlotGrid.plotAt(0, 256, 0).isEmpty(), "positions above Garden build height do not map to plots");
		check(GardenPlotGrid.plotAt(Double.NaN, 64, 0).isEmpty(), "non-finite positions are rejected");
		check(GardenPlotGrid.plotAt(0, Double.POSITIVE_INFINITY, 0).isEmpty(),
			"an infinite height is rejected");
		// The two-argument form is the one that maps a pest marker's horizontal position, and it takes
		// no build-height guard with it, so its own finiteness rule has to hold on its own.
		check(GardenPlotGrid.plotAt(Double.NaN, 0).isEmpty(), "a non-finite X is rejected without a Y guard");
		check(GardenPlotGrid.plotAt(0, Double.NaN).isEmpty(), "a non-finite Z is rejected without a Y guard");
		check(GardenPlotGrid.plotAt(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).isEmpty(),
			"infinite horizontal coordinates are rejected");
		check(GardenPlotGrid.plotAt(0, 0).isPresent(), "finite horizontal coordinates still map");
		for (int id = 0; id < 25; id++) {
			GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(id).orElseThrow();
			check(idAt(plot.centerX(), 64, plot.centerZ()) == id, "plot id round-trips through its center: " + id);
		}
	}

	private static void checkGeometry() {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(0).orElseThrow();
		List<GardenPlotGeometry.Segment> solid = GardenPlotGeometry.perimeter(plot, 72.25, 0.5);
		check(solid.size() == 4, "solid plot border has four perimeter edges");
		check(solid.getFirst().from().x() == plot.minX() + 0.5
			&& solid.getFirst().from().z() == plot.minZ() + 0.5,
			"border inset stays just inside the plot boundary");
		check(Math.abs(solid.getFirst().length() - 95.0) < 0.0001,
			"inset border spans the 96-block plot minus both margins");
		check(solid.stream().allMatch(edge -> edge.from().y() == 72.25 && edge.to().y() == 72.25),
			"all border segments stay on the selected render plane");
		// Unknown or expired evidence is dashed, so it cannot read as confirmed clear.
		List<GardenPlotGeometry.Segment> dashed = GardenPlotGeometry.dashedSegments(
			GardenPlotGeometry.perimeter(plot, 72.25, 0.5), 8, 6);
		check(dashed.size() > solid.size(), "unknown-state outline is segmented rather than solid");
		check(dashed.stream().allMatch(dash -> dash.length() <= 8.0001), "unknown outline respects dash length");
		check(GardenPlotGeometry.dashedSegments(GardenPlotGeometry.perimeter(plot, 72, 0), 0, 3).isEmpty(),
			"invalid dash settings fail closed");
		checkDashBudget(plot);
		checkBoxEdges(plot);
	}

	/**
	 * A box is a box only if its walls reach its rings. They did not: the inset was applied twice, so
	 * every vertical stood a full padding inside both rings and all eight corners were open. Asserting
	 * "at least this far inside" never caught it, so these checks compare the corners exactly.
	 */
	private static void checkBoxCorners(GardenPlotGrid.Plot plot) {
		for (double inset : new double[] { 0.0, 0.5, 2.0 }) {
			for (double[] heights : new double[][] { { 70, 73 }, { 73, 70 }, { 70, 70 } }) {
				List<GardenPlotGeometry.Segment> box =
					GardenPlotGeometry.boxEdges(plot, heights[0], heights[1], inset);
				String label = "a box at inset " + inset;
				check(box.size() == 12, label + " still has twelve edges");
				check(box.stream().allMatch(edge -> closeTo(edge.from().x(), plot.minX() + inset)
					|| closeTo(edge.from().x(), plot.maxX() - inset)),
					label + " keeps its X inset exactly once");
				check(box.stream().allMatch(edge -> closeTo(edge.from().z(), plot.minZ() + inset)
					|| closeTo(edge.from().z(), plot.maxZ() - inset)),
					label + " keeps its Z inset exactly once");
				// Every ring corner has to be the foot of a vertical, in all three axes.
				for (GardenPlotGeometry.Segment ring : box) {
					if (!isRing(ring)) continue;
					for (GardenPlotGeometry.Point corner : List.of(ring.from(), ring.to())) {
						check(box.stream().anyMatch(edge -> isVertical(edge)
								&& sameColumn(edge.from(), corner)),
							label + " closes its corner at " + corner.x() + "/" + corner.z());
					}
				}
			}
		}
	}

	/**
	 * Dashes have to stay affordable. A box dashes each of its twelve edges on its own, and the long
	 * rings used to explode into about sixty segments per plot - roughly fifteen hundred line draws
	 * in a frame where every plot is unknown, which is what a fresh session looks like.
	 */
	private static void checkDashBudget(GardenPlotGrid.Plot plot) {
		List<GardenPlotGeometry.Segment> box = GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5);
		List<GardenPlotGeometry.Segment> bounded =
			GardenPlotGeometry.dashedSegments(box, 8, 6, GardenPlotGeometry.NO_DASH_BUDGET);
		check(bounded.size() > box.size(), "unbounded dashes still segment a box");
		// The long rings are what blew up, so the bound that matters is a tight one per edge.
		List<GardenPlotGeometry.Segment> capped = GardenPlotGeometry.dashedSegments(box, 8, 6, 1);
		check(capped.size() <= 12, "a per-edge budget of one caps a whole box at twelve dashes");
		check(capped.size() < bounded.size(), "the budget actually reduces the segment count here");
		check(!capped.isEmpty(), "a bounded box is still dashed rather than dropped");
		check(capped.stream().allMatch(dash -> dash.length() > 0.0), "every surviving dash has length");
		check(GardenPlotGeometry.NO_DASH_BUDGET == 0, "the no-budget sentinel stays at zero");
		// A lenient budget must not touch an edge that is already inside it.
		List<GardenPlotGeometry.Segment> lenient = GardenPlotGeometry.dashedSegments(box, 8, 6, 64);
		check(lenient.size() == bounded.size(), "a budget nothing exceeds changes nothing");
		check(GardenPlotGeometry.dashedSegments(
				GardenPlotGeometry.perimeter(plot, 72, 0.5), 8, 6, 64).size() == 28,
			"an unbudgeted ring keeps dashing at its requested length");

		// The budget is per edge, so one very long edge cannot eat the whole allowance.
		List<GardenPlotGeometry.Segment> oneLong =
			List.of(new GardenPlotGeometry.Segment(solidPoint(0, 70, 0), solidPoint(100, 70, 0)));
		int longDashes = GardenPlotGeometry.dashedSegments(oneLong, 8, 6, 3).size();
		check(longDashes <= 3,
			"a single long edge obeys the budget instead of dashing to its natural count, got "
				+ longDashes);

		check(GardenPlotGeometry.dashedSegments(box, 8, 6, -1).size() == bounded.size(),
			"a non-positive budget means no budget");
	}

	private static boolean isRing(GardenPlotGeometry.Segment edge) {
		return edge.from().y() == edge.to().y();
	}

	private static boolean isVertical(GardenPlotGeometry.Segment edge) {
		return edge.from().y() != edge.to().y();
	}

	private static boolean sameColumn(GardenPlotGeometry.Point a, GardenPlotGeometry.Point b) {
		return closeTo(a.x(), b.x()) && closeTo(a.z(), b.z());
	}

	private static boolean closeTo(double a, double b) {
		return Math.abs(a - b) < 0.000001;
	}

	private static GardenPlotGeometry.Point solidPoint(double x, double y, double z) {
		return new GardenPlotGeometry.Point(x, y, z);
	}

	/**
	 * The border is a fixed landmark now: every edge lives between the two stored heights and none
	 * of them can move when the player does.
	 */
	private static void checkBoxEdges(GardenPlotGrid.Plot plot) {
		List<GardenPlotGeometry.Segment> box = GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5);
		check(box.size() == 12, "a plot box has twelve edges");
		check(box.stream().allMatch(edge -> edge.from().y() >= 70.0 && edge.to().y() >= 70.0
			&& edge.from().y() <= 73.0 && edge.to().y() <= 73.0),
			"every box edge stays between the fixed bottom and top heights");
		check(box.stream().anyMatch(edge -> edge.from().y() == 70.0 && edge.to().y() == 70.0),
			"the box has a bottom ring at the stored world height");
		check(box.stream().anyMatch(edge -> edge.from().y() == 73.0 && edge.to().y() == 73.0),
			"the box has a top ring at the wall height");
		check(box.stream().anyMatch(edge -> edge.from().y() == 70.0 && edge.to().y() == 73.0),
			"the box has vertical walls connecting them");
		check(box.stream().allMatch(edge -> edge.from().x() >= plot.minX() + 0.5
			&& edge.from().x() <= plot.maxX() - 0.5), "the box keeps its inset on the X axis");
		check(box.stream().allMatch(edge -> edge.from().z() >= plot.minZ() + 0.5
			&& edge.from().z() <= plot.maxZ() - 0.5), "the box keeps its inset on the Z axis");

		// Reversed and degenerate heights still produce a drawable box rather than nothing.
		check(GardenPlotGeometry.boxEdges(plot, 73, 70, 0.5).size() == 12,
			"reversed heights are normalised into a box");
		List<GardenPlotGeometry.Segment> flat = GardenPlotGeometry.boxEdges(plot, 70, 70, 0.5);
		check(flat.size() == 12, "a degenerate height still draws a box");
		check(flat.stream().allMatch(edge -> Math.abs(edge.length()) < 96.0001),
			"a degenerate box stays within the plot");
		check(GardenPlotGeometry.boxEdges(null, 70, 73, 0.5).isEmpty(), "a missing plot yields no geometry");
		check(GardenPlotGeometry.boxEdges(plot, Double.NaN, 73, 0.5).isEmpty(),
			"a non-finite height yields no geometry");

		List<GardenPlotGeometry.Segment> dashedBox = GardenPlotGeometry.dashedSegments(
			GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5), 8, 6);
		check(dashedBox.size() > box.size(), "a dashed box is segmented rather than solid");
		check(dashedBox.stream().allMatch(dash -> dash.length() <= 8.0001), "a dashed box respects dash length");
		check(GardenPlotGeometry.dashedSegments(box, 0, 6).isEmpty(), "an invalid dash fails closed");
		checkBoxCorners(plot);
	}

	private static void checkEvidenceStates() {
		GardenPlotState state = new GardenPlotState();
		check(all(state.snapshot(10_000, FRESH_FOR), GardenPlotState.Status.UNKNOWN),
			"plots without evidence start unknown");
		check(!state.observePestsWidgetSnapshot(Set.of(3), false, 9_000),
			"a partial widget snapshot is rejected before any evidence exists");
		check(all(state.snapshot(9_001, FRESH_FOR), GardenPlotState.Status.UNKNOWN),
			"a rejected partial widget snapshot does not invent evidence");
		check(state.observePestsWidgetSnapshot(Set.of(3, 10), 10_000), "valid full widget snapshot is accepted");
		List<GardenPlotState.PlotStatus> snapshot = state.snapshot(10_000 + FRESH_FOR, FRESH_FOR);
		check(status(snapshot, 3).status() == GardenPlotState.Status.INFESTED
			&& status(snapshot, 3).pestCount() == null,
			"widget membership confirms infestation without inventing an exact count");
		check(status(snapshot, 4).status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(status(snapshot, 4).pestCount()),
			"omission from a valid full widget snapshot confirms clear");
		List<GardenPlotState.PlotStatus> stale = state.snapshot(10_001 + FRESH_FOR, FRESH_FOR);
		check(status(stale, 3).status() == GardenPlotState.Status.UNKNOWN && status(stale, 3).stale()
			&& status(stale, 3).pestCount() == null,
			"expired infestation becomes explicitly stale unknown rather than clean");
		check(status(stale, 4).status() == GardenPlotState.Status.UNKNOWN && status(stale, 4).stale(),
			"expired clear evidence also becomes stale unknown");

		state.reset();
		check(state.observePlotMenuCounts(Map.of(7, 0, 8, 4), 20_000), "visible plot counts are accepted");
		snapshot = state.snapshot(20_001, FRESH_FOR);
		check(status(snapshot, 7).status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(status(snapshot, 7).pestCount()), "menu zero count confirms clear");
		check(status(snapshot, 8).status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(4).equals(status(snapshot, 8).pestCount()), "menu count confirms infestation and exact count");
		check(status(snapshot, 9).status() == GardenPlotState.Status.UNKNOWN, "partial menu data leaves absent plots unknown");
		check(!state.observePestsWidgetSnapshot(Set.of(3, 25), 20_100), "invalid full snapshots are rejected atomically");
		check(status(state.snapshot(20_101, FRESH_FOR), 8).status() == GardenPlotState.Status.INFESTED,
			"rejected snapshot leaves prior evidence unchanged");
		check(state.observeGardenPestTotal(2, 20_200), "positive visible Garden total is accepted");
		snapshot = state.snapshot(20_201, FRESH_FOR);
		check(status(snapshot, 7).status() == GardenPlotState.Status.CLEAR,
			"Garden-wide positive total does not guess a plot is infested");
		check(status(snapshot, 8).status() == GardenPlotState.Status.INFESTED,
			"positive total does not erase a specific infested observation");
		check(state.observeGardenPestTotal(0, 20_300), "zero Garden total is accepted");
		check(status(state.snapshot(20_301, FRESH_FOR), 8).status() == GardenPlotState.Status.INFESTED,
			"Garden-wide zero total also leaves plot evidence unchanged");
	}

	private static void checkVisibleSourcesAndChat() {
		GardenPlotState state = new GardenPlotState();
		check(state.observeChatMessage("§aPlot §r§7- §r§b10 §r§ais now clean!", 1_000, FRESH_FOR),
			"formatted explicit plot-clean chat is recognized");
		GardenPlotState.PlotStatus clean = status(state.snapshot(1_001, FRESH_FOR), 10);
		check(clean.status() == GardenPlotState.Status.CLEAR && clean.source() == GardenPlotState.Source.CLEAN_CHAT,
			"clean chat confirms only its named plot");
		check(status(state.snapshot(1_001, FRESH_FOR), 11).status() == GardenPlotState.Status.UNKNOWN,
			"clean chat does not imply other plots are clear");
		check(state.observeChatMessage("§cThere are not any Pests on your Garden right now! Keep farming!", 2_000),
			"explicit no-pests chat is recognized");
		check(all(state.snapshot(2_001, FRESH_FOR), GardenPlotState.Status.CLEAR),
			"no-pests chat confirms all plots clear");
		check(!state.observeChatMessage("Plot - 10 might be clean", 3_000), "ambiguous chat is ignored");
		check(state.observeSpawn(4, 3, 3_100, FRESH_FOR),
			"spawn evidence accepts an exact numeric plot ID");
		check(!state.observeChatMessage("Garden! 2 Pests have spawned in Plot - 4!", 3_101, FRESH_FOR),
			"clean-chat handling does not parse a spawn that the caller will handle separately");
		GardenPlotState.PlotStatus spawned = status(state.snapshot(3_101, FRESH_FOR), 4);
		check(spawned.status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(3).equals(spawned.pestCount())
			&& spawned.source() == GardenPlotState.Source.SPAWN_CHAT,
			"spawn evidence adds to the fresh exact prior count on its named plot");
		check(status(state.snapshot(3_101, FRESH_FOR), 5).status() == GardenPlotState.Status.CLEAR,
			"exact plot spawn does not assign a neighboring plot");
		check(!state.observeSpawn(25, 3, 3_200, FRESH_FOR)
			&& !state.observeSpawn(-1, 1, 3_300, FRESH_FOR),
			"out-of-grid spawn IDs are rejected");
		check(state.observeSpawn(6, 0, 3_400, FRESH_FOR)
			&& state.observeSpawn(8, 0, 3_400, FRESH_FOR),
			"offline spawns can mark each exact numeric plot without a count");
		check(status(state.snapshot(3_401, FRESH_FOR), 6).status() == GardenPlotState.Status.INFESTED
			&& status(state.snapshot(3_401, FRESH_FOR), 6).pestCount() == null
			&& status(state.snapshot(3_401, FRESH_FOR), 8).status() == GardenPlotState.Status.INFESTED,
			"offline spawn evidence marks each named plot without inventing per-plot counts");
		check(state.observeCurrentPlotScoreboardCount(4, 2, 4_000), "current-plot count is accepted");
		List<GardenPlotState.PlotStatus> snapshot = state.snapshot(4_001, FRESH_FOR);
		check(status(snapshot, 4).status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(2).equals(status(snapshot, 4).pestCount()), "current-plot scoreboard supplies exact count");
		state.reset();
		check(all(state.snapshot(5_000, FRESH_FOR), GardenPlotState.Status.UNKNOWN), "session reset clears transient data");
	}

	private static void checkEvidenceFreshnessAndPartialWidgets() {
		GardenPlotState state = new GardenPlotState();
		check(state.observePestsWidgetSnapshot(Set.of(3, 7, 9), true, 10_000),
			"a complete ordered Pests widget snapshot is accepted");
		check(!state.observePestsWidgetSnapshot(Set.of(3), false, 11_000),
			"a partial Pests widget snapshot is rejected");
		check(status(state.snapshot(11_001, FRESH_FOR), 7).status() == GardenPlotState.Status.INFESTED,
			"a partial widget read cannot clear an omitted plot");

		check(state.observeCurrentPlotScoreboardCount(7, 0, 12_000),
			"a newer exact current-plot clear is accepted");
		check(state.observePestsWidgetSnapshot(Set.of(7), true, 11_500),
			"a valid older complete snapshot is safely processed");
		GardenPlotState.PlotStatus scoreboard = status(state.snapshot(12_001, FRESH_FOR), 7);
		check(scoreboard.status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(scoreboard.pestCount())
			&& scoreboard.source() == GardenPlotState.Source.CURRENT_PLOT_SCOREBOARD,
			"an older widget cannot overwrite the current-plot scoreboard observation");
		check(state.refreshCurrentPlotScoreboardCount(7, 0, 12_500),
			"an unchanged current-plot scoreboard row refreshes its own evidence");
		check(state.observeSpawn(7, 1, 13_000, FRESH_FOR),
			"a fresh exact zero count can be incremented by spawn evidence");
		check(!state.refreshCurrentPlotScoreboardCount(7, 0, 14_000),
			"an unchanged old scoreboard row cannot refresh over newer spawn evidence");
		GardenPlotState.PlotStatus afterSpawn = status(state.snapshot(14_001, FRESH_FOR), 7);
		check(afterSpawn.status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(1).equals(afterSpawn.pestCount())
			&& afterSpawn.source() == GardenPlotState.Source.SPAWN_CHAT,
			"scoreboard polling preserves the later spawn count and source");

		check(state.observeCurrentPlotScoreboardCount(9, 2, 19_500),
			"a fresh exact prior count is accepted for the spawn plot");
		check(state.observeSpawn(9, 2, 20_000, FRESH_FOR), "a newer exact spawn observation is accepted");
		check(state.observePestsWidgetSnapshot(Set.of(), true, 12_500),
			"an older full snapshot may update plots without newer evidence");
		GardenPlotState.PlotStatus spawned = status(state.snapshot(20_001, FRESH_FOR), 9);
		check(spawned.status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(4).equals(spawned.pestCount())
			&& spawned.source() == GardenPlotState.Source.SPAWN_CHAT,
			"an older omitted-list clear cannot override newer spawn evidence");

		check(state.observePlotClean(3, 22_000), "a newer exact clean observation is accepted");
		check(state.observePestsWidgetSnapshot(Set.of(3), true, 12_500),
			"another older full snapshot is processed without replacing newer evidence");
		GardenPlotState.PlotStatus cleaned = status(state.snapshot(22_001, FRESH_FOR), 3);
		check(cleaned.status() == GardenPlotState.Status.CLEAR
			&& cleaned.source() == GardenPlotState.Source.CLEAN_CHAT,
			"an older listed infestation cannot override newer clean evidence");

		check(state.observeGardenPestTotal(0, 23_000), "a whole-Garden zero total is accepted");
		check(status(state.snapshot(23_001, FRESH_FOR), 9).status() == GardenPlotState.Status.INFESTED,
			"a Garden-wide total has no per-plot effect");
		state.refreshWidgetObservation(15_000);
		check(status(state.snapshot(23_001, FRESH_FOR), 7).source() == GardenPlotState.Source.SPAWN_CHAT,
			"widget refresh does not replace newer non-widget evidence");

		GardenPlotState counts = new GardenPlotState();
		check(counts.observePlotClean(12, 100), "an exact clean count establishes zero for its plot");
		check(counts.observeSpawn(12, 3, 110, 50), "a fresh spawn increment is accepted");
		check(Integer.valueOf(3).equals(status(counts.snapshot(111, FRESH_FOR), 12).pestCount()),
			"spawn amount is added to a fresh exact zero count");
		check(counts.observeSpawn(13, 2, 110, 50), "a spawn without a prior count is accepted");
		check(status(counts.snapshot(111, FRESH_FOR), 13).pestCount() == null,
			"a spawn without an exact prior total keeps the resulting count unknown");
		check(counts.observeCurrentPlotScoreboardCount(14, 5, 100), "an exact prior count is accepted");
		check(counts.observeSpawn(14, 2, 200, 50), "a spawn after stale count evidence is accepted");
		check(status(counts.snapshot(201, FRESH_FOR), 14).pestCount() == null,
			"a stale prior exact count is not used to invent a spawn total");
	}

	private static void checkScoreboardParsing() {
		String plotWithGlyph = "§aPlot - 17 \uE100 §cx4";
		Optional<GardenPlotScoreboardParser.PlotCount> parsed =
			GardenPlotScoreboardParser.parseCurrentPlotCount(plotWithGlyph);
		check(parsed.equals(Optional.of(new GardenPlotScoreboardParser.PlotCount(17, 4))),
			"colored scoreboard formatting and an invisible glyph do not hide plot 17 count 4");
		GardenPlotState state = new GardenPlotState();
		GardenPlotScoreboardParser.PlotCount count = parsed.orElseThrow();
		check(state.observeCurrentPlotScoreboardCount(count.plotId(), count.pestCount(), 30_000),
			"a parsed current-plot count updates its exact plot");
		GardenPlotState.PlotStatus plot17 = status(state.snapshot(30_001, FRESH_FOR), 17);
		check(plot17.status() == GardenPlotState.Status.INFESTED && Integer.valueOf(4).equals(plot17.pestCount())
			&& status(state.snapshot(30_001, FRESH_FOR), 16).status() == GardenPlotState.Status.UNKNOWN,
			"the current-plot count maps only to plot 17");
		check(GardenPlotScoreboardParser.parseCurrentPlotCount("§aPlot - 17")
			.equals(Optional.of(new GardenPlotScoreboardParser.PlotCount(17, 0))),
			"a plain exact plot row means its pest count is zero");
		check(GardenPlotScoreboardParser.parseCurrentPlotCount("Plot - 25 x4").isEmpty(),
			"out-of-grid scoreboard plot IDs are rejected");
		check(GardenPlotScoreboardParser.parseCurrentPlotCount("Plot - North x4").isEmpty(),
			"named scoreboard plots are not mapped to an ID");
		OptionalInt gardenTotal = GardenPlotScoreboardParser.parseGardenTotal("§aThe Garden §ex12");
		check(gardenTotal.equals(OptionalInt.of(12)),
			"the Garden aggregate count can be parsed");
		check(GardenPlotScoreboardParser.parseCurrentPlotCount("§aThe Garden §ex12").isEmpty(),
			"the Garden aggregate is never attributed to a specific plot");
		state.observeGardenPestTotal(gardenTotal.orElseThrow(), 30_002);
		check(status(state.snapshot(30_003, FRESH_FOR), 16).status() == GardenPlotState.Status.UNKNOWN,
			"a parsed Garden aggregate does not create per-plot evidence");
	}

	/**
	 * The widget reader is the one whole-Garden source, so its failures matter as much as its
	 * successes: every rejection below is a case where believing a partial tab list would have
	 * certified a plot as clean that had simply not rendered yet.
	 */
	private static void checkPestWidgetParsing() {
		// A realistic tab list, with the Pests widget between two other widgets.
		List<String> tabList = List.of(
			"§7Players (4)",
			"§7Info",
			"§7Profile: §aApple",
			"§7Skills: §e30.5",
			"§7Bank: §e1,000,000",
			"§7The Garden",
			"§7Pests:",
			"§e5 §7pests",
			"§7Plots: §a4, 12, 13, 18, 20",
			"§7Visitors (§e3§7)",
			"§7Crop Milestones: §e12");
		check(read(tabList).equals(Optional.of(Set.of(4, 12, 13, 18, 20))),
			"the widget names every infested plot it lists");
		check(read(List.of("§7Pests:", "§e1 §7pest", "§7Plots: §a9"))
				.equals(Optional.of(Set.of(9))),
			"a single-plot widget is read, and a singular count row does not end the block");
		check(read(List.of("§7Pests:", "§7Plots: §aNone")).equals(Optional.of(Set.of())),
			"an explicit none is a decisive empty list, not a failed parse");
		check(read(List.of("§7Pests:", "§7Plots: §7None")).equals(Optional.of(Set.of())),
			"a colour-coded none is still read as none");
		check(read(List.of("§7Pests: §a4", "§7Plots: §a3")).equals(Optional.of(Set.of(3))),
			"a count on the header line does not hide the plot list");
		check(read(List.of("§7Pests:", "§7Plots: §a12, 4, 12, 3"))
				.orElseThrow().stream().toList().equals(List.of(12, 4, 3)),
			"plot IDs keep first-seen order while duplicates collapse");
		check(read(List.of("§7Pests:", "§7Plots: §an/a")).equals(Optional.of(Set.of())),
			"the explicit n/a spelling also reports no infested plots");
		check(read(List.of("§7Players (80)", "§7Plots: §r§b4§r§f, §r§b12§r§f, §r§b24"))
				.equals(Optional.of(Set.of(4, 12, 24))),
			"the unique authoritative Plots row works when Hypixel omits the Pests heading");

		check(read(List.of("§7Bank: §e200", "§7Skills: §e12")).isEmpty(),
			"a tab list without the widget is unknown rather than clean");
		check(read(List.of()).isEmpty(), "an empty tab list yields nothing");
		check(read(List.of("§7Pests:")).isEmpty(),
			"a header with no data rows is a partial read and is rejected");
		check(read(List.of("§7Pests:", "§7Visitors (§e1§7)")).isEmpty(),
			"a header followed by another widget is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a3", "§dQueue: §bSkyBlock")).isEmpty(),
			"a row the reader does not understand inside the block rejects the whole read");
		check(read(List.of("§7Pests:", "§7Plots: §a3, 40")).isEmpty(),
			"an out-of-grid plot id is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a3, banana")).isEmpty(),
			"a non-numeric plot id is rejected");
		check(read(List.of("§7Plots: §a3, banana")).isEmpty(),
			"a malformed standalone Plots row is rejected rather than clearing the grid");
		check(read(List.of("§7Pests:", "§7Plots: §a")).isEmpty(),
			"an empty plot list without an explicit none is rejected");
		check(read(List.of("§7Plots: §a3", "§7Plots: §a4")).isEmpty(),
			"multiple standalone Plots rows are ambiguous and rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a4", "§7Pests:", "§7Plots: §a5")).isEmpty(),
			"two widget headers cannot both be the widget");
	}

	/** The clear path BUG-005 was about: a plot stops reading as infested once it leaves the widget. */
	private static void checkWidgetClearAndLifetime() {
		GardenPlotState state = new GardenPlotState();
		check(state.observePestsWidgetSnapshot(Set.of(3, 10), 10_000), "full widget snapshot is accepted");
		check(status(state.snapshot(10_001, FRESH_FOR), 10).status() == GardenPlotState.Status.INFESTED,
			"a listed plot is infested");
		check(state.observePestsWidgetSnapshot(Set.of(3), 11_000), "the next widget snapshot is accepted");
		GardenPlotState.PlotStatus cleared = status(state.snapshot(11_001, FRESH_FOR), 10);
		check(cleared.status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(cleared.pestCount())
			&& cleared.source() == GardenPlotState.Source.PESTS_WIDGET,
			"a plot that leaves the infested list is cleared by omission");

		// A steady garden republishes nothing, so the reader re-stamps what the widget still says.
		long refreshedAt = 20_000L;
		state.refreshWidgetObservation(refreshedAt);
		GardenPlotState.PlotStatus stayed = status(state.snapshot(refreshedAt, FRESH_FOR), 3);
		check(stayed.status() == GardenPlotState.Status.INFESTED && !stayed.stale()
			&& stayed.source() == GardenPlotState.Source.PESTS_WIDGET,
			"refreshing keeps widget evidence fresh without changing it");
		GardenPlotState.PlotStatus refreshedCleared = status(state.snapshot(refreshedAt, FRESH_FOR), 10);
		check(refreshedCleared.status() == GardenPlotState.Status.CLEAR && !refreshedCleared.stale(),
			"a refreshed clear stays clear");
		// Well past the age the first observation would have expired at, so only the re-stamp can
		// be keeping the widget's knowledge alive.
		long later = refreshedAt + FRESH_FOR / 2;
		check(status(state.snapshot(later, FRESH_FOR), 3).status() == GardenPlotState.Status.INFESTED
			&& !status(state.snapshot(later, FRESH_FOR), 3).stale(),
			"a refreshed plot is still infested when its original observation would have expired");

		GardenPlotState monotonicRefresh = new GardenPlotState();
		check(monotonicRefresh.observePestsWidgetSnapshot(Set.of(3), 10_000),
			"widget evidence is available for refresh ordering");
		monotonicRefresh.refreshWidgetObservation(20_000);
		monotonicRefresh.refreshWidgetObservation(15_000);
		check(status(monotonicRefresh.snapshot(20_001, FRESH_FOR), 3).observedAtMillis() == 20_000,
			"an older widget refresh does not move its evidence timestamp backwards");

	}

	/** Drives the parser through the reader's own seam rather than calling the parser directly. */
	private static Optional<Set<Integer>> read(List<String> tabList) {
		return new PestWidgetReader(() -> tabList).read();
	}

	private static int idAt(double x, double y, double z) {
		return GardenPlotGrid.plotAt(x, y, z).orElseThrow().id();
	}

	private static GardenPlotState.PlotStatus status(List<GardenPlotState.PlotStatus> snapshot, int id) {
		return snapshot.stream().filter(plot -> plot.plotId() == id).findFirst().orElseThrow();
	}

	private static boolean all(List<GardenPlotState.PlotStatus> snapshot, GardenPlotState.Status expected) {
		return snapshot.size() == 25 && snapshot.stream().allMatch(plot -> plot.status() == expected);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
