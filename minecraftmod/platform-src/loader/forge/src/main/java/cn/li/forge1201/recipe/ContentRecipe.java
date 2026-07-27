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

/** Generic Forge adapter for content-defined machine recipes. */
public final class ContentRecipe implements Recipe<SimpleContainer> {
    private final ResourceLocation id; private final Ingredient input; private final ItemStack output;
    private final int consumeLiquid; private final int craftTime; private final String mode; private final String kind;
    public ContentRecipe(ResourceLocation id, Ingredient input, ItemStack output, int consumeLiquid, int craftTime, String mode, String kind) {
        this.id=id; this.input=input; this.output=output; this.consumeLiquid=consumeLiquid; this.craftTime=craftTime; this.mode=mode; this.kind=kind;
    }
    public boolean matches(SimpleContainer c, Level l) { return input.test(c.getItem(0)); }
    public ItemStack assemble(SimpleContainer c, RegistryAccess a) { return output.copy(); }
    public boolean canCraftInDimensions(int w,int h) { return true; }
    public ItemStack getResultItem(RegistryAccess a) { return output.copy(); }
    public Ingredient getInput() { return input; } public ItemStack getOutput() { return output; }
    public int getConsumeLiquid() { return consumeLiquid; } public int getCraftTime() { return craftTime; }
    public String getMode() { return mode; } public ResourceLocation getId() { return id; }
    public RecipeSerializer<?> getSerializer() { return kind.equals("mode") ? ModRecipeTypes.CONTENT_MODE_SERIALIZER.get() : ModRecipeTypes.CONTENT_PROCESS_SERIALIZER.get(); }
    public RecipeType<?> getType() { return kind.equals("mode") ? ModRecipeTypes.CONTENT_MODE_TYPE.get() : ModRecipeTypes.CONTENT_PROCESS_TYPE.get(); }

    public static final class Serializer implements RecipeSerializer<ContentRecipe> {
        private final String kind;
        public Serializer(String kind) { this.kind=kind; }
        public ContentRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient input=Ingredient.fromJson(GsonHelper.getAsJsonObject(json,"input"));
            ItemStack output=ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json,"output"));
            return new ContentRecipe(id,input,output,GsonHelper.getAsInt(json,"consume_liquid",0),GsonHelper.getAsInt(json,"craft_time",200),GsonHelper.getAsString(json,"mode",""),kind);
        }
        public ContentRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient input=Ingredient.fromNetwork(buf); ItemStack output=buf.readItem(); int liquid=buf.readVarInt(); int time=buf.readVarInt(); String mode=kind.equals("mode")?buf.readUtf():"";
            return new ContentRecipe(id,input,output,liquid,time,mode,kind);
        }
        public void toNetwork(FriendlyByteBuf buf, ContentRecipe recipe) { recipe.input.toNetwork(buf); buf.writeItem(recipe.output); buf.writeVarInt(recipe.consumeLiquid); buf.writeVarInt(recipe.craftTime); if(kind.equals("mode")) buf.writeUtf(recipe.mode); }
    }
}
