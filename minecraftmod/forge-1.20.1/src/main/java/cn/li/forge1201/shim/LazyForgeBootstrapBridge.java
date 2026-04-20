package cn.li.forge1201.shim;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LazyForgeBootstrapBridge {
    private LazyForgeBootstrapBridge() {
    }

    public static Object createStoneProperties() {
        return ForgeBootstrapHelper.createStoneProperties();
    }

    public static Object carrierBlockProperties(Object base) {
        return ForgeBootstrapHelper.carrierBlockProperties((BlockBehaviour.Properties) base);
    }

    public static Object createBlocksRegister(String modId) {
        return ForgeBootstrapHelper.createBlocksRegister(modId);
    }

    public static Object createItemsRegister(String modId) {
        return ForgeBootstrapHelper.createItemsRegister(modId);
    }

    public static Object createCreativeTabsRegister(String modId) {
        return ForgeBootstrapHelper.createCreativeTabsRegister(modId);
    }

    public static Object createBlockEntityTypesRegister(String modId) {
        return ForgeBootstrapHelper.createBlockEntityTypesRegister(modId);
    }

    public static Object createMenusRegister(String modId) {
        return ForgeBootstrapHelper.createMenusRegister(modId);
    }

    public static Object createFluidTypesRegister(String modId) {
        return ForgeBootstrapHelper.createFluidTypesRegister(modId);
    }

    public static Object createFluidsRegister(String modId) {
        return ForgeBootstrapHelper.createFluidsRegister(modId);
    }

    public static Object createSoundsRegister(String modId) {
        return ForgeBootstrapHelper.createSoundsRegister(modId);
    }

    public static Object createEffectsRegister(String modId) {
        return ForgeBootstrapHelper.createEffectsRegister(modId);
    }

    public static Object createParticleTypesRegister(String modId) {
        return ForgeBootstrapHelper.createParticleTypesRegister(modId);
    }

    public static Object createCarrierScriptedDynamicBlock(String blockId, String tileId, List<Property<?>> properties, Object blockProperties) {
        return ForgeBootstrapHelper.createCarrierScriptedDynamicBlock(blockId, tileId, properties, (BlockBehaviour.Properties) blockProperties);
    }

    public static Object createDynamicStateBlock(String blockId, List<Property<?>> properties, Object blockProperties) {
        return ForgeBootstrapHelper.createDynamicStateBlock(blockId, properties, (BlockBehaviour.Properties) blockProperties);
    }

    public static Object createCarrierScriptedBlock(String blockId, String tileId, Object blockProperties) {
        return ForgeBootstrapHelper.createCarrierScriptedBlock(blockId, tileId, (BlockBehaviour.Properties) blockProperties);
    }

    public static Object createPlainBlock(Object blockProperties) {
        return ForgeBootstrapHelper.createPlainBlock((BlockBehaviour.Properties) blockProperties);
    }

    public static Object createFluidType(int luminosity,
                                         int density,
                                         int viscosity,
                                         int temperature,
                                         boolean canHydrate,
                                         boolean supportsBoating,
                                         String stillTexture,
                                         String flowingTexture,
                                         String overlayTexture,
                                         int tintColor) {
        return ForgeBootstrapHelper.createFluidType(
            luminosity,
            density,
            viscosity,
            temperature,
            canHydrate,
            supportsBoating,
            stillTexture,
            flowingTexture,
            overlayTexture,
            tintColor
        );
    }

    public static Object createFlowingFluidProperties(
        Supplier<FluidType> fluidTypeSupplier,
        Supplier<? extends FlowingFluid> sourceSupplier,
        Supplier<? extends FlowingFluid> flowingSupplier,
        Supplier<? extends net.minecraft.world.item.Item> bucketSupplier,
        Supplier<? extends LiquidBlock> blockSupplier,
        int slopeFindDistance,
        int levelDecreasePerBlock,
        int tickRate,
        float explosionResistance,
        boolean canConvertToSource
    ) {
        return ForgeBootstrapHelper.createFlowingFluidProperties(
            fluidTypeSupplier,
            sourceSupplier,
            flowingSupplier,
            bucketSupplier,
            blockSupplier,
            slopeFindDistance,
            levelDecreasePerBlock,
            tickRate,
            explosionResistance,
            canConvertToSource
        );
    }

    public static Object createSourceFluid(Object properties) {
        return ForgeBootstrapHelper.createSourceFluid((ForgeFlowingFluid.Properties) properties);
    }

    public static Object createFlowingFluid(Object properties) {
        return ForgeBootstrapHelper.createFlowingFluid((ForgeFlowingFluid.Properties) properties);
    }

    public static Object createLiquidBlock(Supplier<? extends FlowingFluid> fluidSupplier) {
        return ForgeBootstrapHelper.createLiquidBlock(fluidSupplier);
    }

    public static Object createFluidBucket(Supplier<? extends Fluid> fluidSupplier) {
        return ForgeBootstrapHelper.createFluidBucket(fluidSupplier);
    }

    public static Object createEntityType(String fullId,
                                          Class<?> entityClass,
                                          String category,
                                          float width,
                                          float height,
                                          int clientTrackingRange,
                                          int updateInterval,
                                          boolean fireImmune) {
        return ForgeBootstrapHelper.createEntityType(
            fullId,
            entityClass,
            category,
            width,
            height,
            clientTrackingRange,
            updateInterval,
            fireImmune
        );
    }

    public static Object createEntityTypeByKind(String fullId,
                                                String entityKind,
                                                String category,
                                                float width,
                                                float height,
                                                int clientTrackingRange,
                                                int updateInterval,
                                                boolean fireImmune) {
        return ForgeBootstrapHelper.createEntityTypeByKind(
            fullId,
            entityKind,
            category,
            width,
            height,
            clientTrackingRange,
            updateInterval,
            fireImmune
        );
    }

    public static Object createScriptedBlockEntityType(String tileId, List<Block> blocks, Function<Block, String> blockIdResolver) {
        return ForgeBootstrapHelper.createScriptedBlockEntityType(tileId, blocks, blockIdResolver);
    }

    public static Object findBlock(String namespace, String path) {
        return ForgeBootstrapHelper.findBlock(namespace, path);
    }

    public static Object getAirBlock() {
        return ForgeBootstrapHelper.getAirBlock();
    }

    public static boolean isAirBlock(Object block, Object airBlock) {
        return ForgeBootstrapHelper.isAirBlock((Block) block, (Block) airBlock);
    }
}
