package cn.li.forge1201.shim;

import cn.li.forge1201.block.DynamicStateBlock;
import cn.li.forge1201.block.ScriptedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.List;

public final class ForgeBootstrapHelper {
    private ForgeBootstrapHelper() {
    }

    public static DeferredRegister<Block> createBlocksRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.BLOCKS, modId);
    }

    public static DeferredRegister<Item> createItemsRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.ITEMS, modId);
    }

    public static DeferredRegister<BlockEntityType<?>> createBlockEntityTypesRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, modId);
    }

    public static DeferredRegister<CreativeModeTab> createCreativeTabsRegister(String modId) {
        return DeferredRegister.create(Registries.CREATIVE_MODE_TAB, modId);
    }

    public static DeferredRegister<MenuType<?>> createMenusRegister(String modId) {
        return DeferredRegister.create(Registries.MENU, modId);
    }

    public static BlockBehaviour.Properties createStoneProperties() {
        return BlockBehaviour.Properties.copy(Blocks.STONE);
    }

    public static BlockBehaviour.Properties carrierBlockProperties(BlockBehaviour.Properties base) {
        BlockBehaviour.StatePredicate alwaysFalse = new BlockBehaviour.StatePredicate() {
            @Override
            public boolean test(BlockState state, BlockGetter level, BlockPos pos) {
                return false;
            }
        };
        return base.noOcclusion()
            .forceSolidOff()
            .isViewBlocking(alwaysFalse)
            .isSuffocating(alwaysFalse)
            .isRedstoneConductor(alwaysFalse);
    }

    public static Block createCarrierScriptedDynamicBlock(String blockId, String tileId, List<Property<?>> properties, BlockBehaviour.Properties blockProperties) {
        return ScriptedBlock.create(blockId, tileId, new ArrayList<>(properties), blockProperties);
    }

    public static Block createDynamicStateBlock(String blockId, List<Property<?>> properties, BlockBehaviour.Properties blockProperties) {
        return DynamicStateBlock.create(blockId, new ArrayList<>(properties), blockProperties);
    }

    public static Block createCarrierScriptedBlock(String blockId, String tileId, BlockBehaviour.Properties blockProperties) {
        return new ScriptedBlock(blockId, tileId, blockProperties);
    }

    public static Block createPlainBlock(BlockBehaviour.Properties blockProperties) {
        return new Block(blockProperties);
    }

    @SuppressWarnings("unchecked")
    public static BlockEntityType<?> createScriptedBlockEntityType(String tileId,
                                                                   List<Block> blocks,
                                                                   Function<Block, String> blockIdResolver) {
        Block[] blockArray = blocks.toArray(new Block[0]);
        BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity>[] typeHolder =
            (BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity>[]) new BlockEntityType<?>[1];
        BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity> beType =
            BlockEntityType.Builder.of(
                (pos, state) -> {
                    Block blockInst = state.getBlock();
                    String blockId = blockIdResolver.apply(blockInst);
                    if (blockId == null) {
                        blockId = tileId;
                    }
                    return new cn.li.forge1201.block.entity.ScriptedBlockEntity(typeHolder[0], pos, state, tileId, blockId);
                },
                blockArray
            ).build(null);
        typeHolder[0] = beType;
        cn.li.forge1201.block.entity.ScriptedBlockEntity.registerType(tileId, beType);
        return beType;
    }

    public static boolean isAirBlock(Block block, Block airBlock) {
        return block == null || block == airBlock;
    }

    public static Block findBlock(String namespace, String path) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
    }
}