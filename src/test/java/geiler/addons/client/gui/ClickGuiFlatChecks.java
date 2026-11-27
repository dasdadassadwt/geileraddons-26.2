package geiler.addons.client.gui;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.VisualModule;

/** The flat Click GUI: fixed GUI-scale-2 layout, header alignment, card controls, liquid switch and the Onyx theme. */
public final class ClickGuiFlatChecks {
	private ClickGuiFlatChecks() { }

	public static void run() {
		checkDesignScale();
		checkPanelOrigin();
		checkHeader();
		checkCardControls();
		checkLiquidSwitch();
		checkLabelsAndPresets();
	}

	private static void checkDesignScale() {
		float pose = ClickGuiDesignScale.poseScale(1917, 1022, 3.0, true);
		check(Math.abs(pose - 2.0f / 3.0f) < 1e-4f, "GUI scale 3 draws the menu at two thirds so it looks like scale 2");
		check(ClickGuiDesignScale.virtualSize(639, pose) == 958 && ClickGuiDesignScale.virtualSize(341, pose) == 511,
			"a 1917x1022 window lays the menu out on a 958x511 design screen at every GUI scale");
		check(ClickGuiDesignScale.poseScale(1917, 1022, 2.0, true) == 1.0f
			&& ClickGuiDesignScale.poseScale(1917, 1022, 1.0, true) == 2.0f,
			"GUI scale 2 needs no extra scale and GUI scale 1 is doubled");
		check(ClickGuiDesignScale.poseScale(1000, 600, 2.0, true) == 0.5f,
			"a window below 1280x720 falls back to a scale-1 design so the menu still fits");
		check(ClickGuiDesignScale.poseScale(1917, 1022, 3.0, false) == 1.0f,
			"with the lock off the menu follows the Minecraft GUI scale");
	}

	private static void checkPanelOrigin() {
		// framebuffer width, framebuffer height, Minecraft GUI scale
		for (int[] size : new int[][] {{2560, 1369, 2}, {1920, 1080, 3}, {1920, 1080, 4}, {3840, 2160, 2}, {800, 450, 2}}) {
			float pose = ClickGuiDesignScale.poseScale(size[0], size[1], size[2], true);
			for (int side = 0; side < 2; side++) {
				int screen = ClickGuiDesignScale.virtualSize(size[side] / size[2], pose);
				int panel = side == 0 ? ClickGuiHeaderGeometry.panelWidthForScreen(screen)
					: ClickGuiHeaderGeometry.panelHeightForScreen(screen);
				int origin = ClickGuiHeaderGeometry.panelOriginForScreen(screen, panel);
				check(origin >= 0 && origin + panel <= screen && Math.abs(origin - (screen - origin - panel)) <= 1,
					"the panel is centred on the design screen: " + size[0] + "x" + size[1] + " at GUI scale " + size[2]);
			}
		}
	}

	private static void checkHeader() {
		int panelW = ClickGuiHeaderGeometry.panelWidthForScreen(958);
		int panelH = ClickGuiHeaderGeometry.panelHeightForScreen(511);
		check(panelW == 924 && panelH == 462, "the panel covers 96.5% x 90.5% of the design screen: " + panelW + "x" + panelH);
		check(ClickGuiHeaderGeometry.baseHeaderHeightForPanelHeight(panelH) == 40, "the top bar is 40 px tall");
		int px = 17;
		int py = 24;
		int categoryWidth = 100;
		ClickGuiScreen.Rect search = ClickGuiHeaderGeometry.searchRectForLayout(px, py, panelW, panelH, categoryWidth);
		ClickGuiScreen.Rect first = ClickGuiHeaderGeometry.topBarRectForLayout(px, py, panelW, panelH, ClickGuiScreen.TopBarIcon.WORKSPACE);
		ClickGuiScreen.Rect last = ClickGuiHeaderGeometry.topBarRectForLayout(px, py, panelW, panelH, ClickGuiScreen.TopBarIcon.MOVE_ELEMENTS);
		int gridLeft = px + categoryWidth + ClickGuiModuleGrid.GRID_INSET;
		int gridContentRight = px + panelW - ClickGuiModuleGrid.GRID_INSET - ClickGuiScreen.scrollbarGutter();
		check(search.x() == gridLeft, "the search bar starts at the card grid's left edge");
		check(search.x() + search.w() == first.x() - 8, "the search bar ends 8 px before the toolbar");
		check(last.x() + last.w() == gridContentRight, "the toolbar ends at the card grid's right edge");
		check(ClickGuiScreen.TopBarIcon.values().length == 7 && first.w() == 30 && first.y() == py + 5,
			"seven 30 px toolbar buttons sit centred in the top bar");
		check(ClickGuiScreen.TopBarIcon.HUNTING.ordinal() == ClickGuiScreen.TopBarIcon.COSMETICS.ordinal() + 1,
			"the Hunting Methods button sits right of the Cosmetics button");
		check(ClickGuiScreen.TopBarIcon.COSMETICS.ordinal() == ClickGuiScreen.TopBarIcon.THEME.ordinal() + 1,
			"the Cosmetics button sits right of the Theme button");
		check(search.h() == 21 && search.y() == py + 9, "the search bar is 21 px tall and centred");
		ClickGuiScreen.Rect github = ClickGuiHeaderGeometry.topBarRectForLayout(px, py, panelW, panelH, ClickGuiScreen.TopBarIcon.GITHUB);
		check(github.x() == first.x() + 38, "toolbar buttons are 8 px apart");
		check(ClickGuiHeaderGeometry.versionTextX(px, categoryWidth) == px + 11,
			"the version text starts where the category names start");
	}

