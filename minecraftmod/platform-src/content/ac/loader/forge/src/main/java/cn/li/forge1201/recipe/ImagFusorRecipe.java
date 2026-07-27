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
 * Imaginary Fusor recipe — processes low-purity crystal into mid/high-purity
 * crystal using Imag Energy and Imag Phase Liquid.
 *
 * Forge 1.20.1 API: uses Json-based serialization (no Codec in this version).
 */
public class ImagFusorRecipe implements Recipe<SimpleContainer> {

    private final ResourceLocation id;
    private final Ingredient input;
    private final ItemStack output;
    private final int consumeLiquid;
    private final int craftTime;

    public ImagFusorRecipe(ResourceLocation id, Ingredient input, ItemStack output,
                           int consumeLiquid, int craftTime) {
        this.id = id;
        this.input = input;
        this.output = output;
        this.consumeLiquid = consumeLiquid;
        this.craftTime = craftTime;
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

    public int getConsumeLiquid() {
        return consumeLiquid;
    }

    public int getCraftTime() {
        return craftTime;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.IMAG_FUSOR_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.IMAG_FUSOR_TYPE.get();
    }

    public static class Serializer implements RecipeSerializer<ImagFusorRecipe> {
        @Override
        public ImagFusorRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient input = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "input"));
            ItemStack output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "output"));
            int consumeLiquid = GsonHelper.getAsInt(json, "consume_liquid", 0);
            int craftTime = GsonHelper.getAsInt(json, "craft_time", 200);
            return new ImagFusorRecipe(id, input, output, consumeLiquid, craftTime);
        }

        @Override
        public ImagFusorRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient input = Ingredient.fromNetwork(buf);
            ItemStack output = buf.readItem();
            int consumeLiquid = buf.readVarInt();
            int craftTime = buf.readVarInt();
            return new ImagFusorRecipe(id, input, output, consumeLiquid, craftTime);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ImagFusorRecipe recipe) {
            recipe.input.toNetwork(buf);
            buf.writeItem(recipe.output);
            buf.writeVarInt(recipe.consumeLiquid);
            buf.writeVarInt(recipe.craftTime);
        }
    }
}
