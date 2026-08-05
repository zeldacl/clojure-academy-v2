package cn.li.mc262.recipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

public final class ContentRecipe implements Recipe<RecipeInput> {
    public ContentRecipe() {}
    public ContentRecipe(Object a) {}
    public ContentRecipe(Object a, Object b) {}
    public ContentRecipe(Object a, Object b, Object c) {}
    public ContentRecipe(Object a, Object b, Object c, Object d) {}

    public static Object contentProcessType() { return null; }

    @Override public boolean matches(RecipeInput input, Level level) { return false; }
    @Override public ItemStack assemble(RecipeInput input) { return ItemStack.EMPTY; }
    @Override public boolean showNotification() { return false; }
    @Override public String group() { return ""; }
    @Override public RecipeSerializer<? extends Recipe<RecipeInput>> getSerializer() {
        throw new UnsupportedOperationException("ContentRecipe serializer not bound");
    }
    @Override public RecipeType<? extends Recipe<RecipeInput>> getType() {
        throw new UnsupportedOperationException("ContentRecipe type not bound");
    }
    @Override public PlacementInfo placementInfo() { return PlacementInfo.NOT_PLACEABLE; }
    @Override public RecipeBookCategory recipeBookCategory() {
        throw new UnsupportedOperationException("ContentRecipe category not bound");
    }
}
