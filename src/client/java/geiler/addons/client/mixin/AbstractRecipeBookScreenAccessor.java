package geiler.addons.client.mixin;

import geiler.addons.client.access.AbstractRecipeBookScreenAccess;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the active recipe-book panel so inventory buttons never occupy its visible surface. */
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor extends AbstractRecipeBookScreenAccess {
	@Accessor("recipeBookComponent")
	RecipeBookComponent<?> geileraddons$getRecipeBookComponent();
}
