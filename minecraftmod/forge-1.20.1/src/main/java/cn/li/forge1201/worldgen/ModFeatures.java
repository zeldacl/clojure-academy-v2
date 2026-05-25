package cn.li.forge1201.worldgen;

import cn.li.mc1201.worldgen.ConfigurablePoolFeature;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for custom world generation features.
 */
public class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
        DeferredRegister.create(Registries.FEATURE, "my_mod");

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> CONFIGURABLE_POOL =
        FEATURES.register("configurable_pool", () ->
            new ConfigurablePoolFeature(NoneFeatureConfiguration.CODEC, Blocks.WATER.defaultBlockState()));

    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }
}
