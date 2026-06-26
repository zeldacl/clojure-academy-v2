package cn.li.forge1201.worldgen;

import cn.li.mc1201.worldgen.ConfigurablePoolFeature;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Registry for custom world generation features.
 * Original AcademyCraft had genOres + genPhaseLiquid worldgen config.
 * Phase liquid uses the configurable_pool feature with imag_phase as fill block.
 */
public class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
        DeferredRegister.create(Registries.FEATURE, "my_mod");

    /**
     * Phase-liquid pool feature.  Resolves the imag_phase block at registration
     * time (after block registration) so we don't need Java-level access to the
     * Clojure-registered block class.
     */
    private static final Supplier<Block> PHASE_LIQUID_SUPPLIER = () -> {
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation("my_mod", "imag_phase"));
        return block != null ? block : Blocks.WATER;
    };

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> CONFIGURABLE_POOL =
        FEATURES.register("configurable_pool", () ->
            new ConfigurablePoolFeature(NoneFeatureConfiguration.CODEC,
                PHASE_LIQUID_SUPPLIER.get().defaultBlockState()));

    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }
}
