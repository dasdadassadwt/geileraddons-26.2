package geiler.addons.client.dungeon.odin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Offline coverage for strict Tic Tac Toe board recognition, minimax, and room transforms. */
public final class DungeonTicTacToeRulesChecks {
	private DungeonTicTacToeRulesChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkJavaBoundary();
		checkImmediateWin();
		checkNecessaryBlock();
		checkDeterministicTie();
		checkTurnAndTerminalBoards();
		checkRejectedBoards();
		checkPredictionSequence();
		checkAmbiguousPrediction();
		checkRoomTransforms();
	}

	private static void checkJavaBoundary() {
		checkStatic("cellAt", DungeonTicTacToeRules.Cell.class, int.class, int.class, int.class);
		checkStatic("positionForCell", DungeonTicTacToeRules.Position.class, DungeonTicTacToeRules.Cell.class);
		checkStatic("toWorld", DungeonTicTacToeRules.Position.class, DungeonTicTacToeRules.Position.class,
			DungeonTicTacToeRules.Position.class, OdinDungeonRoomRotation.Rotation.class);
		checkStatic("toRoom", DungeonTicTacToeRules.Position.class, DungeonTicTacToeRules.Position.class,
			DungeonTicTacToeRules.Position.class, OdinDungeonRoomRotation.Rotation.class);
		checkStatic("worldPositionForCell", DungeonTicTacToeRules.Position.class,
			DungeonTicTacToeRules.Cell.class, DungeonTicTacToeRules.Position.class,
			OdinDungeonRoomRotation.Rotation.class);
		checkStatic("analyze", DungeonTicTacToeRules.Analysis.class, List.class);
		checkStatic("predict", DungeonTicTacToeRules.Prediction.class, List.class,
			DungeonTicTacToeRules.Analysis.class);
		checkRecord(DungeonTicTacToeRules.Cell.class, new String[] { "row", "column" }, int.class, int.class);
		checkRecord(DungeonTicTacToeRules.Position.class, new String[] { "x", "y", "z" },
			int.class, int.class, int.class);
		checkRecord(DungeonTicTacToeRules.Piece.class, new String[] { "x", "y", "z", "mapColor" },
			int.class, int.class, int.class, int.class);
		checkValueClass(DungeonTicTacToeRules.Analysis.class,
			new String[] { "status", "rejection", "observedPieces", "xCount", "oCount", "turn", "suggestedCell" },
			DungeonTicTacToeRules.Status.class, DungeonTicTacToeRules.Rejection.class,
			int.class, int.class, int.class, DungeonTicTacToeRules.Turn.class, DungeonTicTacToeRules.Cell.class);
		checkValueClass(DungeonTicTacToeRules.Prediction.class, new String[] { "status", "xReply", "nextOMove" },
			DungeonTicTacToeRules.PredictionStatus.class, DungeonTicTacToeRules.Cell.class,
			DungeonTicTacToeRules.Cell.class);
		checkNormalizedValues();
		require(java.util.Arrays.equals(DungeonTicTacToeRules.Status.values(),
			new DungeonTicTacToeRules.Status[] { DungeonTicTacToeRules.Status.READY,
				DungeonTicTacToeRules.Status.REJECTED, DungeonTicTacToeRules.Status.FINISHED,
				DungeonTicTacToeRules.Status.NOT_O_TURN }),
			"the solver retains Java enum ordering");
		try {
			Field xColor = DungeonTicTacToeRules.class.getDeclaredField("X_MAP_COLOR");
			Field oColor = DungeonTicTacToeRules.class.getDeclaredField("O_MAP_COLOR");
			require(Modifier.isStatic(xColor.getModifiers()) && Modifier.isFinal(xColor.getModifiers())
				&& xColor.getInt(null) == 114 && Modifier.isStatic(oColor.getModifiers())
				&& Modifier.isFinal(oColor.getModifiers()) && oColor.getInt(null) == 33,
				"the calibrated map colors retain their Java constants");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("the solver's Java color constants are preserved", exception);
		}
	}

	private static void checkStatic(String name, Class<?> result, Class<?>... parameters) {
		try {
			Method method = DungeonTicTacToeRules.class.getDeclaredMethod(name, parameters);
			require(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == result,
				"the solver retains its Java static method and return descriptor: " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the solver lost Java method " + name, exception);
		}
	}

	private static void checkRecord(Class<?> record, String[] names, Class<?>... types) {
		var components = record.getRecordComponents();
		require(record.isRecord() && components.length == names.length && components.length == types.length,
			record.getSimpleName() + " retains its Java record metadata");
		for (int index = 0; index < names.length; index++) {
			require(components[index].getName().equals(names[index]) && components[index].getType() == types[index],
				record.getSimpleName() + " retains record component " + index);
		}
	}

	private static void checkValueClass(Class<?> value, String[] names, Class<?>... types) {
		require(!value.isRecord(), value.getSimpleName() + " keeps constructor normalization without record metadata");
		try {
			var constructor = value.getConstructor(types);
			require(Modifier.isPublic(constructor.getModifiers()),
				value.getSimpleName() + " retains a public Java constructor");
			for (int index = 0; index < names.length; index++) {
				require(value.getMethod(names[index]).getReturnType() == types[index],
					value.getSimpleName() + " retains accessor " + names[index]);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(value.getSimpleName() + " Java value API changed", exception);
		}
	}

	private static void checkNormalizedValues() {
		var normalized = new DungeonTicTacToeRules.Analysis(null, null, -1, -2, 3, null,
			new DungeonTicTacToeRules.Cell(-1, 0));
		require(normalized.status() == DungeonTicTacToeRules.Status.REJECTED
			&& normalized.rejection() == DungeonTicTacToeRules.Rejection.UNAVAILABLE
			&& normalized.observedPieces() == 0 && normalized.xCount() == 0 && normalized.oCount() == 3
			&& normalized.turn() == DungeonTicTacToeRules.Turn.UNKNOWN && normalized.suggestedCell() == null,
			"Analysis retains null/default normalization, nonnegative counters, and invalid-cell removal");
		var analysis = new DungeonTicTacToeRules.Analysis(DungeonTicTacToeRules.Status.READY,
			DungeonTicTacToeRules.Rejection.NONE, 3, 2, 1, DungeonTicTacToeRules.Turn.O,
			new DungeonTicTacToeRules.Cell(0, 1));
		var equalAnalysis = new DungeonTicTacToeRules.Analysis(DungeonTicTacToeRules.Status.READY,
			DungeonTicTacToeRules.Rejection.NONE, 3, 2, 1, DungeonTicTacToeRules.Turn.O,
			new DungeonTicTacToeRules.Cell(0, 1));
		var expectedAnalysis = new ExpectedAnalysis(DungeonTicTacToeRules.Status.READY,
			DungeonTicTacToeRules.Rejection.NONE, 3, 2, 1, DungeonTicTacToeRules.Turn.O,
			new DungeonTicTacToeRules.Cell(0, 1));
		require(analysis.equals(equalAnalysis) && equalAnalysis.equals(analysis)
			&& analysis.hashCode() == expectedAnalysis.hashCode()
			&& analysis.toString().equals("Analysis[status=READY, rejection=NONE, observedPieces=3, xCount=2, "
				+ "oCount=1, turn=O, suggestedCell=Cell[row=0, column=1]]"),
			"Analysis retains record-style value equality, hash codes, and diagnostic text");

		var normalizedPrediction = new DungeonTicTacToeRules.Prediction(null,
			new DungeonTicTacToeRules.Cell(3, 0), new DungeonTicTacToeRules.Cell(0, 3));
		require(normalizedPrediction.status() == DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE
			&& normalizedPrediction.xReply() == null && normalizedPrediction.nextOMove() == null,
			"Prediction retains null status normalization and invalid-cell removal");
		var prediction = new DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.READY,
			new DungeonTicTacToeRules.Cell(1, 2), new DungeonTicTacToeRules.Cell(2, 0));
		var equalPrediction = new DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.READY,
			new DungeonTicTacToeRules.Cell(1, 2), new DungeonTicTacToeRules.Cell(2, 0));
		var expectedPrediction = new ExpectedPrediction(DungeonTicTacToeRules.PredictionStatus.READY,
			new DungeonTicTacToeRules.Cell(1, 2), new DungeonTicTacToeRules.Cell(2, 0));
		require(prediction.equals(equalPrediction) && equalPrediction.equals(prediction)
			&& prediction.hashCode() == expectedPrediction.hashCode()
			&& prediction.toString().equals("Prediction[status=READY, xReply=Cell[row=1, column=2], "
				+ "nextOMove=Cell[row=2, column=0]]"),
			"Prediction retains record-style value equality, hash codes, and diagnostic text");
	}

	private record ExpectedAnalysis(DungeonTicTacToeRules.Status status, DungeonTicTacToeRules.Rejection rejection,
		int observedPieces, int xCount, int oCount, DungeonTicTacToeRules.Turn turn,
		DungeonTicTacToeRules.Cell suggestedCell) { }

	private record ExpectedPrediction(DungeonTicTacToeRules.PredictionStatus status,
		DungeonTicTacToeRules.Cell xReply, DungeonTicTacToeRules.Cell nextOMove) { }

	private static void checkImmediateWin() {
		var analysis = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 1, 'X'), piece(2, 2, 'X'),
			piece(1, 0, 'O'), piece(1, 1, 'O')));
		require(analysis.status() == DungeonTicTacToeRules.Status.READY
			&& new DungeonTicTacToeRules.Cell(1, 2).equals(analysis.suggestedCell()),
			"O takes its immediate winning move");
	}

	private static void checkNecessaryBlock() {
		var analysis = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 1, 'X'), piece(2, 2, 'X'),
			piece(1, 1, 'O'), piece(2, 1, 'O')));
		require(analysis.status() == DungeonTicTacToeRules.Status.READY
			&& new DungeonTicTacToeRules.Cell(0, 2).equals(analysis.suggestedCell()),
			"O blocks X's only immediate winning threat");
	}

	private static void checkDeterministicTie() {
		var pieces = List.of(piece(1, 1, 'X'));
		var first = DungeonTicTacToeRules.analyze(pieces);
		var second = DungeonTicTacToeRules.analyze(pieces);
		require(first.suggestedCell() != null && first.suggestedCell().equals(second.suggestedCell())
			&& first.suggestedCell().equals(new DungeonTicTacToeRules.Cell(0, 0)),
			"equally scored moves use stable row-major tie breaking");
	}

	private static void checkTurnAndTerminalBoards() {
		var xTurn = DungeonTicTacToeRules.analyze(List.of());
		require(xTurn.status() == DungeonTicTacToeRules.Status.NOT_O_TURN
			&& xTurn.turn() == DungeonTicTacToeRules.Turn.X && xTurn.suggestedCell() == null,
			"the solver does not guide when X is to move");
		var xWin = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 1, 'X'), piece(0, 2, 'X'),
			piece(1, 0, 'O'), piece(1, 1, 'O')));
		require(xWin.status() == DungeonTicTacToeRules.Status.FINISHED
			&& xWin.suggestedCell() == null, "a valid winning board has no suggested move");
		var draw = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 1, 'O'), piece(0, 2, 'X'),
			piece(1, 0, 'X'), piece(1, 1, 'O'), piece(1, 2, 'O'),
			piece(2, 0, 'O'), piece(2, 1, 'X'), piece(2, 2, 'X')));
		require(draw.status() == DungeonTicTacToeRules.Status.FINISHED
			&& draw.suggestedCell() == null, "a full draw board has no suggested move");
	}

	private static void checkRejectedBoards() {
		var unknown = DungeonTicTacToeRules.analyze(List.of(new DungeonTicTacToeRules.Piece(8, 72, 17, 7)));
		require(unknown.rejection() == DungeonTicTacToeRules.Rejection.UNKNOWN_MARK
			&& unknown.suggestedCell() == null
			&& DungeonTicTacToeRules.predict(List.of(new DungeonTicTacToeRules.Piece(8, 72, 17, 7)), unknown).status()
				== DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE,
			"unknown map colors are rejected and cannot produce predictions");
		var duplicate = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 0, 'O')));
		require(duplicate.rejection() == DungeonTicTacToeRules.Rejection.DUPLICATE_SQUARE,
			"two observed pieces cannot claim one board square");
		var outOfRange = DungeonTicTacToeRules.analyze(List.of(
			new DungeonTicTacToeRules.Piece(9, 72, 17, DungeonTicTacToeRules.X_MAP_COLOR)));
		require(outOfRange.rejection() == DungeonTicTacToeRules.Rejection.OUT_OF_RANGE
			&& outOfRange.suggestedCell() == null && duplicate.suggestedCell() == null,
			"occupied frames outside the calibrated cells are rejected");
		var illegalCounts = DungeonTicTacToeRules.analyze(List.of(
			piece(0, 0, 'X'), piece(0, 1, 'X')));
		require(illegalCounts.rejection() == DungeonTicTacToeRules.Rejection.ILLEGAL_COUNTS,
			"illegal alternating X/O counts are rejected");
	}

	private static void checkPredictionSequence() {
		var xTurn = List.<DungeonTicTacToeRules.Piece>of();
		require(DungeonTicTacToeRules.predict(xTurn, DungeonTicTacToeRules.analyze(xTurn)).status()
			== DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE,
			"prediction is gated until a legal O turn");
		var board = findBoard(DungeonTicTacToeRules.PredictionStatus.READY);
		require(board != null, "at least one legal board has a unique perfect X reply");
		var analysis = DungeonTicTacToeRules.analyze(board);
		var first = DungeonTicTacToeRules.predict(board, analysis);
		var second = DungeonTicTacToeRules.predict(board, analysis);
		require(first.status() == DungeonTicTacToeRules.PredictionStatus.READY
			&& first.xReply() != null && first.nextOMove() != null
			&& first.xReply().equals(second.xReply()) && first.nextOMove().equals(second.nextOMove()),
			"unique perfect X reply and deterministic next O move are predicted");

		var immediateWin = List.of(piece(0, 0, 'X'), piece(0, 1, 'X'), piece(2, 2, 'X'),
			piece(1, 0, 'O'), piece(1, 1, 'O'));
		var winAnalysis = DungeonTicTacToeRules.analyze(immediateWin);
		var terminal = DungeonTicTacToeRules.predict(immediateWin, winAnalysis);
		require(terminal.status() == DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_O
			&& terminal.xReply() == null && terminal.nextOMove() == null,
			"an immediate O win has no hypothetical machine reply");
		var machineWinBoard = findBoard(DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_X);
		require(machineWinBoard != null, "a unique winning X reply is representable");
		var machineWin = DungeonTicTacToeRules.predict(machineWinBoard,
			DungeonTicTacToeRules.analyze(machineWinBoard));
		require(machineWin.xReply() != null && machineWin.nextOMove() == null,
			"when X ends the game, prediction shows no follow-up O move");
	}

	private static void checkAmbiguousPrediction() {
		require(findBoard(DungeonTicTacToeRules.PredictionStatus.AMBIGUOUS_X_REPLY) != null,
			"a board with tied perfect machine replies suppresses prediction");
	}

	private static List<DungeonTicTacToeRules.Piece> findBoard(DungeonTicTacToeRules.PredictionStatus wanted) {
		int boards = 1;
		for (int cell = 0; cell < 9; cell++) boards *= 3;
		for (int encoded = 0; encoded < boards; encoded++) {
			int state = encoded;
			java.util.ArrayList<DungeonTicTacToeRules.Piece> pieces = new java.util.ArrayList<>(9);
			for (int cell = 0; cell < 9; cell++) {
				int mark = state % 3;
				state /= 3;
				if (mark == 1) pieces.add(piece(cell / 3, cell % 3, 'X'));
				else if (mark == 2) pieces.add(piece(cell / 3, cell % 3, 'O'));
			}
			var analysis = DungeonTicTacToeRules.analyze(pieces);
			if (analysis.status() != DungeonTicTacToeRules.Status.READY) continue;
			if (DungeonTicTacToeRules.predict(pieces, analysis).status() == wanted) return List.copyOf(pieces);
		}
		return null;
	}

	private static void checkRoomTransforms() {
		var clay = new DungeonTicTacToeRules.Position(101, 64, -203);
		for (OdinDungeonRoomRotation.Rotation rotation : OdinDungeonRoomRotation.Rotation.values()) {
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < 3; column++) {
					var cell = new DungeonTicTacToeRules.Cell(row, column);
					var room = DungeonTicTacToeRules.positionForCell(cell);
					var world = DungeonTicTacToeRules.toWorld(room, clay, rotation);
					var observedRoom = DungeonTicTacToeRules.toRoom(world, clay, rotation);
					require(room.equals(observedRoom), "Tic Tac Toe cell survives " + rotation + " room transform");
					require(cell.equals(DungeonTicTacToeRules.cellAt(observedRoom.x(), observedRoom.y(), observedRoom.z())),
						"transformed frame resolves to its original cell for " + rotation);
				}
			}
		}
	}

	private static DungeonTicTacToeRules.Piece piece(int row, int column, char mark) {
		var position = DungeonTicTacToeRules.positionForCell(new DungeonTicTacToeRules.Cell(row, column));
		int color = mark == 'X' ? DungeonTicTacToeRules.X_MAP_COLOR : DungeonTicTacToeRules.O_MAP_COLOR;
		return new DungeonTicTacToeRules.Piece(position.x(), position.y(), position.z(), color);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
