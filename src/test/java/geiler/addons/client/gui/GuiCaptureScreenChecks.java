package geiler.addons.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntConsumer;

import java.lang.reflect.Modifier;

/** Guards the 26.2 screen hooks and constructor ABI used by settings and inventory controls. */
public final class GuiCaptureScreenChecks {
	private GuiCaptureScreenChecks() { }

	public static void run() {
		ColorPickerPaletteChecks.run();
		checkScreen(HotbarBindingCaptureScreen.class);
		checkScreen(GuiColorPickerScreen.class);
		checkConstructor(HotbarBindingCaptureScreen.class, Screen.class, int.class);
		checkConstructor(GuiColorPickerScreen.class, Screen.class, String.class, int.class, IntConsumer.class);
		checkConstructor(GuiColorPickerScreen.class, Screen.class, String.class, int.class,
			boolean.class, IntConsumer.class);
		checkDeclaredMethod(GuiColorPickerScreen.class, "mouseClicked", MouseButtonEvent.class, boolean.class);
		checkDeclaredMethod(GuiColorPickerScreen.class, "mouseDragged", MouseButtonEvent.class,
			double.class, double.class);
		checkDeclaredMethod(GuiColorPickerScreen.class, "mouseReleased", MouseButtonEvent.class);
		checkDeclaredMethod(GuiColorPickerScreen.class, "init");
	}

	private static void checkScreen(Class<?> screenClass) {
		check(Screen.class.isAssignableFrom(screenClass), screenClass.getSimpleName() + " remains a Minecraft screen");
		check(Modifier.isFinal(screenClass.getModifiers()), screenClass.getSimpleName() + " remains final");
		checkDeclaredMethod(screenClass, "isPauseScreen");
		checkDeclaredMethod(screenClass, "extractRenderState",
			GuiGraphicsExtractor.class, int.class, int.class, float.class);
		checkDeclaredMethod(screenClass, "keyPressed", KeyEvent.class);
		checkDeclaredMethod(screenClass, "onClose");
	}

	private static void checkConstructor(Class<?> type, Class<?>... parameterTypes) {
		try {
			type.getConstructor(parameterTypes);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " constructor ABI changed", error);
		}
	}

	private static void checkDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
		try {
			type.getDeclaredMethod(name, parameterTypes);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " lost " + name + " hook", error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
