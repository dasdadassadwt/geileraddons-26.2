package geiler.addons.client.gui;

import java.util.List;

/** Offline checks for the Classic Click GUI's column geometry and its style setting. */
public final class ClickGuiClassicChecks {
	private ClickGuiClassicChecks() { }

	public static void run() {
		check(ClickGuiStyle.fromSetting("Classic") == ClickGuiStyle.CLASSIC && ClickGuiStyle.fromSetting("Modern") == ClickGuiStyle.MODERN
			&& ClickGuiStyle.fromSetting(null) == ClickGuiStyle.MODERN && ClickGuiStyle.fromSetting("x") == ClickGuiStyle.MODERN,
			"only the saved value Classic selects the Classic menu");

		ClickGuiClassicLayout.Plan wide = ClickGuiClassicLayout.plan(960, 540, 5);
		check(wide.getColumns().size() == 5 && wide.getColumnWidth() == ClickGuiClassicLayout.MAX_COLUMN_WIDTH,
			"five columns on a wide screen use the maximum width in one row");
		List<ClickGuiClassicLayout.Column> columns = wide.getColumns();
		check(columns.stream().allMatch(c -> c.getY() == ClickGuiClassicLayout.MARGIN), "one row of columns shares the top edge");
		int left = columns.get(0).getX();
		int right = columns.get(4).getX() + wide.getColumnWidth();
		check(Math.abs(left - (960 - right)) <= 1, "a row of columns is centred");
		check(columns.get(1).getX() - (columns.get(0).getX() + wide.getColumnWidth()) == ClickGuiClassicLayout.GAP, "columns are one gap apart");

		ClickGuiClassicLayout.Plan nine = ClickGuiClassicLayout.plan(960, 540, 9);
		check(nine.getColumns().stream().map(c -> c.getY()).distinct().count() == 1 && nine.getColumnWidth() >= ClickGuiClassicLayout.MIN_COLUMN_WIDTH,
			"nine columns shrink to fit one row when they can");

		ClickGuiClassicLayout.Plan narrow = ClickGuiClassicLayout.plan(320, 400, 9);
		long rows = narrow.getColumns().stream().map(c -> c.getY()).distinct().count();
		check(rows > 1 && narrow.getColumnWidth() == ClickGuiClassicLayout.MIN_COLUMN_WIDTH, "a narrow screen wraps columns onto more rows");
		check(narrow.getColumns().stream().allMatch(c -> c.getX() >= 0 && c.getX() + c.getWidth() <= 320), "wrapped columns stay on screen");
		check(narrow.getColumns().stream().allMatch(c -> c.getY() + ClickGuiClassicLayout.TITLE_HEIGHT <= 400 - ClickGuiClassicLayout.BOTTOM_BAR_HEIGHT),
			"wrapped rows leave room for the search bar");
		check(ClickGuiClassicLayout.plan(960, 540, 0).getColumns().isEmpty(), "no categories give no columns");

		check(ClickGuiClassicLayout.listHeight(3, 200) == 3 * ClickGuiClassicLayout.ROW_HEIGHT
			&& ClickGuiClassicLayout.listHeight(100, 200) == 200 && ClickGuiClassicLayout.listHeight(-1, 200) == 0,
			"a module list grows with its rows up to the column's room");
		check(ClickGuiClassicLayout.maxScroll(10, 80) == 10 * ClickGuiClassicLayout.ROW_HEIGHT - 80
			&& ClickGuiClassicLayout.maxScroll(2, 80) == 0, "only a list taller than its view scrolls");
		int top = 50;
		int h = ClickGuiClassicLayout.ROW_HEIGHT;
		check(ClickGuiClassicLayout.rowAt(top + 1, top, 0, 5, 5 * h) == 0 && ClickGuiClassicLayout.rowAt(top + h, top, 0, 5, 5 * h) == 1
			&& ClickGuiClassicLayout.rowAt(top + 1, top, h, 5, 3 * h) == 1 && ClickGuiClassicLayout.rowAt(top - 1, top, 0, 5, 5 * h) == -1
			&& ClickGuiClassicLayout.rowAt(top + 5 * h, top, 0, 5, 5 * h) == -1 && ClickGuiClassicLayout.rowAt(top + 3 * h, top, 0, 5, 3 * h) == -1,
			"a click maps to the row under it, scroll included, and nothing outside the list");

		check(KeybindCapture.HINT.contains("Esc"), "the keybind hint names the Escape key");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