	private static void checkCardControls() {
		ClickGuiScreen.Rect card = new ClickGuiScreen.Rect(100, 200, 253, 52);
		ClickGuiScreen.Rect toggle = ClickGuiScreen.cardSwitchRect(card);
		check(toggle.w() == 20 && toggle.h() == 12, "the card switch is a 20x12 pill");
		check(toggle.x() + toggle.w() == card.x() + card.w() - 9 && toggle.y() == card.y() + 15,
			"the switch sits on the card's right side");
		ClickGuiScreen.Rect heart = ClickGuiScreen.moduleFavoriteHitRect(card, true, toggle.y());
		check(heart.y() >= card.y() && heart.y() + heart.h() <= toggle.y(), "the heart sits above the switch");
		ClickGuiScreen.Rect key = ClickGuiScreen.keySquareRect(card, true, 3);
		check(key.h() == 16 && key.w() == 16, "a one-letter key gets a 16 px square");
		check(key.y() == toggle.y() + toggle.h() + 2 && key.y() + key.h() <= card.y() + card.h(),
			"the key square sits under the switch inside the card");
		check(Math.abs((key.x() + key.w() / 2) - (toggle.x() + toggle.w() / 2)) <= 1, "the key square is centred under the switch");
		ClickGuiScreen.Rect wide = ClickGuiScreen.keySquareRect(card, true, 20);
		check(wide.w() == 30, "a longer key name widens the box to fit");
		check(ClickGuiScreen.cardTextRight(card, toggle, heart) < heart.x(), "card text stops before the heart");
	}

	private static void checkLiquidSwitch() {
		ClickGuiMotion expressive = ClickGuiMotion.EXPRESSIVE;
		check(expressive.liquidLead(0L) == 0.0f && expressive.liquidTrail(0L) == 0.0f, "the switch starts at rest");
		check(expressive.liquidLead(520L) == 1.0f && expressive.liquidTrail(520L) == 1.0f, "both knob edges arrive by 520 ms");
		float peak = 0.0f;
		for (long ms = 0; ms <= 520; ms += 5) {
			float lead = expressive.liquidLead(ms);
			float trail = expressive.liquidTrail(ms);
			check(lead + 1e-4f >= trail, "the leading edge never falls behind the trailing edge at " + ms + " ms");
			peak = Math.max(peak, lead);
		}
		check(peak > 1.0f && peak <= 1.1f, "the leading edge overshoots a little, like a spring: " + peak);
		check(expressive.liquidLead(170L) > expressive.liquidTrail(170L) + 0.2f, "mid-way the knob is stretched");
		check(ClickGuiMotion.NONE.liquidLead(0L) == 1.0f && ClickGuiMotion.NONE.liquidTrail(0L) == 1.0f,
			"with motion off the switch jumps");
		check(ClickGuiMotion.REDUCED.liquidLead(150L) == 1.0f
			&& ClickGuiMotion.REDUCED.liquidLead(75L) == ClickGuiMotion.REDUCED.liquidTrail(75L),
			"reduced motion slides the knob without stretching it");
	}

	private static void checkLabelsAndPresets() {
		check("Misc".equals(ClickGuiScreen.categoryLabel(Category.MISCELLANEOUS))
			&& "Farming".equals(ClickGuiScreen.categoryLabel(Category.FARMING)),
			"Miscellaneous is shown as Misc and other names stay");
		VisualModule.Preset onyx = VisualModule.Preset.ONYX;
		check(onyx.background() == 0xF8080808 && onyx.border() == 0x00A8C7FF && onyx.accent() == 0xFF3B82F6
			&& onyx.text() == 0xFFEAF2FF && onyx.iconColor() == 0xFFEAF2FF && onyx.muted() == 0xFF7E8FA8,
			"Onyx keeps the mockup colours");
		for (VisualModule.Preset preset : VisualModule.Preset.ALL) {
			check((preset.background() >>> 24) >= 200, "every preset has a near-solid background for the flat menu");
			check((preset.border() >>> 24) <= 0x26, "every preset keeps its lines subtle");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
