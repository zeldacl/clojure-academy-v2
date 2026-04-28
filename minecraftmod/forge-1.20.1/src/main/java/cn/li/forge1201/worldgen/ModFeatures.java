package cn.li.forge1201.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
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

    private static BlockState phaseLiquidBlockState = null;

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> PHASE_LIQUID_POOL =
        FEATURES.register("phase_liquid_pool", () -> {
            if (phaseLiquidBlockState == null) {
                throw new IllegalStateException("Phase liquid block state not set! Call setPhaseLiquidBlock first.");
            }
            return new PhaseLiquidPoolFeature(NoneFeatureConfiguration.CODEC, phaseLiquidBlockState);
        });

    /**
     * Set the phase liquid block state to use for pool generation.
     * Must be called before feature registration.
     */
    public static void setPhaseLiquidBlock(BlockState blockState) {
        phaseLiquidBlockState = blockState;
    }

    /**
     * Set the phase liquid block to use for pool generation.
     * Must be called before feature registration.
     */
    public static void setPhaseLiquidBlock(Block block) {
        setPhaseLiquidBlock(block.defaultBlockState());
    }

    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }
}
