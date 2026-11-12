package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PestTrackerModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports completed crop breaks and accepted crop or crop-planting interactions to Garden timers. */
@Mixin(MultiPlayerGameMode.class)
public abstract class GardenCropActionMixin {
	@Unique private boolean geileraddons$breakingCrop;
	@Unique private boolean geileraddons$usingOnCrop;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void geileraddons$captureCropBreak(BlockPos position, CallbackInfoReturnable<Boolean> cir) {
		geileraddons$breakingCrop = isCropBlock(blockAt(position));
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void geileraddons$recordCropBreak(BlockPos position, CallbackInfoReturnable<Boolean> cir) {
		boolean wasCrop = geileraddons$breakingCrop;
		geileraddons$breakingCrop = false;
		if (wasCrop && cir.getReturnValueZ()) PestTrackerModule.INSTANCE.onCropAction();
	}

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void geileraddons$captureCropUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		BlockState target = blockAt(hit.getBlockPos());
		geileraddons$usingOnCrop = isCropBlock(target) || isCropPlantingTarget(target, player.getItemInHand(hand));
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void geileraddons$recordCropUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		boolean cropAction = geileraddons$usingOnCrop;
		geileraddons$usingOnCrop = false;
		InteractionResult result = cir.getReturnValue();
		if (cropAction && result != null && result.consumesAction()) PestTrackerModule.INSTANCE.onCropAction();
	}

	@Unique
	private static BlockState blockAt(BlockPos position) {
		Minecraft client = Minecraft.getInstance();
		return client.level == null ? null : client.level.getBlockState(position);
	}

	@Unique
	private static boolean isCropBlock(BlockState state) {
		if (state == null) return false;
		var block = state.getBlock();
		return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock
			|| block instanceof SweetBerryBushBlock || block instanceof CocoaBlock || block instanceof NetherWartBlock
			|| block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof MushroomBlock
			|| block instanceof HugeMushroomBlock || state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN);
	}

	@Unique
	private static boolean isCropPlantingTarget(BlockState target, ItemStack held) {
		if (target == null) return false;
		Item item = held.getItem();
		if (target.is(Blocks.FARMLAND)) {
			return item == Items.WHEAT_SEEDS || item == Items.CARROT || item == Items.POTATO
				|| item == Items.BEETROOT_SEEDS || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS;
		}
		if (target.is(Blocks.SOUL_SAND)) return item == Items.NETHER_WART;
		if (target.is(Blocks.JUNGLE_LOG) || target.is(Blocks.STRIPPED_JUNGLE_LOG)
			|| target.is(Blocks.JUNGLE_WOOD) || target.is(Blocks.STRIPPED_JUNGLE_WOOD))
			return item == Items.COCOA_BEANS;
		if (target.is(Blocks.SAND)) return item == Items.CACTUS || item == Items.SUGAR_CANE;
		if (target.is(Blocks.DIRT) || target.is(Blocks.GRASS_BLOCK) || target.is(Blocks.PODZOL)
			|| target.is(Blocks.MYCELIUM))
			return item == Items.RED_MUSHROOM || item == Items.BROWN_MUSHROOM || item == Items.SUGAR_CANE;
		return false;
	}
}
