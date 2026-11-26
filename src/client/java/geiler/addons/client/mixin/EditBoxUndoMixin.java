package geiler.addons.client.mixin;

import geiler.addons.client.gui.EditBoxUndo;
import geiler.addons.client.gui.TextFieldInput;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z for every vanilla text box (chat, anvil, and this mod's screens). */
@Mixin(EditBox.class)
public abstract class EditBoxUndoMixin {
	@Shadow private String value;
	@Shadow private int cursorPos;
	@Shadow private int highlightPos;

	@Shadow public abstract void setValue(String value);
	@Shadow public abstract void setCursorPosition(int position);
	@Shadow public abstract void setHighlightPos(int position);
	@Shadow public abstract boolean canConsumeInput();

	@Unique private EditBoxUndo geileraddons$history;
	@Unique private boolean geileraddons$typing;
	@Unique private boolean geileraddons$programmatic;

	@Unique
	private EditBoxUndo geileraddons$history() {
		if (geileraddons$history == null) geileraddons$history = new EditBoxUndo();
		return geileraddons$history;
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void geileraddons$undoKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!event.hasControlDown() || !canConsumeInput()) return;
		int key = event.key();
		boolean redo = key == GLFW.GLFW_KEY_Y || (key == GLFW.GLFW_KEY_Z && event.hasShiftDown());
		boolean undo = key == GLFW.GLFW_KEY_Z && !event.hasShiftDown();
		if (!undo && !redo) return;
		EditBoxUndo history = geileraddons$history();
		boolean moved = redo ? history.redo() : history.undo();
		if (moved) {
			history.setRestoring(true);
			try {
				setValue(history.getText());
				setCursorPosition(history.getCursor());
				setHighlightPos(history.getHighlight());
			} finally {
				history.setRestoring(false);
			}
		}
		TextFieldInput.traceEdit("edit-box", (redo ? "redo" : "undo") + (moved ? "" : "-empty"),
			value.length(), cursorPos, cursorPos != highlightPos);
		cir.setReturnValue(true);
	}

	@Inject(method = "charTyped", at = @At("HEAD"))
	private void geileraddons$typingStart(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		geileraddons$typing = true;
	}

	@Inject(method = "charTyped", at = @At("RETURN"))
	private void geileraddons$typingEnd(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		geileraddons$typing = false;
	}

	@Inject(method = "setValue", at = @At("HEAD"))
	private void geileraddons$programmaticStart(String newValue, CallbackInfo ci) {
		geileraddons$programmatic = true;
	}

	@Inject(method = "setValue", at = @At("RETURN"))
	private void geileraddons$programmaticEnd(String newValue, CallbackInfo ci) {
		geileraddons$programmatic = false;
	}

	@Inject(method = "onValueChange", at = @At("HEAD"))
	private void geileraddons$recordChange(String newValue, CallbackInfo ci) {
		geileraddons$history().changed(newValue, cursorPos, highlightPos, geileraddons$typing,
			geileraddons$programmatic, System.currentTimeMillis());
	}
}
