package cn.li.forge1201.shim;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.function.Function;

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
