package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.NumberSetting;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Focused offline checks for first-person item scale and swing semantics. */
public final class ItemAnimationScaleChecks {
	private ItemAnimationScaleChecks() { }

	public static void main(String[] args) throws IOException {
		run();
		System.out.println("Item animation checks passed.");
	}

	public static void run() throws IOException {
		String source = Files.readString(Path.of(
			"src", "client", "kotlin", "geiler", "addons", "client", "module", "impl", "ItemAnimationsModule.kt"));
		check(source.contains("private const val ITEM_MODEL_CENTER = 0.5f"),
			"item scaling must use the model's centered origin");
		check(source.contains("NumberSetting(\"scale\", \"Scale\", -4.0f, 4.0f, -1.53f)"),
			"item scale's saved default must remain unchanged");
		check(source.contains("pose.mulPose(Axis.XP.rotationDegrees(pitchOffset.value()))")
				&& source.contains("pose.mulPose(Axis.YP.rotationDegrees(yawOffset.value()))")
				&& source.contains("pose.mulPose(Axis.ZP.rotationDegrees(rollOffset.value()))")
				&& source.contains("pose.translate(xOffset.value(), yOffset.value(), zOffset.value())"),
			"XYZ position and pitch/yaw/roll must remain independent transform settings");
		checkRuntimeTransformAndScale();
		checkSwingProgressParity(source);

		int methodStart = source.indexOf("fun applyDeferredItemScale(pose: PoseStack?)");
		check(methodStart >= 0, "deferred item scale hook must exist");
		int methodEnd = source.indexOf("\n    }", methodStart);
		check(methodEnd > methodStart, "deferred item scale hook must have a body");
		String method = source.substring(methodStart, methodEnd);
		int centerBefore = method.indexOf("pose.translate(ITEM_MODEL_CENTER, ITEM_MODEL_CENTER, ITEM_MODEL_CENTER)");
		int scale = method.indexOf("pose.scale(multiplier, multiplier, multiplier)");
		int centerAfter = method.indexOf("pose.translate(-ITEM_MODEL_CENTER, -ITEM_MODEL_CENTER, -ITEM_MODEL_CENTER)");
		check(centerBefore >= 0 && scale > centerBefore && centerAfter > scale,
			"uniform scale must be applied around the model center");
		check(!method.contains("xOffset") && !method.contains("yOffset") && !method.contains("zOffset")
				&& !method.contains("yawOffset") && !method.contains("pitchOffset") && !method.contains("rollOffset"),
			"the scale hook must not modify position or rotation settings");

		for (double multiplier : new double[] { 0.25, 0.5, 1.0, 2.0 }) {
			double center = scaleCoordinateAroundCenter(0.5, multiplier);
			check(close(center, 0.5), "centered scaling must keep the model center fixed");
			double scaledUnitWidth = scaleCoordinateAroundCenter(1.0, multiplier)
				- scaleCoordinateAroundCenter(0.0, multiplier);
			check(close(scaledUnitWidth, multiplier), "scale must change rendered dimensions uniformly");
		}
	}

