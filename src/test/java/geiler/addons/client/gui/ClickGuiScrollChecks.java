package geiler.addons.client.gui;

import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleCategoryFilter;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.impl.CroesusModule;
import geiler.addons.client.module.impl.DungeonTerminalStatsModule;
import geiler.addons.client.module.impl.DungeonTerminalTitlesModule;
import geiler.addons.client.module.impl.GeneralModule;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline checks for the Click GUI's scroll bounds.
 *
 * <p>Both defects these cover were a wheel that could move past the end of a list: the category
 * column had no scroll at all, so the rows it could not fit were drawn outside the panel yet still
 * accepted a click, and the block picker clamped its wheel against the row count while its renderer
 * clamped against the visible row count, so it overshot and snapped back a frame later.
 */
public final class ClickGuiScrollChecks {

	private ClickGuiScrollChecks() { }

	public static void run() {
		checkResponsiveClickGuiSizing();
		checkHeaderGeometry();
		checkVisibleGridRows();
		checkModuleGridSectionsAndGeometry();
		checkModuleSearchBehavior();
		checkTextCursorCodePoints();
		checkModuleIconCatalog();
		checkScrollbarGutter();
		checkCategoryScroll();
		checkCategoryHierarchy();
		checkSearchInset();
		checkBlockPickerScroll();
		checkChangelogScrollPrecedence();
		checkSettingsRowLayout();
		checkSettingsSearch();
	}

	private static void checkModuleIconCatalog() {
		Map<String, String> expectedIcons = Map.ofEntries(
			Map.entry("Debug", "geileraddons:textures/module_icons/debug.png"),
			Map.entry("General", "geileraddons:textures/gui/octicon-gear-16.png"),
			Map.entry("Slot IDs", "geileraddons:textures/module_icons/slot_ids.png"),
			Map.entry("i4 helper", "geileraddons:textures/module_icons/i4_helper.png"),
			Map.entry("Experimentation Solver", "geileraddons:textures/module_icons/experiment_solver.png"),
			Map.entry("Auto Experiments", "geileraddons:textures/module_icons/auto_experiments.png"),
			Map.entry("Auto Kick", "geileraddons:textures/module_icons/auto_kick.png"),
			Map.entry("Party Finder Stats", "geileraddons:textures/module_icons/party_finder_stats.png"),
			Map.entry("Dungeon Guide", "geileraddons:textures/module_icons/dungeon_guide.png"),
			Map.entry("Box Doors", "geileraddons:textures/module_icons/box_doors.png"),
			Map.entry("Tiki Helper", "geileraddons:textures/module_icons/tiki_helper.png"),
			Map.entry("Safari Floor Drops", "geileraddons:textures/module_icons/safari_floor_drops.png"),
			Map.entry("Hideyho Finder", "geileraddons:textures/module_icons/hideyho_finder.png"),
			Map.entry("Sparkling Critter", "geileraddons:textures/module_icons/sparkling_critter.png"),
			Map.entry("Pest Highlighter", "geileraddons:textures/module_icons/pest_highlighter.png"),
			Map.entry("Infested plot", "geileraddons:textures/module_icons/infested_plot.png"),
			Map.entry("Tree Tracker", "geileraddons:textures/module_icons/tree_tracker.png"),
			Map.entry("Tree Broken Notifier", "geileraddons:textures/module_icons/tree_broken_notifier.png"),
			Map.entry("Macros", "geileraddons:textures/module_icons/macros.png"),
			Map.entry("Theme", "geileraddons:textures/gui/octicon-paintbrush-16.png"),
			Map.entry("Mob Highlight", "geileraddons:textures/module_icons/mob_highlight.png"),
			Map.entry("Dungeon Mob ESP", "geileraddons:textures/module_icons/dungeon_mob_esp.png"),
			Map.entry("Block ESP", "geileraddons:textures/module_icons/block_esp.png"),
			Map.entry("Inventory Buttons", "geileraddons:textures/module_icons/inventory_buttons.png"),
			Map.entry("Animations", "geileraddons:textures/module_icons/animations.png"),
			Map.entry("Chat Channels", "geileraddons:textures/module_icons/chat_channels.png"),
			Map.entry("Hide Particles", "geileraddons:textures/module_icons/hide_particles.png"),
			Map.entry("Player Size", "geileraddons:textures/module_icons/player_size.png"),
			Map.entry("Third Person Camera", "geileraddons:textures/module_icons/camera.png"),
			Map.entry("Chat", "geileraddons:textures/module_icons/chat.png"),
			Map.entry("System Clock", "geileraddons:textures/module_icons/system_clock.png"),
			Map.entry("Spirit Leap Menu", "geileraddons:textures/module_icons/spirit_leap_menu.png"),
			Map.entry("Glass Doors", "geileraddons:textures/module_icons/glass_doors.png"),
			Map.entry("Puzzle Solvers", "geileraddons:textures/module_icons/puzzle_solvers.png"),
			Map.entry("Run Splits", "geileraddons:textures/module_icons/run_splits.png"),
			Map.entry("Snap Tap", "geileraddons:textures/module_icons/snap_tap.png"),
			Map.entry("Dungeon Boss ESP", "geileraddons:textures/module_icons/dungeon_boss_esp.png"),
			Map.entry("Dungeon Class ESP", "geileraddons:textures/module_icons/dungeon_class_esp.png"),
			Map.entry("Item Rarity Backgrounds", "geileraddons:textures/module_icons/item_rarity_backgrounds.png"),
			Map.entry("No Cursor Reset", "geileraddons:textures/module_icons/no_cursor_reset.png"),
			Map.entry("Aliases", "geileraddons:textures/module_icons/aliases.png"),
			Map.entry("Instance Requeue", "geileraddons:textures/module_icons/instance_requeue.png"),
			Map.entry("Trail", "geileraddons:textures/module_icons/trail.png"),
			Map.entry("No Loading Screen", "geileraddons:textures/module_icons/no_loading_screen.png")
		);
		Set<String> fullSizeIcons = Set.of(
			"Debug", "General", "Slot IDs", "i4 helper", "Experimentation Solver", "Auto Experiments",
			"Auto Kick", "Theme", "Party Finder Stats", "Dungeon Guide", "Box Doors", "Tiki Helper",
			"Safari Floor Drops", "Hideyho Finder", "Sparkling Critter", "Pest Highlighter", "Infested plot",
			"Tree Tracker", "Tree Broken Notifier", "Macros", "Mob Highlight", "Dungeon Mob ESP", "Block ESP",
			"Inventory Buttons", "Dungeon Boss ESP", "Dungeon Class ESP", "Item Rarity Backgrounds", "No Cursor Reset", "Aliases", "Instance Requeue", "Trail", "No Loading Screen"
		);
		for (Map.Entry<String, String> expected : expectedIcons.entrySet()) {
			Identifier actual = ModuleIconTextures.forModule(expected.getKey());
			check(actual != null && expected.getValue().equals(actual.toString()),
				"module icon path remains stable for " + expected.getKey());
			check(ModuleIconTextures.isTintable(expected.getKey()),
				"module icon tint behavior remains enabled for " + expected.getKey());
			check(ModuleIconTextures.sourceSize(expected.getKey())
				== (fullSizeIcons.contains(expected.getKey()) ? 256 : 230),
				"module icon source size remains stable for " + expected.getKey());
		}
		check(ModuleIconTextures.sourceSize("unregistered") == 64,
			"unknown modules retain the 64-pixel icon source-size fallback");
		check(ModuleIconTextures.forModule("unregistered") == null
			&& !ModuleIconTextures.isTintable("unregistered"),
			"unknown modules have no icon and are not tintable");
	}

