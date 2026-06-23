package cn.li.forge1201.recipe;

import cn.li.forge1201.MyMod1201;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Custom recipe types and serializers for Imaginary Fusor and Metal Former.
 *
 * Both RecipeType and RecipeSerializer use DeferredRegister (Forge-standard
 * pattern) — never call vanilla RecipeType.register() directly, which writes
 * to the locked vanilla registry and throws IllegalStateException.
 */
public final class ModRecipeTypes {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
        DeferredRegister.create(Registries.RECIPE_TYPE, MyMod1201.MODID);

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.RECIPE_SERIALIZERS, MyMod1201.MODID);

    public static final RegistryObject<RecipeType<ImagFusorRecipe>> IMAG_FUSOR_TYPE =
        RECIPE_TYPES.register("imag_fusor", () -> new RecipeType<ImagFusorRecipe>() {
            @Override
            public String toString() {
                return MyMod1201.MODID + ":imag_fusor";
            }
        });

    public static final RegistryObject<RecipeType<MetalFormerRecipe>> METAL_FORMER_TYPE =
        RECIPE_TYPES.register("metal_former", () -> new RecipeType<MetalFormerRecipe>() {
            @Override
            public String toString() {
                return MyMod1201.MODID + ":metal_former";
            }
        });

    public static final RegistryObject<RecipeSerializer<ImagFusorRecipe>> IMAG_FUSOR_SERIALIZER =
        RECIPE_SERIALIZERS.register("imag_fusor", () -> new ImagFusorRecipe.Serializer());

    public static final RegistryObject<RecipeSerializer<MetalFormerRecipe>> METAL_FORMER_SERIALIZER =
        RECIPE_SERIALIZERS.register("metal_former", () -> new MetalFormerRecipe.Serializer());

    /**
     * Register both DeferredRegisters with the mod event bus.
     * Called from Clojure during mod construction.
     */
    public static void register(IEventBus modBus) {
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }

    private ModRecipeTypes() {}
}