	private static void checkRuntimeTransformAndScale() {
		ItemAnimationsModule module = ItemAnimationsModule.INSTANCE;
		NumberSetting xOffset = numberSetting(module, "xOffset");
		NumberSetting yOffset = numberSetting(module, "yOffset");
		NumberSetting zOffset = numberSetting(module, "zOffset");
		NumberSetting yawOffset = numberSetting(module, "yawOffset");
		NumberSetting pitchOffset = numberSetting(module, "pitchOffset");
		NumberSetting rollOffset = numberSetting(module, "rollOffset");
		NumberSetting scale = numberSetting(module, "scale");
		NumberSetting[] settings = { xOffset, yOffset, zOffset, yawOffset, pitchOffset, rollOffset, scale };
		float[] originalValues = new float[settings.length];
		for (int index = 0; index < settings.length; index++) originalValues[index] = settings[index].value();
		boolean originalEnabled = module.isEnabled();
		try {
			module.setEnabled(true);
			xOffset.setValue(0.25f);
			yOffset.setValue(-0.5f);
			zOffset.setValue(0.75f);
			yawOffset.setValue(0.0f);
			pitchOffset.setValue(0.0f);
			rollOffset.setValue(0.0f);
			scale.setValue(1.0f);

			PoseStack transformed = new PoseStack();
			module.applyTransform(transformed, ItemStack.EMPTY);
			check(close(transformed.last().pose().m30(), 0.25)
					&& close(transformed.last().pose().m31(), -0.5)
					&& close(transformed.last().pose().m32(), 0.75),
				"enabled empty-hand transforms apply the configured XYZ offsets");

			PoseStack firstPersonItem = new PoseStack();
			module.beginItemScale(ItemStack.EMPTY, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
			try {
				module.applyDeferredItemScale(firstPersonItem);
			} finally {
				module.endItemScale();
			}
			check(close(firstPersonItem.last().pose().m00(), 2.0)
					&& close(firstPersonItem.last().pose().m11(), 2.0)
					&& close(firstPersonItem.last().pose().m22(), 2.0)
					&& close(firstPersonItem.last().pose().m30(), -0.5)
					&& close(firstPersonItem.last().pose().m31(), -0.5)
					&& close(firstPersonItem.last().pose().m32(), -0.5),
				"first-person scale changes all three axes around the item model center");

			PoseStack thirdPersonItem = new PoseStack();
			module.beginItemScale(ItemStack.EMPTY, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
			try {
				module.applyDeferredItemScale(thirdPersonItem);
			} finally {
				module.endItemScale();
			}
			check(close(thirdPersonItem.last().pose().m00(), 1.0)
					&& close(thirdPersonItem.last().pose().m30(), 0.0),
				"the held-item scale scope excludes third-person render contexts");

			module.setEnabled(false);
			PoseStack disabled = new PoseStack();
			module.applyTransform(disabled, ItemStack.EMPTY);
			check(close(disabled.last().pose().m00(), 1.0)
					&& close(disabled.last().pose().m30(), 0.0)
					&& close(disabled.last().pose().m31(), 0.0)
					&& close(disabled.last().pose().m32(), 0.0),
				"disabled Item Animations leaves the vanilla pose unchanged");

			module.setEnabled(true);
			module.applyTransform(null, null);
			module.beginItemScale(null, null);
			module.applyDeferredItemScale(null);
			module.endItemScale();
		} finally {
			for (int index = 0; index < settings.length; index++) settings[index].setValue(originalValues[index]);
			module.setEnabled(originalEnabled);
		}
	}

	private static NumberSetting numberSetting(ItemAnimationsModule module, String name) {
		return module.numberSettings().stream().filter(setting -> name.equals(setting.name()))
			.findFirst().orElseThrow(() -> new AssertionError("missing Item Animations setting: " + name));
	}

	private static void checkSwingProgressParity(String source) throws IOException {
		Path baselinePath = Path.of("..", "26.1.2", "src", "client", "java", "geiler", "addons",
			"client", "module", "impl", "ItemAnimationsModule.java");
		// The frozen 26.1.2 baseline now lives under ../archive/.
		if (!Files.exists(baselinePath)) baselinePath = Path.of("..", "archive").resolve(Path.of("26.1.2", "src", "client", "java", "geiler", "addons",
			"client", "module", "impl", "ItemAnimationsModule.java"));
		if (!Files.exists(baselinePath)) {
			System.out.println("Item Animations baseline comparison skipped: no 26.1.2 source next to this checkout");
			return;
		}
		String baseline = Files.readString(baselinePath);
		String targetMethod = methodBody(source, "fun swingProgress(partialTick: Float, vanillaProgress: Float): Float");
		String baselineMethod = methodBody(baseline, "public float swingProgress(float partialTick, float vanillaProgress)");
		String[] parityFragments = {
			"if (!isEnabled()) return vanillaProgress",
			"noSwing.value() || (noSwingTerm.value() && isTerminator(held))",
			"if (delta < 0.0f) delta++",
			"Math.max(0.0f, Math.min(1.0f, partialTick))",
			"return previousAttackAnimation + delta *",
			"return 0.0f",
		};
		for (String fragment : parityFragments) {
			check(targetMethod.contains(fragment) && baselineMethod.contains(fragment),
				"swing progress must preserve the frozen 26.1.2 behavior: " + fragment);
		}
	}

	private static String methodBody(String source, String signature) {
		int methodStart = source.indexOf(signature);
		check(methodStart >= 0, "expected method must exist: " + signature);
		int bodyStart = source.indexOf('{', methodStart);
		check(bodyStart >= 0, "expected method body must exist: " + signature);
		int depth = 0;
		for (int index = bodyStart; index < source.length(); index++) {
			if (source.charAt(index) == '{') depth++;
			else if (source.charAt(index) == '}' && --depth == 0) return source.substring(bodyStart, index + 1);
		}
		throw new AssertionError("unterminated method body: " + signature);
	}

	private static double scaleCoordinateAroundCenter(double coordinate, double multiplier) {
		return (coordinate - 0.5) * multiplier + 0.5;
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) < 1.0e-9;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