	private static void checkHeaderGeometry() {
		int panelX = 40;
		int panelY = 30;
		int panelWidth = ClickGuiHeaderGeometry.panelWidthForScreen(2048);
		int panelHeight = ClickGuiHeaderGeometry.panelHeightForScreen(981);
		int headerHeight = ClickGuiHeaderGeometry.baseHeaderHeightForPanelHeight(panelHeight);
		ClickGuiScreen.Rect logo = ClickGuiHeaderGeometry.profileHeaderRectForLayout(
			panelX, panelY, panelWidth, panelHeight);
		ClickGuiScreen.Rect version = ClickGuiHeaderGeometry.versionBadgeRectForLayout(
			panelX, panelY, panelWidth, panelHeight);
		int brandRoom = ClickGuiHeaderGeometry.categoryBrandRoom(panelWidth, panelHeight);
		int trailingInset = Math.max(12,
			Math.round(ClickGuiHeaderGeometry.headerInsetXForPanelWidth(panelWidth) * 0.4f));
		check(panelWidth == 1844 && panelHeight == 750,
			"the reference panel grows just enough to keep the search field width");
		check(headerHeight == 113 && logo.w() == 93 && logo.h() == 93,
			"the 2048x981 reference header uses the current compact band and logo scale");
		check(version.w() == 86 && version.h() == 28,
			"the reference version badge scales to its requested bounds");
		check(brandRoom == 232,
			"the brand and its inset fit in the reference sidebar");
		check(version.x() == logo.x() + logo.w()
				+ ClickGuiHeaderGeometry.logoVersionGapForPanelWidth(panelWidth)
			&& version.y() + version.h() / 2 == logo.y() + logo.h() / 2,
			"the version badge follows and vertically centers on the logo");
		check(version.x() + version.w() + trailingInset <= panelX + brandRoom,
			"the category rail reserves the brand and its trailing inset");

		int controlHeight = ClickGuiHeaderGeometry.controlHeaderHeightForPanelHeight(panelHeight);
		int buttonSize = ClickGuiHeaderGeometry.headerButtonSizeForLayout(panelWidth, panelHeight);
		ClickGuiScreen.Rect search = ClickGuiHeaderGeometry.searchRectForLayout(
			panelX, panelY, panelWidth, panelHeight, brandRoom);
		ClickGuiScreen.Rect github = ClickGuiHeaderGeometry.topBarRectForLayout(
			panelX, panelY, panelWidth, panelHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		check(controlHeight == 90 && buttonSize == 50 && search.h() == 34,
			"search and toolbar dimensions follow the current compact header formulas");
		check(search.w() == 1284,
			"the wider responsive panel preserves the reference search width");
		check(search.x() >= panelX + brandRoom
			&& search.x() + search.w() <= github.x(),
			"the search field stays between the brand rail and toolbar without overlap");
		check(version.x() + version.w() <= github.x(),
			"the brand badge ends before the toolbar begins");
		int previousRight = -1;
		for (ClickGuiScreen.TopBarIcon icon : ClickGuiScreen.TopBarIcon.values()) {
			ClickGuiScreen.Rect button = ClickGuiHeaderGeometry.topBarRectForLayout(
				panelX, panelY, panelWidth, panelHeight, icon);
			check(button.w() == buttonSize && button.h() == buttonSize,
				"toolbar hit rectangles retain their reference size");
			check(button.x() >= panelX && button.x() + button.w() <= panelX + panelWidth,
				"toolbar hit rectangles stay inside the reference panel");
			check(previousRight < 0 || previousRight <= button.x(),
				"toolbar buttons do not overlap one another");
			previousRight = button.x() + button.w();
		}

		int compactWidth = ClickGuiHeaderGeometry.panelWidthForScreen(854);
		int compactHeight = ClickGuiHeaderGeometry.panelHeightForScreen(480);
		int compactBrandRoom = ClickGuiHeaderGeometry.categoryBrandRoom(compactWidth, compactHeight);
		ClickGuiScreen.Rect compactSearch = ClickGuiHeaderGeometry.searchRectForLayout(
			panelX, panelY, compactWidth, compactHeight, compactBrandRoom);
		ClickGuiScreen.Rect compactGithub = ClickGuiHeaderGeometry.topBarRectForLayout(
			panelX, panelY, compactWidth, compactHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		ClickGuiScreen.Rect compactMove = ClickGuiHeaderGeometry.topBarRectForLayout(
			panelX, panelY, compactWidth, compactHeight, ClickGuiScreen.TopBarIcon.MOVE_ELEMENTS);
		ClickGuiScreen.Rect compactVersion = ClickGuiHeaderGeometry.versionBadgeRectForLayout(
			panelX, panelY, compactWidth, compactHeight);
		check(ClickGuiHeaderGeometry.headerButtonSizeForLayout(compactWidth, compactHeight) == 24
			&& compactSearch.h() == 18,
			"compact screens use toolbar and search dimensions from the shared header geometry");
		check(compactSearch.x() >= panelX + compactBrandRoom
			&& compactSearch.x() + compactSearch.w() <= compactGithub.x(),
			"the compact search field remains bounded between brand and toolbar");
		check(compactVersion.x() + compactVersion.w() <= compactGithub.x()
			&& compactGithub.x() >= panelX
			&& compactMove.x() + compactMove.w() <= panelX + compactWidth,
			"compact brand and toolbar bounds remain separate and inside the panel");

		int narrowWidth = ClickGuiHeaderGeometry.panelWidthForScreen(240);
		int narrowHeight = ClickGuiHeaderGeometry.panelHeightForScreen(160);
		int narrowBrandRoom = ClickGuiHeaderGeometry.categoryBrandRoom(narrowWidth, narrowHeight);
		ClickGuiScreen.Rect narrowVersion = ClickGuiHeaderGeometry.versionBadgeRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight);
		ClickGuiScreen.Rect narrowGithub = ClickGuiHeaderGeometry.topBarRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		ClickGuiScreen.Rect narrowSearch = ClickGuiHeaderGeometry.searchRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight, narrowBrandRoom);
		check(narrowVersion.x() + narrowVersion.w() <= narrowGithub.x()
			&& narrowGithub.x() >= panelX
			&& ClickGuiHeaderGeometry.topBarRectForLayout(panelX, panelY, narrowWidth, narrowHeight,
				ClickGuiScreen.TopBarIcon.MOVE_ELEMENTS).x()
				+ ClickGuiHeaderGeometry.headerButtonSizeForLayout(narrowWidth, narrowHeight) <= panelX + narrowWidth
			&& narrowSearch.w() == 0,
			"the search field is hidden when it cannot fit between the brand and toolbar");
	}

