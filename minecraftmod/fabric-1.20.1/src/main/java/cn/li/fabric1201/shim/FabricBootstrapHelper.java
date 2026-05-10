package cn.li.fabric1201.shim;

import cn.li.fabric1201.block.entity.ScriptedBlockEntity;
import cn.li.mc1201.block.SharedDynamicStateBlock;
import cn.li.mc1201.block.SharedScriptedBlock;
import cn.li.mc1201.block.ScriptedRenderShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class FabricBootstrapHelper {

    private FabricBootstrapHelper() {
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

    public static Block createCarrierScriptedDynamicBlock(String blockId,
                                                           String tileId,
                                                           List<Property<?>> properties,
                                                           BlockBehaviour.Properties blockProperties) {
        return SharedScriptedBlock.create(
            blockId,
            tileId,
            new ArrayList<>(properties),
            blockProperties,
            (resolvedTileId, resolvedBlockId, pos, state) -> {
                BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(resolvedTileId);
                return type != null ? new ScriptedBlockEntity(type, pos, state, resolvedTileId, resolvedBlockId) : null;
            },
            (level, pos, state, blockEntity) -> {
                if (blockEntity instanceof ScriptedBlockEntity scripted) {
                    ScriptedBlockEntity.serverTick(level, pos, state, scripted);
                }
            },
            (resolvedBlockId, state) -> ScriptedRenderShapes.resolveDefault(resolvedBlockId)
        );
    }

    public static Block createDynamicStateBlock(String blockId,
                                                List<Property<?>> properties,
                                                BlockBehaviour.Properties blockProperties) {
        return SharedDynamicStateBlock.create(blockId, new ArrayList<>(properties), blockProperties);
    }

    public static Block createCarrierScriptedBlock(String blockId,
                                                   String tileId,
                                                   BlockBehaviour.Properties blockProperties) {
        return new SharedScriptedBlock(
            blockId,
            tileId,
            blockProperties,
            (resolvedTileId, resolvedBlockId, pos, state) -> {
                BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(resolvedTileId);
                return type != null ? new ScriptedBlockEntity(type, pos, state, resolvedTileId, resolvedBlockId) : null;
            },
            (level, pos, state, blockEntity) -> {
                if (blockEntity instanceof ScriptedBlockEntity scripted) {
                    ScriptedBlockEntity.serverTick(level, pos, state, scripted);
                }
            },
            (resolvedBlockId, state) -> ScriptedRenderShapes.resolveDefault(resolvedBlockId)
        );
    }

    public static Block createPlainBlock(BlockBehaviour.Properties blockProperties) {
        return new Block(blockProperties);
    }

    public static Item createBlockItem(Block block) {
        return new BlockItem(block, new Item.Properties());
    }

    @SuppressWarnings("unchecked")
    public static BlockEntityType<?> createScriptedBlockEntityType(String tileId,
                                                                    List<Block> blocks,
                                                                    Function<Block, String> blockIdResolver) {
        Block[] blockArray = blocks.toArray(new Block[0]);
        BlockEntityType<ScriptedBlockEntity>[] typeHolder =
            (BlockEntityType<ScriptedBlockEntity>[]) new BlockEntityType<?>[1];
        BlockEntityType<ScriptedBlockEntity> beType =
            BlockEntityType.Builder.of(
                (pos, state) -> {
                    Block blockInst = state.getBlock();
                    String blockId = blockIdResolver.apply(blockInst);
                    if (blockId == null) {
                        blockId = tileId;
                    }
                    return new ScriptedBlockEntity(typeHolder[0], pos, state, tileId, blockId);
                },
                blockArray
            ).build(null);
        typeHolder[0] = beType;
        ScriptedBlockEntity.registerType(tileId, beType);
        return beType;
    }

    public static Block registerBlock(String modId, String id, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation(modId, id), block);
    }

    public static Item registerItem(String modId, String id, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(modId, id), item);
    }

    public static BlockEntityType<?> registerBlockEntityType(String modId, String id, BlockEntityType<?> type) {
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, new ResourceLocation(modId, id), type);
    }
}
