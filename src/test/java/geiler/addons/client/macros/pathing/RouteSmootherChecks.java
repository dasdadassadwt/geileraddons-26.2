package geiler.addons.client.macros.pathing;

import java.util.ArrayList;
import java.util.List;

/** The drawn route is a smooth curve through the corners: the ends stay, no point strays off the corner it cuts, a short line is left alone. */
public final class RouteSmootherChecks {
	private RouteSmootherChecks() { }

	public static void run() {
		checkEndsStay();
		checkCornerIsCut();
		checkStraightStaysStraight();
		checkShortAndLong();
	}

	private static List<double[]> line(double... xz) {
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i < xz.length; i += 2) points.add(new double[] {xz[i], 64.0, xz[i + 1]});
		return points;
	}

	private static void checkEndsStay() {
		List<double[]> smooth = RouteSmoother.smooth(line(0, 0, 10, 0, 10, 10));
		check(smooth.get(0)[0] == 0 && smooth.get(0)[2] == 0, "the start stays");
		double[] last = smooth.get(smooth.size() - 1);
		check(last[0] == 10 && last[2] == 10, "and so does the end");
		check(smooth.size() > 3, "the corner got more points");
	}

	private static void checkCornerIsCut() {
		List<double[]> smooth = RouteSmoother.smooth(line(0, 0, 10, 0, 10, 10));
		double nearest = Double.MAX_VALUE;
		for (double[] point : smooth) nearest = Math.min(nearest, Math.hypot(point[0] - 10, point[2]));
		check(nearest > 0.5, "no point of the curve is on the corner: it is rounded (" + nearest + ")");
		for (double[] point : smooth) check(point[0] <= 10 + 1e-9 && point[2] >= -1e-9, "and none leaves the box of the corner");
	}

	private static void checkStraightStaysStraight() {
		for (double[] point : RouteSmoother.smooth(line(0, 0, 5, 0, 10, 0, 15, 0))) check(Math.abs(point[2]) < 1e-9, "a straight line is not bent");
	}

	private static void checkShortAndLong() {
		check(RouteSmoother.smooth(line(0, 0, 4, 4)).size() == 2, "two points are left as they are");
		List<double[]> many = new ArrayList<>();
		for (int i = 0; i < 400; i++) many.add(new double[] {i, 64, i % 2});
		check(RouteSmoother.smooth(many).size() <= RouteSmoother.MAX_POINTS, "a long line is never made longer than the bound");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