	private static void checkResponsiveClickGuiSizing() {
		check(ClickGuiHeaderGeometry.panelWidthForScreen(1582) == 1424,
			"the reference width uses the mockup panel proportion");
		check(ClickGuiHeaderGeometry.panelHeightForScreen(1024) == 783,
			"the reference height uses the mockup panel proportion");
		check(ClickGuiHeaderGeometry.panelWidthForScreen(854) == 769
			&& ClickGuiHeaderGeometry.panelHeightForScreen(480) == 367,
			"the compact acceptance size retains a centered, responsive panel");
		check(ClickGuiModuleGrid.cardColumnsForModuleWidth(500) == 2
			&& ClickGuiModuleGrid.cardColumnsForModuleWidth(720) == 3
			&& ClickGuiModuleGrid.cardColumnsForModuleWidth(840) == 3
			&& ClickGuiModuleGrid.cardColumnsForModuleWidth(900) == 4
			&& ClickGuiModuleGrid.cardColumnsForModuleWidth(1100) == 4,
			"module cards use two, three, then four columns as the content width allows");
		check(ClickGuiModuleGrid.cardHeightForPanelHeight(367) == 63
			&& ClickGuiModuleGrid.cardHeightForPanelHeight(783) == 135,
			"card height scales with the panel instead of staying pixel-fixed");
		check(ClickGuiModuleGrid.cardGapForPanelWidth(733) == 11
			&& ClickGuiModuleGrid.cardGapForPanelWidth(1357) == 20,
			"card spacing scales with the panel width");
		checkCompactModuleCardActions();
		check(ClickGuiModuleGrid.gridInsetForPanelWidth(733) == 14
			&& ClickGuiModuleGrid.gridInsetForPanelWidth(1357) == 26,
			"card margins scale to the approved reference proportions");
		check(ClickGuiScreenGeometry.categoryRowHeightForPanelHeight(367) == 24
			&& ClickGuiScreenGeometry.categoryRowHeightForPanelHeight(783) == 39,
			"the category rail uses compact rows while preserving a usable minimum target");
	}

	private static void checkCompactModuleCardActions() {
		ClickGuiScreen.Rect compact = new ClickGuiScreen.Rect(12, 18, 160, 63);
		ClickGuiScreen.Rect toggle = ClickGuiScreen.cardSwitchRect(compact);
		ClickGuiScreen.Rect favorite = ClickGuiScreen.moduleFavoriteHitRect(compact, true, toggle.y());
		boolean overlaps = favorite.x() < toggle.x() + toggle.w() && favorite.x() + favorite.w() > toggle.x()
			&& favorite.y() < toggle.y() + toggle.h() && favorite.y() + favorite.h() > toggle.y();
		check(!overlaps, "the compact module favorite hit target never intercepts the enable switch");
		check(favorite.contains(favorite.x() + favorite.w() / 2.0, favorite.y() + favorite.h() / 2.0)
			&& toggle.contains(toggle.x() + toggle.w() / 2.0, toggle.y() + toggle.h() / 2.0),
			"heart and switch retain separate clickable centers on compact module cards");
		check(favorite.x() + favorite.w() / 2 == toggle.x() + toggle.w() / 2,
			"the favorite heart and enable switch share one horizontal alignment axis");
	}

	private static void checkScrollbarGutter() {
		int viewportWidth = 200;
		int columns = 3;
		int gap = 10;
		int contentWidth = ClickGuiScreen.gridCardContentWidth(viewportWidth);
		int cardWidth = ClickGuiScreen.cardWidthForGridViewport(viewportWidth, columns, gap);
		check(contentWidth == 191, "module cards reserve the scrollbar and its side padding");
		check(columns * cardWidth + (columns - 1) * gap <= contentWidth,
			"the final card stays inside the content area, clear of the scrollbar hit strip");
	}

	private static void checkVisibleGridRows() {
		checkVisibleGridRows(0, 100, 180, 110, 6, "top of the grid");
		checkVisibleGridRows(40, 100, 180, 110, 6, "a card ending at the viewport bottom");
		checkVisibleGridRows(100, 100, 180, 110, 6, "a card ending at the viewport top");
		checkVisibleGridRows(105, 100, 180, 110, 6, "partial cards at both viewport edges");
		checkVisibleGridRows(470, 100, 180, 110, 6, "the bottom of the grid");
		checkVisibleGridRows(0, 100, 800, 110, 2, "content shorter than the viewport");
	}

	private static void checkVisibleGridRows(int scroll, int cardHeight, int viewportHeight,
		int rowStep, int rowCount, String scenario) {
		int first = Math.max(0,
			ClickGuiModuleGrid.firstGridRowIntersectingViewport(scroll, cardHeight, rowStep));
		int last = Math.min(rowCount - 1,
			ClickGuiModuleGrid.lastGridRowIntersectingViewport(scroll, viewportHeight, rowStep));
		for (int row = 0; row < rowCount; row++) {
			int top = row * rowStep - scroll;
			boolean intersects = top + cardHeight > 0 && top < viewportHeight;
			boolean selected = row >= first && row <= last;
			check(intersects == selected,
				"visible-row culling matches full geometry for " + scenario + " at row " + row);
		}
	}

