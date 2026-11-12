package geiler.addons.client.access;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;

/** Runtime bridge for the private recipe-book field exposed by its screen mixin. */
public interface AbstractRecipeBookScreenAccess {
	RecipeBookComponent<?> geileraddons$getRecipeBookComponent();
}
