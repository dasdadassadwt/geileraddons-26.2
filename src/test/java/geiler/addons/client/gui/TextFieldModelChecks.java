package geiler.addons.client.gui;

/** Offline checks for the shared text field model: selection, undo/redo, word editing, clicks. */
public final class TextFieldModelChecks {
	private TextFieldModelChecks() { }

	public static void run() {
		checkWords();
		checkTypingAndUndo();
		checkPasteCutSelectAll();
		checkWordKeys();
		checkClicksAndDrag();
		checkFilterAndLimit();
		checkClickCounter();
		checkExternalSet();
		checkAdopt();
		checkEditBoxHistory();
	}

	private static void checkAdopt() {
		TextFieldModel m = new TextFieldModel(64, null, 100);
		m.adopt("abc", 3, -1);
		m.type("d");
		check(m.getText().equals("abcd"), "an adopted field accepts typing");
		m.adopt("abcd", 1, -1);
		check(m.getText().equals("abcd") && m.getCursor() == 1, "adopting the same text keeps the history and moves the caret");
		check(m.undo() && m.getText().equals("abc"), "history survives an adopt of unchanged text");
		m.adopt("zzz", 0, 2);
		check(m.getText().equals("zzz") && m.getAnchor() == 2 && !m.undo(), "adopting different text starts a new history");
		TextFieldModel loose = new TextFieldModel(2, null, 100);
		loose.adopt("abcdef", 6, -1);
		check(loose.getText().equals("abcdef"), "adopt never truncates the field's own text");
	}

	private static void checkEditBoxHistory() {
		EditBoxUndo undo = new EditBoxUndo();
		undo.changed("", 0, 0, false, true, 0L);
		undo.changed("h", 1, 1, true, false, 10L);
		undo.changed("hi", 2, 2, true, false, 20L);
		check(undo.undo() && undo.getText().isEmpty(), "typed characters in a row undo together");
		check(undo.redo() && undo.getText().equals("hi") && undo.getCursor() == 2, "redo brings the typed text back");
		undo.changed("hi there", 8, 8, false, false, 30L);
		check(undo.undo() && undo.getText().equals("hi"), "a paste is its own step");
		undo.changed("hix", 3, 3, true, false, 5000L);
		undo.changed("hixy", 4, 4, true, false, 9000L);
		check(undo.undo() && undo.getText().equals("hix"), "a long pause splits typing into two steps");
		undo.setRestoring(true);
		undo.changed("ignored", 7, 7, false, false, 9100L);
		undo.setRestoring(false);
		check(undo.getText().equals("hix"), "changes made while restoring are not recorded");
		undo.changed("fresh", 5, 5, false, true, 9200L);
		check(!undo.undo(), "a programmatic setValue clears the history");
	}

	private static TextFieldModel model(String text) {
		TextFieldModel model = new TextFieldModel(64, null, 100);
		model.reset(text);
		return model;
	}

	private static void type(TextFieldModel model, String chars) {
		for (int i = 0; i < chars.length(); i++) model.type(String.valueOf(chars.charAt(i)));
	}

	private static void checkWords() {
		check(ClickGuiTextEditing.wordLeft("foo bar", 7) == 4, "word left from the end lands at the word start");
		check(ClickGuiTextEditing.wordLeft("foo bar", 4) == 0, "word left skips the space before the cursor");
		check(ClickGuiTextEditing.wordLeft("foo bar", 0) == 0, "word left at the start stays");
		check(ClickGuiTextEditing.wordRight("foo bar", 0) == 4, "word right skips the word and its trailing space");
		check(ClickGuiTextEditing.wordRight("foo bar", 7) == 7, "word right at the end stays");
		check(ClickGuiTextEditing.wordLeft("a.b", 3) == 2, "punctuation is its own word class");
		check(ClickGuiTextEditing.wordLeft("a😀", 3) == 1, "a surrogate pair moves as one character");
		ClickGuiTextEditing.Selection word = ClickGuiTextEditing.wordRangeAt("foo bar", 5);
		check(word.getStart() == 4 && word.getEnd() == 7, "word range covers the word under the index");
		ClickGuiTextEditing.Selection end = ClickGuiTextEditing.wordRangeAt("foo bar", 7);
		check(end.getStart() == 4 && end.getEnd() == 7, "word range at the end uses the last word");
		ClickGuiTextEditing.Selection space = ClickGuiTextEditing.wordRangeAt("foo  bar", 4);
		check(space.getStart() == 3 && space.getEnd() == 5, "word range on spaces selects the space run");
		check(ClickGuiTextEditing.wordRangeAt("", 0).isEmpty(), "word range of empty text is empty");
	}

	private static void checkTypingAndUndo() {
		TextFieldModel m = model("");
		type(m, "abc");
		check(m.getText().equals("abc") && m.getCursor() == 3, "typing appends");
		check(m.undo() && m.getText().isEmpty(), "consecutive typing undoes as one step");
		check(!m.undo(), "nothing left to undo");
		check(m.redo() && m.getText().equals("abc") && m.getCursor() == 3, "redo restores text and cursor");
		check(!m.redo(), "nothing left to redo");
		m.undo();
		type(m, "x");
		check(!m.redo(), "a new edit clears the redo branch");

		TextFieldModel n = model("");
		type(n, "ab");
		n.key(ClickGuiTextEditKey.LEFT, false, false);
		type(n, "c");
		check(n.getText().equals("acb"), "typing after a caret move inserts at the caret");
		check(n.undo() && n.getText().equals("ab"), "a caret move breaks undo coalescing");
		check(n.undo() && n.getText().isEmpty(), "the earlier typing undoes separately");

		TextFieldModel b = model("hello");
		b.key(ClickGuiTextEditKey.BACKSPACE, false, false);
		check(b.getText().equals("hell"), "backspace deletes before the caret");
		check(b.undo() && b.getText().equals("hello") && b.getCursor() == 5, "backspace undoes with its caret");
	}