	private static void checkModuleGridSectionsAndGeometry() {
		List<Module> modules = List.of(DungeonTerminalTitlesModule.INSTANCE, I4HelperModule.INSTANCE,
			DungeonTerminalStatsModule.INSTANCE, CroesusModule.INSTANCE);
		List<ClickGuiModuleGrid.Section> sections = ClickGuiModuleGrid.sections(Category.F7, modules, "");
		check(sections.size() == 2
			&& sections.get(0).label().equals("F7")
			&& sections.get(0).modules().equals(modules.subList(0, 3))
			&& sections.get(1).label().equals("General")
			&& sections.get(1).modules().equals(List.of(CroesusModule.INSTANCE)),
			"module cards retain child-filter grouping, source order, and the Other/general remainder");

		List<ClickGuiModuleGrid.Section> searchSections = ClickGuiModuleGrid.sections(Category.F7, modules, "terminal");
		check(searchSections.size() == 1 && searchSections.getFirst().label() == null
			&& searchSections.getFirst().modules() == modules,
			"search results keep their flat order without category headings");

		List<Module> f7Only = modules.stream().filter(m ->
			ClickGuiModuleSearchBehavior.categoryMatches(Category.F7, m, "f7")).toList();
		List<Module> generalOnly = modules.stream().filter(m ->
			ClickGuiModuleSearchBehavior.categoryMatches(Category.F7, m, "general")).toList();
		check(f7Only.equals(modules.subList(0, 3)) && generalOnly.equals(List.of(CroesusModule.INSTANCE))
				&& modules.stream().allMatch(m -> ClickGuiModuleSearchBehavior.categoryMatches(Category.F7, m, "all"))
				&& modules.stream().allMatch(m -> ClickGuiModuleSearchBehavior.categoryMatches(Category.F7, m, "gone")),
			"a subcategory chip keeps only its modules; All and an unknown id keep every module");
		check(ClickGuiModuleGrid.sections(Category.F7, f7Only, "").size() == 1
				&& ClickGuiModuleGrid.sections(Category.F7, f7Only, "").getFirst().label().equals("F7"),
			"a filtered grid shows the single chosen section under its heading");

		List<ClickGuiModuleGrid.ChipRect> wide = ClickGuiModuleGrid.chipLayout(List.of(30, 40, 50), 300, 12, 4, 6).chips();
		ClickGuiModuleGrid.ChipLayout narrow = ClickGuiModuleGrid.chipLayout(List.of(30, 40, 50), 80, 12, 4, 6);
		check(wide.size() == 3 && wide.get(0).x() == 0 && wide.get(1).x() == 34 && wide.get(2).x() == 78
				&& wide.stream().allMatch(c -> c.y() == 0 && c.h() == 12)
				&& ClickGuiModuleGrid.chipLayout(List.of(30, 40, 50), 300, 12, 4, 6).height() == 12,
			"chips sit on one row, in order, with the gap between them when they fit");
		check(narrow.chips().size() == 3 && narrow.chips().get(0).y() == 0 && narrow.chips().get(1).y() == 0
				&& narrow.chips().get(2).y() == 18 && narrow.chips().get(2).x() == 0 && narrow.height() == 30
				&& narrow.chips().stream().allMatch(c -> c.x() >= 0 && c.x() + c.w() <= 80),
			"chips wrap onto another row, never leave the bar, and report the full bar height");
		check(ClickGuiModuleGrid.chipLayout(List.of(), 100, 12, 4, 6).height() == 0
				&& ClickGuiModuleGrid.chipLayout(List.of(200), 80, 12, 4, 6).chips().get(0).w() == 80,
			"no chips take no space and an oversized chip is clamped to the bar width");

		ClickGuiModuleGrid.Layout layout = ClickGuiModuleGrid.layout(sections, 2, 60, 10, 16, 4, 8);
		check(layout.sections().size() == 2
			&& layout.sections().get(0).headingY() == 0 && layout.sections().get(0).cardsY() == 20
			&& layout.sections().get(0).firstModuleIndex() == 0 && layout.sections().get(0).rowCount() == 2
			&& layout.sections().get(1).headingY() == 158 && layout.sections().get(1).cardsY() == 178
			&& layout.sections().get(1).firstModuleIndex() == 3 && layout.sections().get(1).rowCount() == 1
			&& layout.contentHeight() == 238,
			"section headings, card rows, indices, and content height retain their original offsets");

		ClickGuiScreen.Rect viewport = new ClickGuiScreen.Rect(10, 20, 250, 85);
		List<ClickGuiModuleGrid.CardRect> allCards = ClickGuiModuleGrid.cardRects(
			layout, viewport.x(), viewport.y(), 30, 2, 80, 60, 10);
		check(allCards.size() == modules.size()
			&& allCards.get(0).x() == 10 && allCards.get(0).y() == 10
			&& allCards.get(2).x() == 10 && allCards.get(2).y() == 80
			&& allCards.get(3).index() == 3 && allCards.get(3).y() == 168,
			"full card hit geometry retains row, section, scroll, and global index positions");

		for (int scroll : new int[] {0, 30, 150, 153}) {
			List<ClickGuiModuleGrid.CardRect> expected = allCardsAtScroll(layout, viewport, scroll);
			List<ClickGuiModuleGrid.CardRect> visible = ClickGuiModuleGrid.visibleCardRects(
				layout, viewport.x(), viewport.y(), viewport.h(), scroll, 2, 80, 60, 10);
			check(visible.equals(expected), "visible card culling matches full geometry at scroll " + scroll);
		}
	}

	private static void checkModuleSearchBehavior() {
		Module searchable = DungeonTerminalStatsModule.INSTANCE;
		List<ClickGuiSearchEntry> index = ClickGuiModuleSearchBehavior.buildSearchIndex(List.of(
			GeneralModule.INSTANCE, VisualModule.INSTANCE, searchable));
		check(index.size() == 1 && index.get(0).module() == searchable,
			"the global search index excludes the General and Theme settings surfaces");

		String query = ClickGuiModuleSearchBehavior.normalizeQuery(
			"  " + searchable.name().toUpperCase(java.util.Locale.ROOT) + "  ");
		check(query.equals(searchable.name().toLowerCase(java.util.Locale.ROOT))
			&& ClickGuiModuleSearchBehavior.matchingModules(index, query).equals(List.of(searchable)),
			"global module search trims and folds queries using locale-independent casing");
		check(ClickGuiModuleSearchBehavior.searchableText(searchable)
			.contains(searchable.description().toLowerCase(java.util.Locale.ROOT)),
			"global search includes each module description as well as its name");
		check(ClickGuiModuleSearchBehavior.matchingModules(index, "not-a-module-token").isEmpty(),
			"global module search returns an empty result for unmatched terms");
	}

	private static void checkTextCursorCodePoints() {
		String value = "A\uD83D\uDE00B";
		check(ClickGuiTextEditing.clampCursor(value, 2) == 1,
			"a cursor cannot remain between a supplementary character's surrogate pair");
		check(ClickGuiTextEditing.clampCursor(value, -2) == 0
				&& ClickGuiTextEditing.clampCursor(value, 10) == value.length(),
			"cursor clamping remains bounded to the string");
		check(ClickGuiTextEditing.moveCursor(value, 1, 1) == 3
				&& ClickGuiTextEditing.moveCursor(value, 3, -1) == 1,
			"left and right movement treat supplementary characters as one code point");
		check(ClickGuiTextEditing.moveCursor(value, 0, -1) == 0
				&& ClickGuiTextEditing.moveCursor(value, value.length(), 1) == value.length(),
			"cursor movement remains bounded at both ends");
		ClickGuiTextEditResult backspace = ClickGuiTextEditing.applyKey(
			value, 3, false, ClickGuiTextEditKey.BACKSPACE);
		ClickGuiTextEditResult delete = ClickGuiTextEditing.applyKey(
			value, 1, false, ClickGuiTextEditKey.DELETE);
		check(backspace.getText().equals("AB") && backspace.getCursor() == 1
				&& delete.getText().equals("AB") && delete.getCursor() == 1,
			"backspace and delete remove one full supplementary character and retain a valid cursor");
		ClickGuiTextEditResult selectLeft = ClickGuiTextEditing.applyKey(
			value, 1, true, ClickGuiTextEditKey.LEFT);
		ClickGuiTextEditResult selectRight = ClickGuiTextEditing.applyKey(
			value, 1, true, ClickGuiTextEditKey.RIGHT);
		ClickGuiTextEditResult clearSelection = ClickGuiTextEditing.applyKey(
			value, 1, true, ClickGuiTextEditKey.DELETE);
		check(selectLeft.getText().equals(value) && selectLeft.getCursor() == 0 && !selectLeft.getSelectAll()
				&& selectRight.getCursor() == value.length() && !selectRight.getSelectAll(),
			"arrow keys collapse an active selection to its start or end");
		check(clearSelection.getText().isEmpty() && clearSelection.getCursor() == 0
				&& !clearSelection.getSelectAll(),
			"delete clears the full selected value and selection state");
		ClickGuiTextEditResult inserted = ClickGuiTextEditing.insertAtCursor(value, 1, false, "X");
		ClickGuiTextEditResult replaced = ClickGuiTextEditing.insertAtCursor(value, 2, true, "X");
		check(inserted.getText().equals("AX\uD83D\uDE00B") && inserted.getCursor() == 2
				&& replaced.getText().equals("X") && replaced.getCursor() == 1 && !replaced.getSelectAll(),
			"insertion preserves code points, clamps split-surrogate cursors, and replaces selected text");
	}

