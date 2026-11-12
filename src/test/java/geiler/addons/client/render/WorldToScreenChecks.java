package geiler.addons.client.render;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/** Direct checks for the Java-facing projection helper contract and invalid-input behavior. */
public final class WorldToScreenChecks {
	private WorldToScreenChecks() { }

	public static void run() {
		checkJavaApi();
		checkInvalidInputsDoNotTouchOutput();
	}

	private static void checkJavaApi() {
		try {
			Method project = WorldToScreen.class.getMethod("project", Camera.class, Vec3.class);
			Method projectInto = WorldToScreen.class.getMethod("projectInto", Camera.class, Vec3.class, float[].class);
			check(project.getReturnType() == float[].class && Modifier.isStatic(project.getModifiers()),
				"Java callers retain the static project(Camera, Vec3) method");
			check(projectInto.getReturnType() == boolean.class && Modifier.isStatic(projectInto.getModifiers()),
				"Java callers retain the static projectInto(Camera, Vec3, float[]) method");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("World-to-screen Java API changed", exception);
		}
	}

	private static void checkInvalidInputsDoNotTouchOutput() {
		float[] projected = {12.0f, 34.0f};
		check(WorldToScreen.project(null, null) == null,
			"a missing camera or target produces no projected point");
		check(!WorldToScreen.projectInto(null, null, projected)
			&& projected[0] == 12.0f && projected[1] == 34.0f,
			"invalid inputs leave caller-owned output untouched");
		check(!WorldToScreen.projectInto(null, null, new float[1]),
			"caller-owned output must have room for both coordinates");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