	private static void checkPasteCutSelectAll() {
		TextFieldModel m = model("hello world");
		m.selectAll();
		check(m.copy().equals("hello world"), "copy returns the selection");
		check(m.getText().equals("hello world") && m.hasSelection(), "copy keeps the selection");
		check(m.cut().equals("hello world") && m.getText().isEmpty(), "cut removes and returns the selection");
		check(m.undo() && m.getText().equals("hello world"), "cut undoes");
		m.selectAll();
		check(m.paste("a\r\nb") && m.getText().equals("a b"), "paste replaces the selection with one line text");
		check(m.undo() && m.getText().equals("hello world"), "paste is its own undo step");

		TextFieldModel empty = model("");
		empty.selectAll();
		check(!empty.hasSelection() && empty.copy().isEmpty(), "select all on empty text selects nothing");

		TextFieldModel none = model("abc");
		check(none.cut() == null && none.getText().equals("abc"), "cut without selection does nothing");
	}

	private static void checkWordKeys() {
		TextFieldModel m = model("foo bar baz");
		m.key(ClickGuiTextEditKey.LEFT, true, false);
		check(m.getCursor() == 8, "ctrl+left jumps one word");
		m.key(ClickGuiTextEditKey.LEFT, true, true);
		check(m.getCursor() == 4 && m.getAnchor() == 8, "ctrl+shift+left extends the selection by a word");
		m.key(ClickGuiTextEditKey.RIGHT, true, false);
		check(m.getAnchor() < 0 && m.getCursor() == 8, "ctrl+right without shift drops the selection");

		TextFieldModel d = model("foo bar");
		check(d.key(ClickGuiTextEditKey.BACKSPACE, true, false) && d.getText().equals("foo "), "ctrl+backspace deletes a word");
		check(d.key(ClickGuiTextEditKey.BACKSPACE, true, false) && d.getText().isEmpty(), "ctrl+backspace skips spaces then deletes");
		check(d.undo() && d.getText().equals("foo "), "word delete undoes");

		TextFieldModel f = model("foo bar");
		f.key(ClickGuiTextEditKey.HOME, false, false);
		check(f.key(ClickGuiTextEditKey.DELETE, true, false) && f.getText().equals("bar"), "ctrl+delete deletes the next word");

		TextFieldModel s = model("abc");
		s.selectAll();
		check(s.key(ClickGuiTextEditKey.BACKSPACE, true, false) && s.getText().isEmpty(), "ctrl+backspace deletes a selection first");
	}

	private static void checkClicksAndDrag() {
		TextFieldModel m = model("foo bar");
		m.click(5, 1, false);
		check(m.getCursor() == 5 && m.getAnchor() < 0, "single click places the caret");
		m.click(5, 2, false);
		check(m.getAnchor() == 4 && m.getCursor() == 7, "double click selects a word");
		m.click(5, 3, false);
		check(m.getAnchor() == 0 && m.getCursor() == 7, "triple click selects everything");
		m.click(2, 1, false);
		m.drag(6);
		check(m.getAnchor() == 2 && m.getCursor() == 6, "drag keeps the click as anchor");
		m.drag(2);
		check(m.getAnchor() < 0 && m.getCursor() == 2, "dragging back to the anchor clears the selection");
		m.click(1, 1, false);
		m.click(5, 1, true);
		check(m.getAnchor() == 1 && m.getCursor() == 5, "shift click extends from the caret");
	}

	private static void checkFilterAndLimit() {
		TextFieldModel hex = new TextFieldModel(8, s -> s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(java.util.Locale.ROOT), 100);
		hex.reset("");
		hex.type("g");
		check(hex.getText().isEmpty(), "the filter rejects characters");
		hex.type("ab");
		check(hex.getText().equals("AB"), "the filter normalizes characters");
		hex.paste("0123456789");
		check(hex.getText().length() == 8, "paste is cut to the max length");

		TextFieldModel limited = new TextFieldModel(3, null, 100);
		limited.reset("");
		type(limited, "abcdef");
		check(limited.getText().equals("abc"), "typing stops at the max length");
	}

	private static void checkClickCounter() {
		TextFieldModel.ClickCounter counter = new TextFieldModel.ClickCounter(400L, 4);
		check(counter.register(0L, 10, 10) == 1, "first click counts one");
		check(counter.register(200L, 11, 10) == 2, "a quick close click counts two");
		check(counter.register(350L, 11, 11) == 3, "a third quick click counts three");
		check(counter.register(500L, 11, 11) == 3, "a fourth click stays at three within the window");
		check(counter.register(1500L, 11, 11) == 1, "a slow click restarts");
		counter.register(1600L, 11, 11);
		check(counter.register(1700L, 40, 11) == 1, "a far click restarts");
	}

	private static void checkExternalSet() {
		TextFieldModel m = model("old");
		type(m, "er");
		m.reset("fresh");
		check(m.getText().equals("fresh") && m.getCursor() == 5 && !m.undo(), "reset clears history and moves the caret to the end");
		m.replaceAll("");
		check(m.getText().isEmpty() && m.undo() && m.getText().equals("fresh"), "replaceAll is an undoable edit");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("TextFieldModelChecks: " + message);
	}
}