	private static List<ClickGuiModuleGrid.CardRect> allCardsAtScroll(
		ClickGuiModuleGrid.Layout layout, ClickGuiScreen.Rect viewport, int scroll) {
		List<ClickGuiModuleGrid.CardRect> all = ClickGuiModuleGrid.cardRects(
			layout, viewport.x(), viewport.y(), scroll, 2, 80, 60, 10);
		return all.stream().filter(card -> card.y() + card.h() > viewport.y()
			&& card.y() < viewport.y() + viewport.h()).toList();
	}

	private static void checkCategoryScroll() {
		check(ClickGuiScreen.categoryContentHeight()
				== Category.Companion.of(geiler.addons.client.module.Workspace.NORMAL).size() * 22,
			"the compatibility helper still reports its baseline row height");
		int compactContent = ClickGuiScreen.categoryContentHeightForPanelHeight(367);
		check(compactContent == 276, "the compact rail fits all eleven categories below its header gap");
		check(ClickGuiScreen.categoryMaxScroll(367, 298) == 0,
			"the compact rail needs no scroll when all category rows fit");
		int referenceContent = ClickGuiScreen.categoryContentHeightForPanelHeight(783);
		check(referenceContent == 456, "the reference rail uses compact responsive row spacing");
		check(ClickGuiScreen.categoryMaxScroll(783, 637) == 0,
			"the full-height reference rail fits every category without scrolling");
		check(ClickGuiScreen.categoryMaxScroll(referenceContent) == 0,
			"a column that fits every row does not scroll");
		check(ClickGuiScreen.categoryMaxScroll(referenceContent * 2) == 0,
			"a column taller than its rows does not scroll");
		check(ClickGuiScreen.categoryMaxScroll(1) == ClickGuiScreen.categoryContentHeight() - 1,
			"a one-pixel baseline column can still reach its last row");
		check(ClickGuiScreen.categoryMaxScroll(0) == ClickGuiScreen.categoryContentHeight() - 1,
			"an unmeasurable column falls back to the smallest usable height");
	}

	private static void checkCategoryHierarchy() {
		checkModuleCategoryFilterJavaValueApi();
		checkSubcategoriesAreData();
		checkGeneralExcludesSiblingModules();
		checkDungeonAndMiscSubcategories();
		checkCategoryBehaviorHelpers();
		List<ClickGuiScreen.CategoryEntry> collapsedDungeons = ClickGuiScreen.categoryEntries(
			List.of(Category.F7), Set.of());
		List<ClickGuiScreen.CategoryEntry> expandedDungeons = ClickGuiScreen.categoryEntries(
			List.of(Category.F7), Set.of(Category.F7));
		check(collapsedDungeons.size() == 1 && collapsedDungeons.get(0).isParent()
			&& collapsedDungeons.get(0).filter().id().equals("all"),
			"a collapsed Dungeons parent is one row and selects the full category");
		check(expandedDungeons.size() == 6
			&& expandedDungeons.get(0).category() == Category.F7
			&& expandedDungeons.get(1).filter().label().equals("F7")
			&& expandedDungeons.get(2).filter().label().equals("M7")
			&& expandedDungeons.get(5).filter().label().equals("General")
			&& expandedDungeons.stream().noneMatch(entry -> entry.filter().label().equals("F5")),
			"expanded Dungeons exposes F7, M7, Highlight, Room / Map, and General as child filters");
		check(ClickGuiScreen.categoryEntrySelected(collapsedDungeons.getFirst(), Category.F7, "f7", false)
			&& ClickGuiScreen.categoryEntryLabel(collapsedDungeons.getFirst(), "f7", false)
			.equals("Dungeons · F7"),
			"a collapsed Dungeons parent makes its retained F7 filter visible and selected");
		check(!ClickGuiScreen.categoryEntrySelected(expandedDungeons.getFirst(), Category.F7, "f7", true)
			&& ClickGuiScreen.categoryEntrySelected(expandedDungeons.get(1), Category.F7, "f7", true)
			&& ClickGuiScreen.categoryEntryLabel(expandedDungeons.getFirst(), "f7", true).equals("Dungeons"),
			"expansion restores the child-row selection without changing the parent label");

		List<ClickGuiScreen.CategoryEntry> expandedHunting = ClickGuiScreen.categoryEntries(
			List.of(Category.HUNTING), Set.of(Category.HUNTING));
		check(expandedHunting.size() == 3
			&& expandedHunting.get(1).filter().label().equals("Critter Safari")
			&& expandedHunting.get(2).filter().label().equals("General"),
			"expanded Hunting keeps Critter Safari and General as distinct children");
		List<ClickGuiScreen.CategoryEntry> collapsedHunting = ClickGuiScreen.categoryEntries(
			List.of(Category.HUNTING), Set.of());
		check(ClickGuiScreen.categoryEntrySelected(collapsedHunting.getFirst(), Category.HUNTING,
			"general", false)
			&& ClickGuiScreen.categoryEntryLabel(collapsedHunting.getFirst(), "general", false)
			.equals("Hunting · General"),
			"a collapsed Hunting parent exposes its retained General filter");

		Module dungeonF7 = new CategoryCheckModule("i4 helper", Category.F7);
		Module dungeonGeneral = new CategoryCheckModule("Croesus", Category.F7);
		Module tiki = new CategoryCheckModule("Tiki Helper", Category.HUNTING);
		Module safari = new CategoryCheckModule("Sparkling Critter", Category.HUNTING);
		check(ModuleCategoryFilter.resolve(Category.F7, "all").matches(dungeonF7)
			&& ModuleCategoryFilter.resolve(Category.F7, "all").matches(dungeonGeneral)
			&& ModuleCategoryFilter.resolve(Category.F7, "f7").matches(dungeonF7)
			&& !ModuleCategoryFilter.resolve(Category.F7, "f7").matches(dungeonGeneral)
			&& ModuleCategoryFilter.resolve(Category.F7, "general").matches(dungeonGeneral)
			&& !ModuleCategoryFilter.resolve(Category.F7, "general").matches(dungeonF7),
			"Dungeons parent, F7, and General filters include their intended modules");
		check(ModuleCategoryFilter.resolve(Category.HUNTING, "all").matches(tiki)
			&& ModuleCategoryFilter.resolve(Category.HUNTING, "general").matches(tiki)
			&& !ModuleCategoryFilter.resolve(Category.HUNTING, "critter-safari").matches(tiki)
			&& ModuleCategoryFilter.resolve(Category.HUNTING, "critter-safari").matches(safari),
			"Hunting General includes Tiki Helper while Critter Safari remains isolated");

		check(!ClickGuiScreenCategoryBehavior.isModuleCardVisible(VisualModule.INSTANCE)
			&& !ClickGuiScreenCategoryBehavior.isModuleCardVisible(GeneralModule.INSTANCE),
			"Theme and Settings/General remain toolbar actions without module cards");
		check(!ModuleCategoryFilter.resolve(Category.VISUAL, "interface").matches(VisualModule.INSTANCE)
			&& !ModuleCategoryFilter.resolve(Category.MISCELLANEOUS, "utilities").matches(GeneralModule.INSTANCE),
			"the Visual Interface and Miscellaneous Utilities children omit toolbar-only entries");

		int collapsedContent = ClickGuiScreen.categoryContentHeightForPanelHeight(367, Category.values().length);
		int expandedContent = ClickGuiScreen.categoryContentHeightForPanelHeight(367, Category.values().length + 4);
		check(expandedContent - collapsedContent == 4 * ClickGuiScreenGeometry.categoryRowHeightForPanelHeight(367)
			&& ClickGuiScreen.categoryMaxScroll(367, 298, Category.values().length + 4) > 0,
			"expanded child rows add to rail height and remain reachable by scrolling");
	}

