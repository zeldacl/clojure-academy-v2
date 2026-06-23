package cn.li.forge1201.recipe;

import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * Metal Former recipe — processes metal ingots/items through 4 modes:
 * plate, incise, etch, refine.
 *
 * Forge 1.20.1 API: uses Json-based serialization (no Codec in this version).
 */
public class MetalFormerRecipe implements Recipe<SimpleContainer> {

    private final ResourceLocation id;
    private final Ingredient input;
    private final ItemStack output;
    private final String mode;

    public MetalFormerRecipe(ResourceLocation id, Ingredient input, ItemStack output, String mode) {
        this.id = id;
        this.input = input;
        this.output = output;
        this.mode = mode;
    }

    @Override
    public boolean matches(SimpleContainer container, Level level) {
        return input.test(container.getItem(0));
    }

    @Override
    public ItemStack assemble(SimpleContainer container, RegistryAccess registryAccess) {
        return output.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return output.copy();
    }

    public Ingredient getInput() {
        return input;
    }

    public ItemStack getOutput() {
        return output;
    }

    public String getMode() {
        return mode;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.METAL_FORMER_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.METAL_FORMER_TYPE;
    }

    public static class Serializer implements RecipeSerializer<MetalFormerRecipe> {
        @Override
        public MetalFormerRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient input = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "input"));
            ItemStack output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "output"));
            String mode = GsonHelper.getAsString(json, "mode");
            return new MetalFormerRecipe(id, input, output, mode);
        }

        @Override
        public MetalFormerRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient input = Ingredient.fromNetwork(buf);
            ItemStack output = buf.readItem();
            String mode = buf.readUtf();
            return new MetalFormerRecipe(id, input, output, mode);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, MetalFormerRecipe recipe) {
            recipe.input.toNetwork(buf);
            buf.writeItem(recipe.output);
            buf.writeUtf(recipe.mode);
        }
    }
}
