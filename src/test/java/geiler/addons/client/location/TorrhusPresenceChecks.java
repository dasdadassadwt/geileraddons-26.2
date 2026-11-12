package geiler.addons.client.location;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Regression checks for the Java-visible Tiki location-gate ABI. */
public final class TorrhusPresenceChecks {
	private TorrhusPresenceChecks() { }

	public static void run() throws ReflectiveOperationException {
		checkStaticMethod("tick", void.class);
		checkStaticMethod("isPresent", boolean.class);
		checkStaticMethod("reason", String.class);
	}

	private static void checkStaticMethod(String name, Class<?> returnType) throws ReflectiveOperationException {
		Method method = TorrhusPresence.class.getDeclaredMethod(name);
		int modifiers = method.getModifiers();
		if (method.getReturnType() != returnType || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
			throw new AssertionError("TorrhusPresence Java static method signature changed: " + method);
		}
	}
}
