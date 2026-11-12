package geiler.addons.client.gui;

/** Offline checks for selection, replacement, copy, cut, and paste in Click GUI text fields. */
public final class ClickGuiTextSelectionChecks {
	private ClickGuiTextSelectionChecks() { }

	public static void run() {
		checkSelectionRange();
		checkTypingReplacesSelection();
		checkKeys();
		checkClipboardHelpers();
		checkMousePlacement();
	}

	private static void checkSelectionRange() {
		ClickGuiTextEditing.Selection none = ClickGuiTextEditing.selection("hello", 3, -1);
		check(none.isEmpty() && ClickGuiTextEditing.selectedText("hello", 3, -1).isEmpty(),
			"no anchor means no selection");
		check(ClickGuiTextEditing.selection("hello", 3, 3).isEmpty(), "an anchor equal to the cursor is empty");
		ClickGuiTextEditing.Selection backwards = ClickGuiTextEditing.selection("hello", 1, 4);
		check(backwards.getStart() == 1 && backwards.getEnd() == 4
			&& ClickGuiTextEditing.selectedText("hello", 1, 4).equals("ell"),
			"a selection made right to left spans the same characters");
		ClickGuiTextEditing.Selection clamped = ClickGuiTextEditing.selection("hi", 0, 99);
		check(clamped.getStart() == 0 && clamped.getEnd() == 2, "an out-of-range anchor is clamped");
		ClickGuiTextEditResult all = ClickGuiTextEditing.selectAllState("héllo");
		check(all.getCursor() == 5 && all.getAnchor() == 0, "select all anchors at the start and moves the cursor to the end");
	}

	private static void checkTypingReplacesSelection() {
		ClickGuiTextEditResult typed = ClickGuiTextEditing.insertReplacingSelection("hello", 4, 1, "i", 64);
		check(typed.getText().equals("hio") && typed.getCursor() == 2 && typed.getAnchor() == -1,
			"typing over a selection replaces it and collapses the caret after the new text");
		ClickGuiTextEditResult plain = ClickGuiTextEditing.insertReplacingSelection("ab", 1, -1, "XY", 64);
		check(plain.getText().equals("aXYb") && plain.getCursor() == 3, "typing without a selection inserts at the caret");
		ClickGuiTextEditResult bounded = ClickGuiTextEditing.insertReplacingSelection("abc", 3, -1, "defgh", 5);
		check(bounded.getText().equals("abcde") && bounded.getCursor() == 5,
			"insertion is cut to the field's length limit");
		ClickGuiTextEditResult replaceAllBounded = ClickGuiTextEditing.insertReplacingSelection("abcde", 5, 0, "xyz", 5);
		check(replaceAllBounded.getText().equals("xyz"), "the length limit counts only text that remains after the replace");
	}

	private static void checkKeys() {
		ClickGuiTextEditResult back = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 4, 1, ClickGuiTextEditKey.BACKSPACE, false);
		check(back.getText().equals("ho") && back.getCursor() == 1 && back.getAnchor() == -1,
			"backspace deletes the selection instead of one character");
		ClickGuiTextEditResult left = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 4, 1, ClickGuiTextEditKey.LEFT, false);
		check(left.getCursor() == 1 && left.getAnchor() == -1 && left.getText().equals("hello"),
			"left collapses a selection to its start");
		ClickGuiTextEditResult right = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 1, 4, ClickGuiTextEditKey.RIGHT, false);
		check(right.getCursor() == 4 && right.getAnchor() == -1, "right collapses a selection to its end");
		ClickGuiTextEditResult extend = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 2, -1, ClickGuiTextEditKey.RIGHT, true);
		check(extend.getCursor() == 3 && extend.getAnchor() == 2, "shift plus right starts a selection at the old caret");
		ClickGuiTextEditResult extendMore = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", extend.getCursor(), extend.getAnchor(), ClickGuiTextEditKey.END, true);
		check(extendMore.getCursor() == 5 && extendMore.getAnchor() == 2, "shift plus end keeps the anchor");
		ClickGuiTextEditResult home = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 3, -1, ClickGuiTextEditKey.HOME, false);
		check(home.getCursor() == 0 && home.getAnchor() == -1, "home without shift just moves the caret");
		ClickGuiTextEditResult del = ClickGuiTextEditing.applyKeyWithSelection(
			"hello", 2, -1, ClickGuiTextEditKey.DELETE, false);
		check(del.getText().equals("helo") && del.getCursor() == 2, "delete without a selection removes the next character");
	}

	private static void checkClipboardHelpers() {
		check(ClickGuiTextEditing.sanitizePaste("a\r\nb\tc\u0007").equals("a b c"),
			"pasted line breaks and tabs become spaces and other control characters are dropped");
		check(ClickGuiTextEditing.sanitizePaste(null).isEmpty(), "a null clipboard pastes nothing");
		ClickGuiTextEditResult cut = ClickGuiTextEditing.deleteSelection("hello", 1, 4);
		check(cut.getText().equals("ho") && cut.getCursor() == 1 && cut.getAnchor() == -1,
			"cut removes the selection and leaves the caret where it began");
		ClickGuiTextEditResult nothing = ClickGuiTextEditing.deleteSelection("hello", 2, -1);
		check(nothing.getText().equals("hello") && nothing.getCursor() == 2, "cut without a selection changes nothing");
	}

	private static void checkMousePlacement() {
		check(ClickGuiTextEditing.dragAnchor(3, -1) == 3 && ClickGuiTextEditing.dragAnchor(3, 1) == 1,
			"a drag keeps its first anchor and otherwise anchors at the click caret");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ClickGuiTextSelectionChecks: " + message);
	}
}