	private static void checkCategoryBehaviorHelpers() {
		ClickGuiCategorySelectionPlan filterChange = ClickGuiScreenCategoryBehavior.categorySelectionPlan(
			Category.F7, "f7", Category.F7, "all");
		check(filterChange.getCategory() == Category.F7 && filterChange.getFilterId().equals("f7")
			&& filterChange.getFilterChanged() && !filterChange.getCategoryChanged(),
			"the Kotlin selection plan reports a filter-only transition");
		ClickGuiCategorySelectionPlan unchanged = ClickGuiScreenCategoryBehavior.categorySelectionPlan(
			Category.F7, "all", Category.F7, "all");
		check(!unchanged.getFilterChanged() && !unchanged.getCategoryChanged(),
			"the Kotlin selection plan preserves unchanged category and filter state");
		ClickGuiCategorySelectionPlan categoryChange = ClickGuiScreenCategoryBehavior.categorySelectionPlan(
			Category.HUNTING, "all", Category.F7, "all");
		check(!categoryChange.getFilterChanged() && categoryChange.getCategoryChanged(),
			"the Kotlin selection plan reports a category-only transition");

		Set<String> favoritesBefore = ClickGuiState.favoriteCategoryIds();
		boolean f7WasFavorite = ClickGuiState.isFavorite(Category.F7);
		try {
			ClickGuiScreenCategoryBehavior.toggleCategoryFavorite(Category.F7);
			check(ClickGuiState.isFavorite(Category.F7) != f7WasFavorite,
				"the Kotlin favorite helper toggles the requested category");
			ClickGuiScreenCategoryBehavior.toggleCategoryFavorite(Category.F7);
			check(ClickGuiState.favoriteCategoryIds().equals(favoritesBefore),
				"toggling the same category twice restores the original favorite state");
			ClickGuiScreenCategoryBehavior.toggleCategoryFavorite(null);
			check(ClickGuiState.favoriteCategoryIds().equals(favoritesBefore),
				"a null Kotlin favorite toggle leaves favorites unchanged");
		} finally {
			ClickGuiState.setFavoriteCategoryIds(favoritesBefore);
		}
		check(ClickGuiState.favoriteCategoryIds().equals(favoritesBefore)
			&& ClickGuiState.isFavorite(Category.F7) == f7WasFavorite,
			"favorite helper checks restore the pre-check config state");

		check(ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(40, 12, 100) == 28,
			"the Kotlin category scroll helper applies the existing wheel delta");
		check(ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(0, 12, 100) == 0,
			"the Kotlin category scroll helper clamps at the lower bound");
		check(ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(98, -10, 100) == 100,
			"the Kotlin category scroll helper clamps at the upper bound");
		check(ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(42, 0, 100) == 42,
			"a zero wheel delta leaves category scroll unchanged");
		check(ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(42, 0, 0) == 0,
			"a zero scroll range remains bounded at zero");

		check(ClickGuiScreenRenderBehavior.groupHeaderColor(false, 0.0f) == GuiTheme.GROUP_HEADER
			&& ClickGuiScreenRenderBehavior.groupHeaderColor(true, 0.5f) == GuiTheme.CARD_BG_HOVER
			&& ClickGuiScreenRenderBehavior.groupHeaderColor(false, 0.5f)
				== GuiTheme.lerpColor(GuiTheme.GROUP_HEADER, GuiTheme.CARD_BG_HOVER, 0.4f),
			"Kotlin group headers preserve resting, hover, and pulse colors");
		check(ClickGuiScreenRenderBehavior.settingsRowHighlightColor(true, 0.5f) == GuiTheme.CATEGORY_HOVER
			&& ClickGuiScreenRenderBehavior.settingsRowHighlightColor(false, 0.5f)
				== GuiTheme.withOpacity(GuiTheme.PANEL_HIGHLIGHT, 0.175f),
			"Kotlin setting-row highlights preserve hover priority and pulse opacity");
		check(ClickGuiScreenRenderBehavior.choiceFieldColor(false, 0.0f) == GuiTheme.BUTTON_BG
			&& ClickGuiScreenRenderBehavior.choiceFieldColor(true, 0.5f) == GuiTheme.BUTTON_HOVER
			&& ClickGuiScreenRenderBehavior.choiceFieldColor(false, 0.5f)
				== GuiTheme.lerpColor(GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER, 0.375f),
			"Kotlin choice fields preserve resting, hover, and pulse colors");
		check(ClickGuiScreenRenderBehavior.actionButtonColor(false, 0.0f) == GuiTheme.BUTTON_BG
			&& ClickGuiScreenRenderBehavior.actionButtonColor(true, 0.5f) == GuiTheme.BUTTON_HOVER
			&& ClickGuiScreenRenderBehavior.actionButtonColor(false, 0.5f)
				== GuiTheme.lerpColor(GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER, 0.4f),
			"Kotlin action buttons preserve resting, hover, and pulse colors");
	}

	/** Dungeons and Miscellaneous expose their new chips in order, and a module sits under one named chip only. */
	private static void checkDungeonAndMiscSubcategories() {
		check(ModuleCategoryFilter.options(Category.F7).stream().map(ModuleCategoryFilter::label).toList()
			.equals(List.of("All", "F7", "M7", "Highlight", "Room / Map", "General")),
			"Dungeons lists F7, M7, Highlight, Room / Map, and General in that order");
		check(ModuleCategoryFilter.options(Category.MISCELLANEOUS).stream().map(ModuleCategoryFilter::label).toList()
			.containsAll(List.of("Inventory", "Player", "World", "Display", "Fix")),
			"Miscellaneous offers the Inventory, Player, World, Display, and Fix subcategories");
		for (Category category : List.of(Category.F7, Category.MISCELLANEOUS)) {
			Set<String> seen = new java.util.HashSet<>();
			for (ModuleCategoryFilter child : ModuleCategoryFilter.children(category)) {
				if ("general".equals(child.id())) continue;
				for (String name : child.includedNames()) {
					check(seen.add(name), name + " belongs to one " + category + " subcategory only");
				}
			}
		}
	}

