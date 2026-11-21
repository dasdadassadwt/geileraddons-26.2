package geiler.addons.client.dungeon.helpers;

import java.util.ArrayList;
import java.util.List;

/** Replays a board through O's move and X's slow reply: the prediction must not vanish in between. */
public final class DungeonTicTacToeTargetHoldChecks {
	private DungeonTicTacToeTargetHoldChecks() { }

	public static void run() {
		var hold = new DungeonTicTacToeTargetHold();
		// X opened in the centre; O to move.
		List<DungeonTicTacToeRules.Piece> start = board("X", 1, 1);
		var ready = DungeonTicTacToeRules.analyze(start);
		require(ready.status() == DungeonTicTacToeRules.Status.READY, "opening board is O's turn");
		var prediction = DungeonTicTacToeRules.predict(start, ready);
		hold.plan(start, ready, prediction);

		// Player places the suggested O; X has not answered yet.
		List<DungeonTicTacToeRules.Piece> afterO = new ArrayList<>(start);
		afterO.add(piece("O", ready.suggestedCell().row(), ready.suggestedCell().column()));
		var waiting = DungeonTicTacToeRules.analyze(afterO);
		require(waiting.status() == DungeonTicTacToeRules.Status.NOT_O_TURN, "board waits for X");
		var held = hold.hold(afterO, waiting);
		if (prediction.xReply() != null) {
			require(held != null && held.xReply().equals(prediction.xReply())
				&& java.util.Objects.equals(held.nextOMove(), prediction.nextOMove()),
				"the predicted X reply and next O move stay visible while X thinks");
		}

		// X plays something unexpected: the plan must be dropped, not shown stale.
		List<DungeonTicTacToeRules.Piece> surprise = new ArrayList<>(afterO);
		DungeonTicTacToeRules.Cell wrong = firstFreeCell(surprise, prediction.xReply());
		surprise.add(piece("X", wrong.row(), wrong.column()));
		require(hold.hold(surprise, DungeonTicTacToeRules.analyze(surprise)) == null,
			"a different X reply discards the held prediction");

		// A board unrelated to the plan never reuses it.
		var fresh = new DungeonTicTacToeTargetHold();
		fresh.plan(start, ready, prediction);
		require(fresh.hold(board("X", 0, 0), DungeonTicTacToeRules.analyze(board("X", 0, 0))) == null,
			"an unrelated board does not reuse a held prediction");
		fresh.clear();
		require(fresh.hold(afterO, waiting) == null, "clear() forgets the plan");
	}

	private static DungeonTicTacToeRules.Cell firstFreeCell(List<DungeonTicTacToeRules.Piece> pieces,
		DungeonTicTacToeRules.Cell avoid) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				DungeonTicTacToeRules.Cell cell = new DungeonTicTacToeRules.Cell(row, column);
				if (cell.equals(avoid)) continue;
				boolean taken = false;
				for (var p : pieces) {
					var at = DungeonTicTacToeRules.cellAt(p.x(), p.y(), p.z());
					if (cell.equals(at)) taken = true;
				}
				if (!taken) return cell;
			}
		}
		throw new AssertionError("no free cell");
	}

	private static List<DungeonTicTacToeRules.Piece> board(String mark, int row, int column) {
		List<DungeonTicTacToeRules.Piece> pieces = new ArrayList<>();
		pieces.add(piece(mark, row, column));
		return pieces;
	}

	private static DungeonTicTacToeRules.Piece piece(String mark, int row, int column) {
		var position = DungeonTicTacToeRules.positionForCell(new DungeonTicTacToeRules.Cell(row, column));
		int color = mark.equals("X") ? DungeonTicTacToeRules.X_MAP_COLOR : DungeonTicTacToeRules.O_MAP_COLOR;
		return new DungeonTicTacToeRules.Piece(position.x(), position.y(), position.z(), color);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("DungeonTicTacToeTargetHoldChecks: " + message);
	}
}
