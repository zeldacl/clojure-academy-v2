package cn.li.forge1201.shim;

import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeProvider;
import clojure.lang.IFn;

import java.util.function.Consumer;

/** Universal RecipeProvider skeleton — replaces proxy sites
 *  extending RecipeProvider.  The buildRecipesFn receives
 *  the DelegatingRecipeProvider instance as first argument. */
public class DelegatingRecipeProvider extends RecipeProvider {

    private final IFn buildRecipesFn;

    public DelegatingRecipeProvider(PackOutput packOutput, IFn buildRecipesFn) {
        super(packOutput);
        this.buildRecipesFn = buildRecipesFn;
    }

    @Override protected void buildRecipes(Consumer<FinishedRecipe> writer) {
        if (buildRecipesFn != null) {
            buildRecipesFn.invoke(this, writer);
        }
    }
}