	/** A subcategory exists because the layout data says so; adding one needs no code. */
	private static void checkSubcategoriesAreData() {
		var parsed = ModuleCategoryFilter.parseLayout("{\"subcategories\":["
			+ "{\"category\":\"VISUAL\",\"id\":\"brand-new\",\"label\":\"Brand New\",\"modules\":[\"Some Module\"]},"
			+ "{\"category\":\"NOT_A_CATEGORY\",\"id\":\"x\",\"label\":\"X\",\"modules\":[\"Y\"]},"
			+ "{\"category\":\"DEV\",\"id\":\"rest\",\"label\":\"Rest\",\"except\":[\"Slot IDs\"]}]}");
		check(parsed.get(Category.VISUAL).size() == 1 && "Brand New".equals(parsed.get(Category.VISUAL).get(0).label())
			&& parsed.get(Category.VISUAL).get(0).includedNames().contains("Some Module"),
			"a new subcategory in the data becomes a filter");
		check(!parsed.containsKey(null) && parsed.size() == 2, "entries for unknown categories are ignored");
		check(parsed.get(Category.DEV).get(0).excludedNames().contains("Slot IDs"), "except lists become exclusions");
		check(ModuleCategoryFilter.parseLayout("not json").isEmpty(), "malformed layout data yields no subcategories");
		check(ModuleCategoryFilter.options(Category.MISCELLANEOUS).stream().anyMatch(f -> "utilities".equals(f.id())),
			"the shipped module-layout.json provides the built-in subcategories");
	}

	/** General is the catch-all: it must never show a module that another subcategory of the same category lists. */
	private static void checkGeneralExcludesSiblingModules() {
		for (Category category : Category.values()) {
			ModuleCategoryFilter general = ModuleCategoryFilter.resolve(category, "general");
			if ("all".equals(general.id())) continue;
			for (ModuleCategoryFilter sibling : ModuleCategoryFilter.children(category)) {
				if ("general".equals(sibling.id())) continue;
				for (String name : sibling.includedNames()) {
					check(!general.matches(new CategoryCheckModule(name, category)),
						category + " General must not show " + name + " from " + sibling.id());
				}
			}
		}
		ModuleCategoryFilter dungeonsGeneral = ModuleCategoryFilter.resolve(Category.F7, "general");
		check(!dungeonsGeneral.matches(new CategoryCheckModule("Wither Dragons", Category.F7))
			&& !dungeonsGeneral.matches(new CategoryCheckModule("Terminal Solver", Category.F7)),
			"Dungeons General omits the F7 boss and terminal modules");
	}

	private static void checkModuleCategoryFilterJavaValueApi() {
		try {
			ModuleCategoryFilter.class.getConstructor(String.class, String.class, Category.class, Set.class, Set.class);
			check(ModuleCategoryFilter.class.getMethod("id").getReturnType() == String.class
				&& ModuleCategoryFilter.class.getMethod("label").getReturnType() == String.class
				&& ModuleCategoryFilter.class.getMethod("category").getReturnType() == Category.class
				&& ModuleCategoryFilter.class.getMethod("includedNames").getReturnType() == Set.class
				&& ModuleCategoryFilter.class.getMethod("excludedNames").getReturnType() == Set.class,
				"Kotlin module category filters retain the Java constructor and component accessors");
			ModuleCategoryFilter.class.getMethod("options", Category.class);
			ModuleCategoryFilter.class.getMethod("children", Category.class);
			ModuleCategoryFilter.class.getMethod("resolve", Category.class, String.class);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Kotlin module category filters retain the Java API", failure);
		}
		java.util.HashSet<String> included = new java.util.HashSet<>(Set.of("Player Names"));
		ModuleCategoryFilter filter = new ModuleCategoryFilter("test", "Test", Category.VISUAL, included, null);
		included.add("Player Size");
		check(filter.includedNames().equals(Set.of("Player Names")) && filter.excludedNames().isEmpty(),
			"module category filter copies non-null input sets and normalizes null sets to empty");
		try {
			filter.includedNames().add("Player Size");
			throw new AssertionError("module category filter names must remain immutable");
		} catch (UnsupportedOperationException expected) {
			// Set.copyOf preserves the Java record's immutable component contract.
		}
		ModuleCategoryFilter equal = new ModuleCategoryFilter("test", "Test", Category.VISUAL,
			Set.of("Player Names"), Set.of());
		check(filter.equals(equal) && filter.hashCode() == equal.hashCode()
			&& filter.toString().equals("ModuleCategoryFilter[id=test, label=Test, category=VISUAL, "
				+ "includedNames=[Player Names], excludedNames=[]]"),
			"module category filter value equality, hash, and record-style string output remain stable");
		ModuleCategoryFilter nullable = new ModuleCategoryFilter(null, null, null, null, null);
		check(nullable.id() == null && nullable.label() == null && nullable.category() == null,
			"nullable record components remain nullable through the Kotlin Java-compatible accessors");
	}

	private static void checkSearchInset() {
		int fieldHeight = 24;
		int iconLeft = 8;
		int iconSize = 18;
		check(ClickGuiScreen.searchTextInsetForHeight(fieldHeight) == iconLeft + iconSize + 6,
			"search text starts after the rendered icon and a six-pixel gap");
		check(ClickGuiScreen.searchTextViewportWidth(180, fieldHeight) == 140,
			"search text hit testing reserves the same icon inset and right padding as rendering");
		check(ClickGuiScreen.searchTextFontWidth(180, fieldHeight) == 184,
			"scaled search rendering and mouse character mapping share the font-space width");
		check(Math.abs(ClickGuiScreen.textOffsetForMouse(138, 100, 0.76f) - 50.0) < 0.001,
			"mouse hit testing converts scaled screen pixels to the same text-space coordinates");
		check(ClickGuiScreen.searchTextViewportWidth(20, fieldHeight) == 1
			&& ClickGuiScreen.searchTextFontWidth(20, fieldHeight) >= 1,
			"narrow search fields keep a bounded, usable text viewport");
	}

	private static void checkBlockPickerScroll() {
		// Ten options in a 96-pixel viewport show four rows, so six may be scrolled past.
		check(BlockPickerScreen.maxScroll(10, 96) == 6,
			"the wheel clamp matches the render clamp, got " + BlockPickerScreen.maxScroll(10, 96));
		check(BlockPickerScreen.maxScroll(4, 96) == 0, "a list that fits does not scroll");
		check(BlockPickerScreen.maxScroll(3, 96) == 0, "a shorter list does not scroll");
		check(BlockPickerScreen.maxScroll(0, 96) == 0, "an empty list does not scroll");
		check(BlockPickerScreen.maxScroll(10, 0) == 9,
			"a viewport too short for one row still lets every option be reached");
		check(BlockPickerScreen.maxScroll(10, 24) == 9, "exactly one visible row behaves as one row");
	}

