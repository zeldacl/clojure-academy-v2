package cn.li.mc262.shim;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;

public class DelegatingRecipeProvider extends RecipeProvider {
    public DelegatingRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    // Compatibility ctor used by older call sites
    public DelegatingRecipeProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> registries) {
        // RecipeProvider 26.2 no longer takes PackOutput in this shape; keep a no-op holder.
        this(HolderLookup.Provider.create(java.util.stream.Stream.empty()), null);
    }

    @Override
    protected void buildRecipes() {}
}
