package geiler.addons.client.render;

import net.minecraft.client.renderer.rendertype.RenderType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Inspects the Java render boundary without initializing GPU pipelines in headless checks. */
public final class GeilerAddonsRenderTypesAbiChecks {
	private GeilerAddonsRenderTypesAbiChecks() { }

	public static void run() {
		try {
			Class<?> types = Class.forName(GeilerAddonsRenderTypes.class.getName(), false,
				GeilerAddonsRenderTypesAbiChecks.class.getClassLoader());
			for (String name : new String[] { "ESP_QUADS", "ESP_LINES", "DEPTH_QUADS", "DEPTH_LINES" }) {
				Field field = types.getField(name);
				check(field.getType() == RenderType.class && Modifier.isPublic(field.getModifiers())
					&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
					name + " remains a public static final RenderType");
			}
			checkStatic(types.getMethod("quads", boolean.class), RenderType.class, "quads");
			checkStatic(types.getMethod("lines", boolean.class), RenderType.class, "lines");
			checkStatic(types.getMethod("endBatches", WorldRenderBuffer.BufferSource.class), void.class, "endBatches");
			checkReverseZ();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Render type Java ABI changed", exception);
		}
	}

	/** 26.2 depth is reverse-Z; a hard-coded LESS_THAN_OR_EQUAL draws boxes only over the occluder. */
	private static void checkReverseZ() throws ReflectiveOperationException {
		check(ReverseZDepth.compareOp() == com.mojang.blaze3d.pipeline.DepthStencilState.DEFAULT.depthTest(),
			"world depth tests use the vanilla default compare op");
		try (java.io.InputStream in = GeilerAddonsRenderTypesAbiChecks.class.getClassLoader()
			.getResourceAsStream("geiler/addons/client/render/GeilerAddonsRenderTypes.class")) {
			String constants = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
			check(!constants.contains("LESS_THAN_OR_EQUAL") && !constants.contains("GREATER_THAN_OR_EQUAL"),
				"render types must not hard-code a depth compare op");
			check(constants.contains("ReverseZDepth"), "depth pipelines take their compare op from ReverseZDepth");
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void checkStatic(Method method, Class<?> result, String name) {
		check(method.getReturnType() == result && Modifier.isPublic(method.getModifiers())
			&& Modifier.isStatic(method.getModifiers()), name + " remains a public static Java method");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
