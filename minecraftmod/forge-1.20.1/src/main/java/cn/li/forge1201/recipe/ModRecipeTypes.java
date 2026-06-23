package cn.li.forge1201.recipe;

import cn.li.forge1201.MyMod1201;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Custom recipe types and serializers for Imaginary Fusor and Metal Former.
 *
 * Registration flow:
 *   1. RECIPE_SERIALIZERS DeferredRegister is created (static init).
 *   2. Serializer RegistryObject entries are defined (static init).
 *   3. RecipeType constants are registered (static init, via RegistryObject).
 *   4. Clojure mod.clj calls {@link #register(IEventBus)} during mod construction.
 */
public final class ModRecipeTypes {

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MyMod1201.MODID);

    public static final RegistryObject<RecipeSerializer<ImagFusorRecipe>> IMAG_FUSOR_SERIALIZER =
        RECIPE_SERIALIZERS.register("imag_fusor", () -> new ImagFusorRecipe.Serializer());

    public static final RegistryObject<RecipeSerializer<MetalFormerRecipe>> METAL_FORMER_SERIALIZER =
        RECIPE_SERIALIZERS.register("metal_former", () -> new MetalFormerRecipe.Serializer());

    public static final RecipeType<ImagFusorRecipe> IMAG_FUSOR_TYPE =
        RecipeType.register(MyMod1201.MODID + ":imag_fusor");

    public static final RecipeType<MetalFormerRecipe> METAL_FORMER_TYPE =
        RecipeType.register(MyMod1201.MODID + ":metal_former");

    /**
     * Register the RECIPE_SERIALIZERS DeferredRegister with the mod event bus.
     * Called from Clojure during mod construction via ForgeBootstrapHelper.
     */
    public static void register(IEventBus modBus) {
        RECIPE_SERIALIZERS.register(modBus);
    }

    private ModRecipeTypes() {}
}
