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

    public static Block stoneBlock() {
        return Blocks.STONE;
    }

    public static Block airBlock() {
        return Blocks.AIR;
    }

    public static Item barrierItem() {
        return net.minecraft.world.item.Items.BARRIER;
    }

    public static BlockBehaviour.Properties stoneProperties() {
        return BlockBehaviour.Properties.copy(Blocks.STONE);
    }

    public static BlockBehaviour.Properties carrierBlockProperties() {
        BlockBehaviour.StatePredicate alwaysFalse = new BlockBehaviour.StatePredicate() {
            @Override
            public boolean test(BlockState state, BlockGetter level, BlockPos pos) {
                return false;
            }
        };
        return stoneProperties()
            .noOcclusion()
            .forceSolidOff()
            .isViewBlocking(alwaysFalse)
            .isSuffocating(alwaysFalse)
            .isRedstoneConductor(alwaysFalse);
    }

    public static Block createCarrierScriptedDynamicBlock(String blockId, String tileId, List<Property<?>> properties) {
        return ScriptedBlock.create(blockId, tileId, new ArrayList<>(properties), carrierBlockProperties());
    }

    public static Block createDynamicStateBlock(String blockId, List<Property<?>> properties) {
        return DynamicStateBlock.create(blockId, new ArrayList<>(properties), stoneProperties());
    }

    public static Block createCarrierScriptedBlock(String blockId, String tileId) {
        return new ScriptedBlock(blockId, tileId, carrierBlockProperties());
    }

    public static Block createPlainBlock() {
        return new Block(stoneProperties());
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

    public static boolean isAirBlock(Block block) {
        return block == null || block == Blocks.AIR;
    }

    public static Block findBlock(String namespace, String path) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
    }
}