	private static void checkChangelogScrollPrecedence() {
		check(ClickGuiScreenCategoryBehavior.scrollTarget(true, true, true, true, true)
			== ClickGuiScrollTarget.CHANGELOG,
			"an open changelog captures wheel input before overlapping category, settings, and module regions");
		check(ClickGuiScreenCategoryBehavior.scrollTarget(false, true, true, true, true)
			== ClickGuiScrollTarget.CATEGORY,
			"without the modal, the category rail keeps its existing precedence over the main panel");
		check(ClickGuiScreenCategoryBehavior.scrollTarget(false, false, true, true, true)
			== ClickGuiScrollTarget.SETTINGS,
			"the open settings panel receives wheel input while hovered");
		check(ClickGuiScreenCategoryBehavior.scrollTarget(false, false, true, false, true)
			== ClickGuiScrollTarget.NONE,
			"an open settings panel does not pass wheel input through to the module grid");
		check(ClickGuiScreenCategoryBehavior.scrollTarget(false, false, false, false, true)
			== ClickGuiScrollTarget.GRID,
			"the module grid receives wheel input when no higher-priority panel is active");
		check(ClickGuiScreenCategoryBehavior.scrollTarget(false, false, false, false, false)
			== ClickGuiScrollTarget.NONE,
			"wheel input outside all scroll regions has no target");
	}

	private static void checkSettingsRowLayout() {
		SettingsLayoutFixture module = new SettingsLayoutFixture();
		boolean debugWasEnabled = DebugState.enabled();
		try {
			DebugState.setEnabled(false);
			List<Row> folded = ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160);
			check(folded.size() == 1 && folded.getFirst() instanceof GroupRow,
				"a folded settings group keeps only its own header in the row layout");
			GroupRow foldedHeader = (GroupRow) folded.getFirst();
			check(foldedHeader.group() == module.root && foldedHeader.collapsed()
				&& foldedHeader.toggle() != null,
				"folded group rows retain their toggle hitbox and collapsed state");

			ClickGuiState.toggleCollapsed(module, module.root);
			List<Row> expanded = ClickGuiSettingsLayout.layoutRows(
				module, 20, 10, module.accent, 200, 160);
			check(expanded.size() == 3 && expanded.get(0) instanceof GroupRow
				&& expanded.get(1) instanceof ColorRow && expanded.get(2) instanceof NumberRow,
				"expanded settings preserve declaration order while filtering hidden and debug-only entries");
			GroupRow header = (GroupRow) expanded.getFirst();
			ColorRow color = (ColorRow) expanded.get(1);
			NumberRow number = (NumberRow) expanded.get(2);
			check(!header.collapsed() && color.setting() == module.accent && color.picker() != null
				&& color.picker().alpha() == null && color.picker().hex() != null,
				"the opaque color picker preserves its square, hue and hex geometry without an alpha row");
			check(color.getBounds().x() == 20 && color.getBounds().w() == 200
				&& color.getBounds().h() == ClickGuiSettingsLayout.ROW_HEIGHT + ClickGuiSettingsLayout.PICKER_RGB_HEIGHT,
				"expanded picker height and settings-row bounds come from the Kotlin layout");
			check(number.field().w() == ClickGuiSettingsLayout.NUMBER_FIELD_WIDTH
				&& number.slider().h() == 4,
				"number rows retain their input field and slider geometry");

			DebugState.setEnabled(true);
			ClickGuiState.toggleCollapsed(module, module.debugGroup);
			List<Row> debugRows = ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160);
			check(debugRows.size() == 5 && debugRows.get(3) instanceof GroupRow
				&& debugRows.get(4) instanceof NumberRow,
				"debug-only nested settings remain available when diagnostic mode is enabled");
		} finally {
			DebugState.setEnabled(debugWasEnabled);
		}
	}

	private static void checkSettingsSearch() {
		SearchLayoutFixture module = new SearchLayoutFixture();
		boolean debugWasEnabled = DebugState.enabled();
		try {
			DebugState.setEnabled(false);
			check(ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "").size() == 1,
				"without a query a folded section keeps only its header");
			List<Row> beta = ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "beta");
			check(beta.size() == 2 && beta.get(0) instanceof GroupRow && beta.get(1) instanceof NumberRow
				&& ((NumberRow) beta.get(1)).setting().name().equals("Beta Setting"),
				"a query keeps the matching setting and its section header, even when the section is folded");
			List<Row> gamma = ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "gamma");
			check(gamma.size() == 3 && gamma.get(1) instanceof GroupRow && gamma.get(2) instanceof NumberRow,
				"a match in a nested folded section opens every ancestor");
			check(ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "nested").size() == 3,
				"a matching section name shows everything inside it");
			check(ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "primary").size() == 5,
				"a matching top section name shows its whole subtree");
			check(ClickGuiSettingsLayout.layoutRows(module, 20, 10, null, 200, 160, "zzz").isEmpty(),
				"a query without matches shows no rows");
		} finally {
			DebugState.setEnabled(debugWasEnabled);
		}
	}

	private static final class SearchLayoutFixture extends Module {
		private static final NumberSetting ALPHA = new NumberSetting("Alpha Setting", 0.0f, 10.0f, 5.0f);
		private static final NumberSetting BETA = new NumberSetting("Beta Setting", 0.0f, 10.0f, 5.0f);
		private static final NumberSetting GAMMA = new NumberSetting("Gamma Setting", 0.0f, 10.0f, 5.0f);

		private SearchLayoutFixture() {
			super("click-gui-search-fixture", "isolated search check", Category.DEV, ALPHA, BETA, GAMMA);
			SettingGroup nested = new SettingGroup("Nested", null, true, List.of(GAMMA), List.of());
			group(new SettingGroup("Primary", null, true, List.of(ALPHA, BETA), List.of(nested)));
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static final class CategoryCheckModule extends Module {
		private CategoryCheckModule(String name, Category category) {
			super(name, "category check", category);
		}
	}

	private static final class SettingsLayoutFixture extends Module {
		private static final BooleanSetting SECTION_TOGGLE = new BooleanSetting("section-toggle", true);
		private static final ColorSetting ACCENT = new ColorSetting("accent", 20, 120, 220);
		private static final NumberSetting VISIBLE_VALUE = new NumberSetting("visible-value", 0.0f, 10.0f, 5.0f);
		private static final NumberSetting HIDDEN_VALUE = new NumberSetting("hidden-value", 0.0f, 10.0f, 5.0f);
		private static final NumberSetting DEBUG_VALUE = new NumberSetting("debug-value", 0.0f, 10.0f, 5.0f);

		private final ColorSetting accent = ACCENT;
		private final SettingGroup debugGroup = SettingGroup.debug("Diagnostics", DEBUG_VALUE);
		private final SettingGroup root = new SettingGroup("Primary", SECTION_TOGGLE, true,
			List.of(ACCENT, VISIBLE_VALUE, HIDDEN_VALUE), List.of(debugGroup));

		private SettingsLayoutFixture() {
			super("click-gui-layout-fixture", "isolated row-layout check", Category.DEV,
				SECTION_TOGGLE, ACCENT, VISIBLE_VALUE, HIDDEN_VALUE, DEBUG_VALUE);
			group(root);
		}

		@Override
		public boolean isSettingVisible(Setting setting) {
			return !"hidden-value".equals(setting.name());
		}
	}
}
