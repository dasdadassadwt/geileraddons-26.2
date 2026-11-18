package geiler.addons.client.dungeon.terminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Terminal Solver's "Analyse Terms" recording: one valid JSON line per hand-solved terminal with the cursor path, every
 * click (slot sent, slot under the cursor, accepted or blocked) and the confirmations, bounded in size and in samples, and
 * numbered per terminal type.
 */
public final class TerminalAnalysisChecks {
	private TerminalAnalysisChecks() { }

	private static final long MS = 1_000_000L;

	public static void run() throws IOException {
		checkRunLine();
		checkRawStream();
		checkBounds();
		checkEmptyRun();
		checkLog();
	}

	private static TerminalRunRecorder recorder(AtomicLong clock, String type, String title, int number) {
		TerminalRunRecorder recorder = new TerminalRunRecorder(type, title, "sim", number,
			new TerminalRunRecorder.Settings(4, 1920, 1080, 144, false, 1.8f, 500), clock::get);
		recorder.board(List.of());
		recorder.board(List.of(10));
		recorder.board(List.of(10, 12, 14));
		recorder.grid(new TerminalRunRecorder.Grid(300f, 200f, 1.8f, 24, 0, 3, 5, 1, 2));
		return recorder;
	}

	private static void checkRunLine() {
		AtomicLong clock = new AtomicLong(5_000L * MS);
		TerminalRunRecorder run = recorder(clock, "PANES", "Correct \"all\" the panes!", 7);
		clock.addAndGet(100 * MS);
		run.cursor(960.0, 540.0);
		run.cursor(960.0, 540.0);
		clock.addAndGet(20 * MS);
		run.cursor(980.5, 541.0);
		clock.addAndGet(300 * MS);
		run.click(10, 10, 1000.0, 545.0, 0, 2, true, 3, -1, "mouse");
		run.confirm(10);
		clock.addAndGet(400 * MS);
		run.click(11, 11, 1100.0, 600.0, 0, 2, false, 2, 12, "key");
		clock.addAndGet(500 * MS);
		String line = run.finish("completed", 18);
		check(line != null && !line.contains("\n"), "a run is one line");
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();
		check(json.get("k").getAsString().equals("run") && json.get("type").getAsString().equals("PANES"), "it has its kind and terminal type");
		check(!json.get("auto").getAsBoolean(), "a run of hand clicks is not marked auto");
		check(json.get("src").getAsString().equals("sim") && json.get("n").getAsInt() == 7, "the source and the run number");
		check(json.get("title").getAsString().equals("Correct \"all\" the panes!"), "a title with quotes survives");
		check(json.get("guiScale").getAsInt() == 4 && json.getAsJsonArray("win").get(0).getAsInt() == 1920, "the gui scale and window size map pixels to slots");
		check(json.getAsJsonObject("grid").get("rows").getAsInt() == 3 && json.getAsJsonObject("grid").get("col0").getAsInt() == 2, "and so does the grid");
		check(json.getAsJsonArray("board").size() == 3 && json.get("boardMs").getAsLong() == 0L, "the board is the last solution before the first click, with the time it first appeared");

		JsonArray path = json.getAsJsonArray("path");
		check(path.size() == 2, "an unchanged cursor is not sampled twice (" + path.size() + ")");
		check(path.get(0).getAsJsonArray().get(0).getAsLong() == 100L, "path times are ms since the terminal opened");
		JsonArray clicks = json.getAsJsonArray("clicks");
		check(clicks.size() == 2, "every click is kept");
		JsonObject first = clicks.get(0).getAsJsonObject();
		check(first.get("t").getAsLong() == 420L && first.get("slot").getAsInt() == 10 && first.get("hover").getAsInt() == 10, "a click has its time, slot sent and slot hovered");
		check(first.get("ok").getAsBoolean() && first.get("left").getAsInt() == 3 && first.get("src").getAsString().equals("mouse"), "accepted, the solution left and the source");
		JsonObject second = clicks.get(1).getAsJsonObject();
		check(!second.get("ok").getAsBoolean() && second.get("exp").getAsInt() == 12 && second.get("src").getAsString().equals("key"), "a blocked click is kept with its expected slot");
		check(json.getAsJsonArray("confirms").size() == 1 && json.getAsJsonArray("confirms").get(0).getAsJsonArray().get(1).getAsInt() == 10, "a confirmation has its slot");
		JsonObject end = json.getAsJsonObject("end");
		check(end.get("reason").getAsString().equals("completed") && end.get("ms").getAsLong() == 1320L && end.get("ticks").getAsInt() == 18, "the end has reason, total time and ticks");
	}

