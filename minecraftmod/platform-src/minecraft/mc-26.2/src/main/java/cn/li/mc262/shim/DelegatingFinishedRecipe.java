package cn.li.mc262.shim;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Recipe;

public final class DelegatingFinishedRecipe {
    private DelegatingFinishedRecipe() {}
    public static void accept(Object output, Identifier id, Recipe<?> recipe) {
        // TODO 26.2 RecipeOutput.accept signature
    }
}
