package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PartyFinderStatsModule;
import geiler.addons.client.access.AbstractContainerScreenAccess;
import geiler.addons.client.module.impl.ExperimentSolverModule;
import geiler.addons.client.module.impl.AutoExperimentsModule;
import geiler.addons.client.module.impl.SlotIdsModule;
import geiler.addons.client.gui.SlotIdOverlay;
import geiler.addons.client.gui.InventoryButtonOverlay;
import geiler.addons.client.inventory.PlayerInventoryControls;
import geiler.addons.client.module.impl.DungeonAssistModule;
import geiler.addons.client.module.impl.SpiritLeapSurfaceOwner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures Group Builder selections before the confirmation click closes the menu. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@org.spongepowered.asm.mixin.Shadow
	protected int leftPos;
	@org.spongepowered.asm.mixin.Shadow
	protected int topPos;

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void geileraddons$handleInventoryButtonKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (geiler.addons.client.module.impl.ChestRollModule.INSTANCE.handleKey((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.handleKey((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.handleKey((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
			return;
		}
		if (InventoryButtonOverlay.keyPressed((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
	private void geileraddons$returnFromInventoryButtonEditor(CallbackInfo ci) {
		if (!((Object) this instanceof InventoryScreen inventoryScreen)) return;
		Screen parent = InventoryButtonOverlay.consumeEditorReturnScreen(inventoryScreen);
		if (parent == null) return;
		InventoryButtonOverlay.stopEditing();
		Minecraft.getInstance().gui.setScreen(parent);
		ci.cancel();
	}

	@Inject(method = "init", at = @At("HEAD"))
	private void geileraddons$attachExperimentListener(CallbackInfo ci) {
		geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.onScreenOpened((AbstractContainerScreen<?>) (Object) this);
		geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.onScreenOpened((AbstractContainerScreen<?>) (Object) this);
		ExperimentSolverModule.INSTANCE.onScreenOpened((AbstractContainerScreen<?>) (Object) this);
	}
	/**
	 * Replaces the vanilla inventory extraction only while the experiment adapter owns the
	 * recognized screen. The adapter draws the complete surface, so the vanilla slot grid cannot
	 * peek through or receive a second render pass underneath it.
	 */
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void geileraddons$renderExperimentSurface(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (geiler.addons.client.module.impl.ChestRollModule.INSTANCE.renderRoll(screen, graphics, mouseX, mouseY)) {
			ci.cancel();
			return;
		}
		if (geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.renderCustomScreen(screen, graphics, mouseX, mouseY)) {
			ci.cancel();
			return;
		}
		if (geiler.addons.client.gui.ContainerSkins.render(screen, graphics, mouseX, mouseY)) {
			ci.cancel();
			return;
		}
		if (geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.renderCustomScreen(screen, graphics, mouseX, mouseY)) {
			ci.cancel();
			return;
		}
		if (ExperimentSolverModule.INSTANCE.renderCustomScreen(screen, graphics, mouseX, mouseY, delta)) {
			ci.cancel();
		}
	}

	/**
	 * The root extraction hook normally cancels this path already. Keep this guard as well because
	 * 26.1 has separate extraction methods: if another mixin re-enters contents extraction, the
	 * original puzzle item textures must still not appear behind the replacement surface.
	 */
	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideVanillaExperimentContents(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (ExperimentSolverModule.INSTANCE.ownsScreen(screen)) ci.cancel();
	}

	/** Draws diagnostic ids after vanilla item stacks, so the badge remains legible. */
	@Inject(method = "extractSlots", at = @At("TAIL"))
	private void geileraddons$renderSlotIds(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		CallbackInfo ci) {
		if (SlotIdsModule.INSTANCE.isEnabled()) {
			SlotIdOverlay.render((AbstractContainerScreen<?>) (Object) this, graphics);
		}
	}

	/**
	 * Custom coordinates are translated back to the original menu slot here. Rejected clicks are
	 * swallowed while the custom surface is active so a hidden vanilla slot cannot be activated.
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$clickExperimentSurface(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		AbstractContainerScreenAccess panel = (AbstractContainerScreenAccess) (Object) screen;
		if (geiler.addons.client.hud.HudManager.clickInContainer(leftPos, topPos, panel.geileraddons$getImageWidth(),
			panel.geileraddons$getImageHeight(), event.x(), event.y(), event.button())) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.rename.RenamePaintbrush.handleClick(screen, event)) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.module.impl.ChestRollModule.INSTANCE.handleMouseClick(screen)) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.handleMouseClick(screen, event)) {
			cir.setReturnValue(true);
			return;
		}
		if (geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.handleMouseClick(screen, event,
			(slot, slotId, button, input) -> ((AbstractContainerScreenAccess) (Object) screen)
				.geileraddons$invokeSlotClicked(slot, slotId, button, input))) {
			cir.setReturnValue(true);
			return;
		}
		if (ExperimentSolverModule.INSTANCE.handleCustomClick(screen, event,
			(slot, slotId, button, input) -> {
				((AbstractContainerScreenAccess) (Object) screen)
					.geileraddons$invokeSlotClicked(slot, slotId, button, input);
			})) {
			cir.setReturnValue(true);
		}
		if (!ExperimentSolverModule.INSTANCE.ownsScreen(screen)
			&& DungeonAssistModule.INSTANCE.handleSpiritLeapMouseClick(screen, event.x(), event.y(),
				event.button(),
				(slot, slotId, button, input) -> SpiritLeapSurfaceOwner.runDispatchedClick(() ->
					((AbstractContainerScreenAccess) (Object) screen)
						.geileraddons$invokeSlotClicked(slot, slotId, button, input)))) {
			cir.setReturnValue(true);
			return;
		}
		if (SpiritLeapSurfaceOwner.owns(screen)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void geileraddons$releaseSpiritLeapCard(MouseButtonEvent event,
		CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		// Vanilla would read the release against its hidden slot layout and throw the carried stack (-999).
		if (geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.ownsScreen(screen)) {
			geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.handleMouseRelease(event);
			cir.setReturnValue(true);
			return;
		}
		if (!ExperimentSolverModule.INSTANCE.ownsScreen(screen)
			&& DungeonAssistModule.INSTANCE.handleSpiritLeapMouseRelease(screen, event.x(), event.y(),
				event.button(),
				(slot, slotId, button, input) -> SpiritLeapSurfaceOwner.runDispatchedClick(() ->
					((AbstractContainerScreenAccess) (Object) screen)
						.geileraddons$invokeSlotClicked(slot, slotId, button, input)))) {
			cir.setReturnValue(true);
			return;
		}
		if (SpiritLeapSurfaceOwner.owns(screen)) {
			cir.setReturnValue(true);
		}
	}

	/** The overlay has no vanilla slots to drag over, so vanilla drag-splitting must not start under it. */
	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void geileraddons$dragStorageOverlay(MouseButtonEvent event, double dragX, double dragY,
		CallbackInfoReturnable<Boolean> cir) {
		if (geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.ownsScreen((AbstractContainerScreen<?>) (Object) this)) {
			geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE
				.handleMouseDrag((AbstractContainerScreen<?>) (Object) this, event);
			cir.setReturnValue(true);
		}
	}

	/** Keep locked slots out of the screen's drag distribution set in the first place. */
	@Inject(method = "shouldAddSlotToQuickCraft", at = @At("HEAD"), cancellable = true)
	private void geileraddons$excludeLockedSlotFromQuickCraft(Slot slot, ItemStack carried,
		CallbackInfoReturnable<Boolean> cir) {
		if (PlayerInventoryControls.INSTANCE.blocksQuickCraftTarget(Minecraft.getInstance(), slot)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * The replacement surface has no vanilla slot coordinates. Prevent a click that missed its
	 * custom panel from activating an invisible inventory slot, but keep -999 outside-click handling
	 * and the module's deliberate invoker dispatch available.
	 */
	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$handleInventoryAndDungeonSlotClick(Slot slot, int slotId, int button,
		ContainerInput input, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (geiler.addons.client.module.impl.ChestRollModule.INSTANCE.swallowsClick(screen)) {
			ci.cancel();
			return;
		}
		if (geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.onSlotClicked(screen, slotId, button)) {
			ci.cancel();
			return;
		}
		if (SpiritLeapSurfaceOwner.owns(screen) && !SpiritLeapSurfaceOwner.isDispatchingClick()) {
			ci.cancel();
			return;
		}
		if (PlayerInventoryControls.INSTANCE.blocksContainerInput(Minecraft.getInstance(), screen,
			slot, slotId, button, input)) {
			ci.cancel();
			return;
		}
		PartyFinderStatsModule.INSTANCE.onContainerSlotClick(screen, slot, button, input);
		if (slotId < 0) return;
		if (ExperimentSolverModule.INSTANCE.ownsScreen(screen)) {
			if (!ExperimentSolverModule.INSTANCE.isDispatchingCustomClick()) ci.cancel();
			return;
		}
		if (AutoExperimentsModule.INSTANCE.handleVanillaSlotClick(screen, slot, slotId, button, input,
			(dispatchSlot, dispatchSlotId, dispatchButton, dispatchInput) -> {
				((AbstractContainerScreenAccess) (Object) screen).geileraddons$invokeSlotClicked(
					dispatchSlot, dispatchSlotId, dispatchButton, dispatchInput);
			})) {
			ci.cancel();
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void geileraddons$resetExperimentSurface(CallbackInfo ci) {
		geiler.addons.client.module.impl.ChestRollModule.INSTANCE.onScreenClosed((AbstractContainerScreen<?>) (Object) this);
		geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.onScreenClosed((AbstractContainerScreen<?>) (Object) this);
		geiler.addons.client.module.impl.StorageOverlayModule.INSTANCE.onScreenClosed((AbstractContainerScreen<?>) (Object) this);
		ExperimentSolverModule.INSTANCE.onScreenClosed();
	}
}
