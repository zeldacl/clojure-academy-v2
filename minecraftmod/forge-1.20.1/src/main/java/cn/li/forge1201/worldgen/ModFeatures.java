package cn.li.forge1201.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for custom world generation features.
 */
public class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
        DeferredRegister.create(ForgeRegistries.FEATURES, "my_mod");

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> PHASE_LIQUID_POOL =
        FEATURES.register("phase_liquid_pool", () -> {
            // Look up the imag_phase block from the registry at feature registration time.
            // Block registries fire before feature registries so this is safe.
            net.minecraft.world.level.block.Block block =
                ForgeRegistries.BLOCKS.getValue(new ResourceLocation("my_mod", "imag_phase"));
            BlockState blockState = (block != null && block != Blocks.AIR)
                ? block.defaultBlockState()
                : Blocks.WATER.defaultBlockState();
            return new PhaseLiquidPoolFeature(NoneFeatureConfiguration.CODEC, blockState);
        });

    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }
}