	private static void checkRawStream() {
		AtomicLong clock = new AtomicLong(1_000L * MS);
		TerminalRunRecorder run = recorder(clock, "MELODY", "Click the button on the end!", 2);
		run.cursor(10.0, 10.0);
		clock.addAndGet(5 * MS);
		run.move(100.0, 200.0);
		run.move(100.0, 200.0);
		clock.addAndGet(1 * MS);
		run.move(101.25, 199.5);
		run.cursor(500.0, 500.0);
		run.target(25);
		run.target(25);
		clock.addAndGet(300 * MS);
		run.target(34);
		run.button(0, true);
		clock.addAndGet(60 * MS);
		run.button(0, false);
		run.click(34, 34, 101.0, 199.0, 0, 2, true, 4, -1, "mouse");
		check(run.getRawMoves() == 2, "an unchanged raw position is not kept twice");
		JsonObject json = JsonParser.parseString(run.finish("completed", 1)).getAsJsonObject();
		check(json.getAsJsonArray("path").size() == 1, "once raw events arrive the per-frame samples are dropped");
		JsonArray moves = json.getAsJsonArray("moves");
		check(moves.size() == 2 && Math.abs(moves.get(0).getAsJsonArray().get(0).getAsDouble() - 5.0) < 1e-6, "raw moves keep their time in ms");
		check(Math.abs(moves.get(1).getAsJsonArray().get(1).getAsDouble() - 101.25) < 0.1, "and their GUI position");
		check(json.getAsJsonArray("targets").size() == 2, "only a change of target is kept");
		JsonArray buttons = json.getAsJsonArray("buttons");
		check(buttons.size() == 2 && buttons.get(0).getAsJsonArray().get(2).getAsInt() == 1
			&& buttons.get(1).getAsJsonArray().get(2).getAsInt() == 0, "a button press and release are kept in order");
		check(!json.get("movesTruncated").getAsBoolean(), "a short run is not truncated");

		TerminalRunRecorder many = recorder(clock, "PANES", "x", 1);
		for (int i = 0; i < 40_000; i++) many.move(i, i);
		many.click(10, 10, 0.0, 0.0, 0, 2, true, 3, -1, "mouse");
		JsonObject capped = JsonParser.parseString(many.finish("completed", 1)).getAsJsonObject();
		check(capped.getAsJsonArray("moves").size() == 30_000 && capped.get("movesTruncated").getAsBoolean(), "raw moves are capped");
	}

	private static void checkBounds() {
		AtomicLong clock = new AtomicLong(0L);
		TerminalRunRecorder run = recorder(clock, "RUBIX", "Change all to same color!", 1);
		for (int i = 0; i < 20_000; i++) {
			clock.addAndGet(MS);
			run.cursor(i, i);
		}
		for (int i = 0; i < 500; i++) run.click(i % 9, i % 9, 1.0, 1.0, 0, 2, true, 1, -1, "mouse");
		JsonObject json = JsonParser.parseString(run.finish("closed", 0)).getAsJsonObject();
		check(json.getAsJsonArray("path").size() <= 8_000 && json.get("pathTruncated").getAsBoolean(), "the cursor path is capped and says so");
		check(json.getAsJsonArray("clicks").size() <= 300 && json.get("clicksTruncated").getAsBoolean(), "the clicks are capped and say so");
	}

	private static void checkEmptyRun() {
		TerminalRunRecorder run = recorder(new AtomicLong(0L), "NUMBERS", "Click in order!", 1);
		run.cursor(1.0, 2.0);
		check(run.finish("closed", 3) == null, "a terminal closed without a click writes nothing");
	}

	private static void checkLog() throws IOException {
		Path dir = Files.createTempDirectory("terminal-analysis-checks");
		try {
			try (TerminalAnalysisLog log = new TerminalAnalysisLog(dir, "s", 200_000L)) {
				check(log.nextNumber("PANES") == 1 && log.nextNumber("PANES") == 1, "the number only moves on once a run is written");
				AtomicLong clock = new AtomicLong(0L);
				for (int i = 0; i < 3; i++) {
					TerminalRunRecorder run = recorder(clock, "PANES", "Correct all the panes!", log.nextNumber("PANES"));
					run.click(10, 10, 1.0, 1.0, 0, 2, true, 1, -1, "mouse");
					log.write("PANES", run.finish("completed", 5));
				}
				check(log.nextNumber("PANES") == 4 && log.nextNumber("RUBIX") == 1, "runs are numbered per terminal type");
				check(log.runs() == 3 && log.bytes() > 0, "and counted");
				for (int i = 0; i < 400; i++) {
					TerminalRunRecorder run = recorder(clock, "PANES", "Correct all the panes!", 99);
					for (int c = 0; c < 20; c++) run.click(10, 10, 1.0, 1.0, 0, 2, true, 1, -1, "mouse");
					log.write("PANES", run.finish("completed", 5));
				}
				check(log.bytes() <= 200_000L, "the file stays within its bound (" + log.bytes() + ")");
			}
			List<Path> files;
			try (Stream<Path> walk = Files.list(dir)) {
				files = walk.sorted().collect(Collectors.toList());
			}
			check(!files.isEmpty() && files.size() <= 2, "at most two segments are kept");
			for (Path file : files) {
				List<String> lines = Files.readAllLines(file);
				check(JsonParser.parseString(lines.get(0)).getAsJsonObject().get("k").getAsString().equals("meta"), "every segment starts with a header");
				for (String line : lines) JsonParser.parseString(line);
			}
		} finally {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
