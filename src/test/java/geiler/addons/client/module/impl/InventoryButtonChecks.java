package geiler.addons.client.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import geiler.addons.client.config.InventoryButtonConfigCodec;
import geiler.addons.client.gui.InventoryButtonOverlay;
import geiler.addons.client.gui.InventoryButtonPickerScreen;
import geiler.addons.client.gui.InventoryButtonTextScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Offline checks for inventory placement migration, bounds, reflow, and macro eligibility. */
public final class InventoryButtonChecks {
	private InventoryButtonChecks() { }

	public static void run() {
		checkJavaAbi();
		checkTextActionDispatchDiagnostics();
		checkPlacementCompatibilityEdges();
		checkEditorPanelPlacement();
		check(InventoryButtonConfigCodec.decode(null, id -> true, name -> true).isEmpty(),
			"configs predating Inventory Buttons migrate to an empty local layout");
		InventoryButtonPlacement first = new InventoryButtonPlacement(4);
		first.setMacroId(17);
		first.setGrid(11, 2);
		first.setAppearance(InventoryButtonPlacement.Appearance.TEXT, "Craft");
		first.setHoverTooltip("Crafting utility");
		List<InventoryButtonPlacement> original = List.of(first);
		JsonArray encoded = InventoryButtonConfigCodec.encode(original);
		List<InventoryButtonPlacement> restored = InventoryButtonConfigCodec.decode(encoded,
			id -> id == 17, name -> name.equals("icon.png"));
		check(restored.size() == 1, "button config round-trips one placement");
		check(restored.getFirst().macroId() == 17 && restored.getFirst().gridX() == 11
			&& restored.getFirst().gridY() == 2, "button stores stable macro id and inventory-relative grid cell");
		check(restored.getFirst().appearance() == InventoryButtonPlacement.Appearance.TEXT
			&& restored.getFirst().value().equals("Craft"), "button config round-trips text appearance");
		check(restored.getFirst().hoverTooltip().equals("Crafting utility"),
			"button config round-trips its optional custom hover tooltip");
		check(restored.getFirst().slotAligned(), "new placements keep the vanilla-slot grid anchor across saves");

		InventoryButtonPlacement actionButton = new InventoryButtonPlacement(12);
		actionButton.setGrid(2, -1);
		actionButton.setAppearance(InventoryButtonPlacement.Appearance.TEXT, "GFS");
		actionButton.setTextAction("/gfs ender_pearl 16");
		InventoryButtonPlacement restoredAction = InventoryButtonConfigCodec.decode(
			InventoryButtonConfigCodec.encode(List.of(actionButton)), id -> true, name -> true).getFirst();
		check(restoredAction.macroId() == -1 && restoredAction.hasTextAction()
			&& restoredAction.textAction().equals("/gfs ender_pearl 16"),
			"standalone text action buttons persist without a macro assignment");
		InventoryButtonTextAction.Dispatch command = InventoryButtonTextAction.parse(" /gfs ender_pearl 16 ");
		check(command != null && command.kind() == InventoryButtonTextAction.Kind.COMMAND
			&& command.payload().equals("gfs ender_pearl 16"), "slash-prefixed text dispatches as a command without the slash");
		InventoryButtonTextAction.Dispatch chat = InventoryButtonTextAction.parse("hello party");
		check(chat != null && chat.kind() == InventoryButtonTextAction.Kind.CHAT
			&& chat.payload().equals("hello party"), "plain text dispatches as a normal chat message");
		check(InventoryButtonTextAction.parse("/") == null
			&& InventoryButtonTextAction.parse("  ") == null, "empty command and chat actions are ignored");

		JsonObject stale = new JsonObject();
		stale.addProperty("id", 5);
		stale.addProperty("macroId", 99);
		stale.addProperty("gridX", 0);
		stale.addProperty("gridY", 0);
		stale.addProperty("appearance", "ITEM");
		stale.addProperty("value", "minecraft:stone");
		JsonArray staleConfig = new JsonArray();
		staleConfig.add(stale);
		List<InventoryButtonPlacement> staleResult = InventoryButtonConfigCodec.decode(staleConfig,
			id -> false, name -> false);
		check(staleResult.getFirst().macroId() == -1,
			"deleted macro ids restore as rebindable unassigned buttons");
		check(staleResult.getFirst().hoverTooltip().isEmpty(),
			"older button configs without a tooltip default to no custom tooltip");
		check(!staleResult.getFirst().slotAligned()
			&& InventoryButtonLayout.placementPixelX(staleResult.getFirst(), 100) == 100
			&& InventoryButtonLayout.placementPixelY(staleResult.getFirst(), 64) == 64,
			"legacy grid positions remain pixel-identical until explicitly moved or reflowed");

		JsonObject unsafe = new JsonObject();
		unsafe.addProperty("id", 6);
		unsafe.addProperty("macroId", -1);
		unsafe.addProperty("appearance", "PNG");
		unsafe.addProperty("value", "../not-an-icon.png");
		JsonArray unsafeConfig = new JsonArray();
		unsafeConfig.add(unsafe);
		InventoryButtonPlacement safeFallback = InventoryButtonConfigCodec.decode(unsafeConfig,
			id -> true, name -> name.equals("icon.png")).getFirst();
		check(safeFallback.appearance() == InventoryButtonPlacement.Appearance.ITEM,
			"invalid PNG paths cannot become arbitrary texture inputs");

		check(InventoryButtonLayout.pixelX(0, 100) == 108 && InventoryButtonLayout.pixelY(0, 64) == 82,
			"cell zero aligns with the first vanilla inventory slot lattice point");
		check(InventoryButtonLayout.gridX(117, 100) == 0 && InventoryButtonLayout.gridY(91, 64) == 0,
			"cell centers map back to their slot-aligned grid coordinates");
		check(InventoryButtonLayout.gridX(127, 100) == 1,
			"cursor snapping advances at the boundary between adjacent 18px cells");

		int screenWidth = 400;
		int screenHeight = 240;
		int inventoryLeft = 100;
		int inventoryTop = 64;
		InventoryButtonLayout.Bounds inventory = new InventoryButtonLayout.Bounds(100, 64, 176, 166);
		InventoryButtonLayout.Bounds recipeBook = new InventoryButtonLayout.Bounds(16, 64, 80, 120);
		InventoryButtonLayout.Bounds editorPanel = new InventoryButtonLayout.Bounds(350, 20, 44, 200);
		List<InventoryButtonLayout.Bounds> occupiedBounds = List.of(inventory, recipeBook, editorPanel);
		check(InventoryButtonLayout.fitsViewportAndBounds(11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "fully exterior grid cells remain available");
		check(!InventoryButtonLayout.fitsViewportAndBounds(0, 0, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "the active inventory rectangle excludes its slot cells");
		check(!InventoryButtonLayout.fitsViewportAndBounds(-2, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "the visible recipe-book panel is excluded from placement");
		check(!InventoryButtonLayout.fitsViewportAndBounds(18, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "cells extending beyond the GUI viewport are rejected");

		InventoryButtonPlacement placement = new InventoryButtonPlacement(4);
		placement.setGrid(11, 2);
		List<InventoryButtonPlacement> placements = new ArrayList<>(List.of(placement));
		check(!InventoryButtonLayout.canPlace(placements, -1, 11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "a second button cannot overlap an existing placement");
		check(InventoryButtonLayout.canPlace(placements, 4, 11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "a button can remain in its own cell while moving");
		InventoryButtonPlacement legacy = InventoryButtonConfigCodec.decode(staleConfig,
			id -> false, name -> false).getFirst();
		check(!InventoryButtonLayout.canPlace(List.of(legacy), -1, -1, -1, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of()),
			"overlap detection uses actual pixels across old and new grid anchors");
		check(!InventoryButtonLayout.canPlace(placements, 4, -12, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "button cells remain fully inside the viewport");
		check(InventoryButtonLayout.hit(placements, 315, 127, inventoryLeft, inventoryTop,
			screenWidth, screenHeight, occupiedBounds) == placement,
			"hit testing follows the same inventory-relative snapped cell");
		check(InventoryButtonLayout.pixelX(11, 100) - 100 == InventoryButtonLayout.pixelX(11, 72) - 72,
			"a cell remains anchored to the inventory rectangle across GUI positions");

		InventoryButtonPlacement invalid = new InventoryButtonPlacement(1);
		invalid.setGrid(0, 0);
		List<InventoryButtonPlacement> needsReflow = new ArrayList<>(List.of(invalid, placement));
		check(InventoryButtonLayout.invalidCount(needsReflow, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of(inventory)) == 1,
			"saved placements are detected as invalid without being silently moved");
		check(invalid.gridX() == 0 && invalid.gridY() == 0,
			"invalid saved coordinates remain unchanged until explicit reflow");
		check(InventoryButtonLayout.hit(needsReflow, 117, 91, inventoryLeft, inventoryTop,
			screenWidth, screenHeight, List.of(inventory)) == null,
			"normal hit testing ignores placements in the inventory surface");
		check(InventoryButtonLayout.hitSaved(needsReflow, 117, 91, inventoryLeft, inventoryTop,
			screenWidth, screenHeight) == invalid,
			"edit hit testing can select a visible invalid placement for repair");
		InventoryButtonLayout.ReflowResult reflow = InventoryButtonLayout.reflowInvalid(needsReflow,
			screenWidth, screenHeight, inventoryLeft, inventoryTop, List.of(inventory));
		check(reflow.moved() == 1 && reflow.remaining() == 0,
			"explicit reflow moves invalid placements into nearest available exterior cells");
		check(InventoryButtonLayout.isPlacementValid(needsReflow, invalid, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of(inventory)), "reflowed placements satisfy all exterior bounds");
		check(invalid.gridX() == 0 && invalid.gridY() == -2,
			"reflow chooses the nearest available exterior cell on the vanilla slot lattice");
		check(placement.gridX() == 11 && placement.gridY() == 2,
			"reflow preserves placements that were already valid");

		InventoryButtonPlacement stranded = new InventoryButtonPlacement(8);
		stranded.setGrid(0, 0);
		List<InventoryButtonPlacement> noSpace = new ArrayList<>(List.of(stranded));
		InventoryButtonLayout.ReflowResult incomplete = InventoryButtonLayout.reflowInvalid(noSpace,
			40, 40, 0, 0, List.of(new InventoryButtonLayout.Bounds(0, 0, 40, 40)));
		check(incomplete.moved() == 0 && incomplete.remaining() == 1,
			"reflow retains placements and reports them when no valid cells exist");

		check(!InventoryButtonRules.evaluate(false, true, true, true, true, true).eligible(),
			"disabled macro system blocks an inventory button");
		check(!InventoryButtonRules.evaluate(true, false, true, true, true, true).eligible(),
			"a deleted macro is ineligible");
		check(!InventoryButtonRules.evaluate(true, true, false, true, true, true).eligible(),
			"disabled macros cannot run from a button");
		check(!InventoryButtonRules.evaluate(true, true, true, false, true, true).eligible(),
			"empty macros cannot run from a button");
		check(!InventoryButtonRules.evaluate(true, true, true, true, false, true).eligible(),
			"button obeys the macro trigger context");
		check(!InventoryButtonRules.evaluate(true, true, true, true, true, false).eligible(),
			"button obeys the macro island allowlist");
		check(InventoryButtonRules.evaluate(true, true, true, true, true, true).eligible(),
			"eligible assigned macro can run from its inventory button");
	}

	private static void checkTextActionDispatchDiagnostics() {
		String command = InventoryButtonsModule.formatTextActionDispatchDiagnostic(7,
			InventoryButtonTextAction.Kind.COMMAND, 10_000,
			InventoryButtonsModule.TextActionDispatchOutcome.COMMAND_SUBMITTED);
		check(command.equals("TEXT ACTION DISPATCH id=7 kind=COMMAND chars=256 outcome=COMMAND_SUBMITTED"),
			"command dispatch trace is fixed-field, payload-free, and length-bounded");
		String missingConnection = InventoryButtonsModule.formatTextActionDispatchDiagnostic(7,
			InventoryButtonTextAction.Kind.CHAT, 4,
			InventoryButtonsModule.TextActionDispatchOutcome.NO_CONNECTION);
		check(missingConnection.equals("TEXT ACTION DISPATCH id=7 kind=CHAT chars=4 outcome=NO_CONNECTION"),
			"chat dispatch trace reports the no-connection outcome without action text");
		String chat = InventoryButtonsModule.formatTextActionDispatchDiagnostic(7,
			InventoryButtonTextAction.Kind.CHAT, 5,
			InventoryButtonsModule.TextActionDispatchOutcome.CHAT_SUBMITTED);
		check(chat.equals("TEXT ACTION DISPATCH id=7 kind=CHAT chars=5 outcome=CHAT_SUBMITTED"),
			"chat dispatch trace uses its fixed submitted outcome");
		String empty = InventoryButtonsModule.formatTextActionDispatchDiagnostic(7, null, 0,
			InventoryButtonsModule.TextActionDispatchOutcome.EMPTY);
		check(empty.equals("TEXT ACTION DISPATCH id=7 kind=NONE chars=0 outcome=EMPTY"),
			"empty dispatch trace uses a fixed kind label and outcome");
	}

	private static void checkJavaAbi() {
		checkStaticMethod(InventoryButtonOverlay.class, "clearIconTextureCache", void.class);
		checkStaticMethod(InventoryButtonOverlay.class, "isEditing", boolean.class);
		checkStaticMethod(InventoryButtonOverlay.class, "beginEditing", void.class);
		checkStaticMethod(InventoryButtonOverlay.class, "beginEditing", void.class, int.class);
		checkStaticMethod(InventoryButtonOverlay.class, "beginEditing", void.class,
			int.class, Screen.class, InventoryScreen.class);
		checkStaticMethod(InventoryButtonOverlay.class, "tick", void.class);
		checkStaticMethod(InventoryButtonOverlay.class, "consumeEditorReturnScreen", Screen.class,
			InventoryScreen.class);
		checkStaticMethod(InventoryButtonOverlay.class, "stopEditing", void.class);
		checkStaticMethod(InventoryButtonOverlay.class, "listPngIcons", List.class);
		checkStaticMethod(InventoryButtonOverlay.class, "render", void.class,
			AbstractContainerScreen.class, GuiGraphicsExtractor.class, int.class, int.class);
		checkStaticMethod(InventoryButtonOverlay.class, "mouseClicked", boolean.class,
			AbstractContainerScreen.class, MouseButtonEvent.class);
		checkStaticMethod(InventoryButtonOverlay.class, "mouseDragged", boolean.class,
			AbstractContainerScreen.class, MouseButtonEvent.class);
		checkStaticMethod(InventoryButtonOverlay.class, "keyPressed", boolean.class,
			AbstractContainerScreen.class, KeyEvent.class);
		checkStaticMethod(InventoryButtonOverlay.class, "mouseReleased", void.class,
			AbstractContainerScreen.class);
		checkStaticMethod(InventoryButtonOverlay.class, "mouseReleased", void.class,
			InventoryScreen.class);

		checkPublicConstructor(InventoryButtonPickerScreen.class, Screen.class,
			InventoryButtonPickerScreen.Mode.class, Consumer.class);
		checkPublicConstructor(InventoryButtonPickerScreen.class, Screen.class,
			InventoryButtonPickerScreen.Mode.class, Consumer.class, Runnable.class, Runnable.class);
		checkPublicConstructor(InventoryButtonTextScreen.class, Screen.class, String.class, Consumer.class);
		checkPublicConstructor(InventoryButtonTextScreen.class, Screen.class, String.class, String.class,
			String.class, Consumer.class);
		check(InventoryButtonPickerScreen.Mode.values().length == 3
			&& InventoryButtonPickerScreen.Mode.values()[0] == InventoryButtonPickerScreen.Mode.MACRO
			&& InventoryButtonPickerScreen.Mode.values()[1] == InventoryButtonPickerScreen.Mode.ITEM
			&& InventoryButtonPickerScreen.Mode.values()[2] == InventoryButtonPickerScreen.Mode.PNG,
			"picker screen keeps the macro, item, and PNG modes in their original enum order");

		checkPublicStaticField("CELL_SIZE", 18);
		checkPublicStaticField("FIRST_SLOT_X", 8);
		checkPublicStaticField("FIRST_SLOT_Y", 18);
		checkPublicConstructor(InventoryButtonPlacement.class, int.class);
		checkPublicConstructor(InventoryButtonPlacement.class, InventoryButtonPlacement.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "id", int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "macroId", int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "gridX", int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "gridY", int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "slotAligned", boolean.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "appearance", InventoryButtonPlacement.Appearance.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "value", String.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "hoverTooltip", String.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "textAction", String.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "hasTextAction", boolean.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setMacroId", void.class, int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setGrid", void.class, int.class, int.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setSlotAligned", void.class, boolean.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setAppearance", void.class,
			InventoryButtonPlacement.Appearance.class, String.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setHoverTooltip", void.class, String.class);
		checkInstanceMethod(InventoryButtonPlacement.class, "setTextAction", void.class, String.class);

		checkStaticMethod(InventoryButtonLayout.class, "gridX", int.class, double.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "gridY", int.class, double.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "pixelX", int.class, int.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "pixelY", int.class, int.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "placementPixelX", int.class,
			InventoryButtonPlacement.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "placementPixelY", int.class,
			InventoryButtonPlacement.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "inventoryBounds", InventoryButtonLayout.Bounds.class,
			int.class, int.class, int.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "editorPanel", InventoryButtonLayout.EditorPanel.class,
			int.class, int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "canPlace", boolean.class, List.class,
			int.class, int.class, int.class, int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "isPlacementValid", boolean.class, List.class,
			InventoryButtonPlacement.class, int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "invalidCount", int.class, List.class,
			int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "reflowInvalid", InventoryButtonLayout.ReflowResult.class,
			List.class, int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "hit", InventoryButtonPlacement.class, List.class,
			double.class, double.class, int.class, int.class, int.class, int.class, List.class);
		checkStaticMethod(InventoryButtonLayout.class, "hitSaved", InventoryButtonPlacement.class, List.class,
			double.class, double.class, int.class, int.class, int.class, int.class);
		checkStaticMethod(InventoryButtonLayout.class, "fitsViewportAndBounds", boolean.class,
			int.class, int.class, int.class, int.class, int.class, int.class, List.class);
		checkRecord(InventoryButtonLayout.Bounds.class, "x", "y", "width", "height");
		checkRecord(InventoryButtonLayout.ReflowResult.class, "moved", "remaining");
		checkRecord(InventoryButtonLayout.EditorPanel.class, "x", "y", "width", "height", "side");
		checkInstanceMethod(InventoryButtonLayout.Bounds.class, "intersects", boolean.class,
			int.class, int.class, int.class, int.class);

		checkStaticMethod(InventoryButtonRules.class, "evaluate", InventoryButtonRules.Eligibility.class,
			boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
		checkRecord(InventoryButtonRules.Eligibility.class, "eligible", "reason");
		checkStaticMethod(InventoryButtonTextAction.class, "parse", InventoryButtonTextAction.Dispatch.class,
			String.class);
		checkRecord(InventoryButtonTextAction.Dispatch.class, "kind", "payload");
	}

	private static void checkPlacementCompatibilityEdges() {
		InventoryButtonPlacement negativeId = new InventoryButtonPlacement(-4);
		check(negativeId.id() == 0, "placement construction clamps IDs at zero");
		InventoryButtonPlacement source = new InventoryButtonPlacement(7);
		source.setMacroId(12);
		source.setGrid(-200, 200);
		source.setAppearance(InventoryButtonPlacement.Appearance.TEXT, "\u2003value\u2003 ");
		source.setHoverTooltip(" tooltip ");
		source.setTextAction(" \u2003 ");
		InventoryButtonPlacement copy = new InventoryButtonPlacement(source);
		check(copy.id() == 7 && copy.macroId() == 12 && copy.gridX() == -128 && copy.gridY() == 128
			&& copy.slotAligned() && copy.appearance() == InventoryButtonPlacement.Appearance.TEXT,
			"the copy constructor preserves all placement state and setter bounds");
		check(copy.value().equals("\u2003value\u2003") && copy.hoverTooltip().equals("tooltip"),
			"placement text retains Java String.trim whitespace and edge behavior");
		check(copy.textAction().equals("\u2003") && !copy.hasTextAction(),
			"text action keeps the trimmed value while Java isBlank still treats it as blank");
		copy.setSlotAligned(false);
		check(!copy.slotAligned() && source.slotAligned(), "the copy does not alias mutable placement state");
		copy.setAppearance(null, null);
		copy.setTextAction(null);
		check(copy.appearance() == InventoryButtonPlacement.Appearance.ITEM && copy.value().isEmpty()
			&& copy.textAction() == null, "nullable Java setters keep their original reset semantics");

		check(InventoryButtonLayout.gridX(Double.NaN, 0) == 0
			&& InventoryButtonLayout.gridX(Double.POSITIVE_INFINITY, 0) == -1,
			"cursor snapping preserves Java Math.round and narrowing edge behavior");
		check(InventoryButtonTextAction.parse("\u2003") != null
			&& InventoryButtonTextAction.parse("\u2003").payload().equals("\u2003"),
			"text action parsing preserves Java String.trim behavior for Unicode whitespace");
		check(new InventoryButtonRules.Eligibility(false, null).reason() == null
			&& new InventoryButtonTextAction.Dispatch(null, null).kind() == null,
			"Java-visible record constructors continue to accept null components");
 	}

	private static void checkPublicStaticField(String name, int expected) {
		try {
			Field field = InventoryButtonLayout.class.getField(name);
			check(field.getType() == int.class && Modifier.isPublic(field.getModifiers())
				&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
				&& field.getInt(null) == expected, "public static final layout constant remains " + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing layout field " + name, exception);
		}
	}

	private static void checkEditorPanelPlacement() {
		InventoryButtonLayout.Bounds inventory = new InventoryButtonLayout.Bounds(360, 100, 176, 166);
		InventoryButtonLayout.EditorPanel right = InventoryButtonLayout.editorPanel(
			900, 600, inventory.x(), inventory.y(), inventory.width(), List.of(inventory));
		check(right.side() == InventoryButtonLayout.EditorPanelSide.RIGHT
			&& right.x() == 688 && right.y() == 100
			&& right.width() == InventoryButtonLayout.EDITOR_PANEL_WIDTH
			&& right.height() == InventoryButtonLayout.EDITOR_PANEL_HEIGHT,
			"the button editor opens in a clear, screen-fitted panel to the inventory's right");

		InventoryButtonLayout.Bounds rightBlocker = new InventoryButtonLayout.Bounds(688, 100, 204, 218);
		InventoryButtonLayout.EditorPanel left = InventoryButtonLayout.editorPanel(
			900, 600, inventory.x(), inventory.y(), inventory.width(), List.of(inventory, rightBlocker));
		check(left.side() == InventoryButtonLayout.EditorPanelSide.LEFT && left.x() == 8,
			"the panel uses the left side only when the preferred right side is occupied");

		InventoryButtonLayout.Bounds centeredInventory = new InventoryButtonLayout.Bounds(162, 60, 176, 166);
		InventoryButtonLayout.EditorPanel fitted = InventoryButtonLayout.editorPanel(
			500, 300, centeredInventory.x(), centeredInventory.y(), centeredInventory.width(),
			List.of(centeredInventory));
		check(fitted.side() == InventoryButtonLayout.EditorPanelSide.FITTED_OVERLAY
			&& fitted.x() == 288 && fitted.y() == 60
			&& fitted.x() >= 0 && fitted.y() >= 0
			&& fitted.x() + fitted.width() <= 500 && fitted.y() + fitted.height() <= 300,
			"when neither side is clear, the editor falls back to an overlay fully inside the screen");

		InventoryButtonLayout.EditorPanel tiny = InventoryButtonLayout.editorPanel(
			100, 80, 0, 0, 176, List.of(new InventoryButtonLayout.Bounds(0, 0, 100, 80)));
		check(tiny.x() >= 0 && tiny.y() >= 0
			&& tiny.x() + tiny.width() <= 100 && tiny.y() + tiny.height() <= 80,
			"the fitted fallback remains inside narrow and short screens");
	}

	private static void checkPublicConstructor(Class<?> type, Class<?>... parameters) {
		try {
			Constructor<?> constructor = type.getConstructor(parameters);
			check(Modifier.isPublic(constructor.getModifiers()), "public constructor remains " + type.getSimpleName());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing public constructor on " + type.getSimpleName(), exception);
		}
	}

	private static void checkStaticMethod(Class<?> type, String name, Class<?> returnType, Class<?>... parameters) {
		try {
			Method method = type.getMethod(name, parameters);
			check(method.getReturnType() == returnType && Modifier.isStatic(method.getModifiers()),
				"Java ABI keeps static " + type.getSimpleName() + "." + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing static method " + type.getSimpleName() + "." + name, exception);
		}
	}

	private static void checkInstanceMethod(Class<?> type, String name, Class<?> returnType, Class<?>... parameters) {
		try {
			Method method = type.getMethod(name, parameters);
			check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()),
				"Java ABI keeps instance " + type.getSimpleName() + "." + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing instance method " + type.getSimpleName() + "." + name, exception);
		}
	}

	private static void checkRecord(Class<?> type, String... components) {
		check(type.isRecord(), type.getSimpleName() + " remains a Java record to callers");
		java.lang.reflect.RecordComponent[] actual = type.getRecordComponents();
		check(actual.length == components.length, type.getSimpleName() + " keeps its record component count");
		for (int index = 0; index < components.length; index++) {
			check(actual[index].getName().equals(components[index]),
				type.getSimpleName() + " keeps record component " + components[index]);
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
