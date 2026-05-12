package cn.li.fabric1201.shim;

import cn.li.fabric1201.block.entity.ScriptedBlockEntity;
import cn.li.mc1201.block.SharedBootstrapBlockHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.function.Function;

public final class FabricBootstrapHelper {

    private FabricBootstrapHelper() {
    }

    public static BlockBehaviour.Properties createStoneProperties() {
        return SharedBootstrapBlockHelper.createStoneProperties();
    }

    public static BlockBehaviour.Properties carrierBlockProperties(BlockBehaviour.Properties base) {
        return SharedBootstrapBlockHelper.carrierBlockProperties(base);
    }

    public static Block createCarrierScriptedDynamicBlock(String blockId,
                                                           String tileId,
                                                           List<Property<?>> properties,
                                                           BlockBehaviour.Properties blockProperties) {
        return SharedBootstrapBlockHelper.createCarrierScriptedDynamicBlock(
            blockId,
            tileId,
            properties,
            blockProperties,
            (resolvedTileId, resolvedBlockId, pos, state) -> {
                BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(resolvedTileId);
                return type != null ? new ScriptedBlockEntity(type, pos, state, resolvedTileId, resolvedBlockId) : null;
            },
            (level, pos, state, blockEntity) -> {
                if (blockEntity instanceof ScriptedBlockEntity scripted) {
                    ScriptedBlockEntity.serverTick(level, pos, state, scripted);
                }
            }
        );
    }

    public static Block createDynamicStateBlock(String blockId,
                                                List<Property<?>> properties,
                                                BlockBehaviour.Properties blockProperties) {
        return SharedBootstrapBlockHelper.createDynamicStateBlock(blockId, properties, blockProperties);
    }

    public static Block createCarrierScriptedBlock(String blockId,
                                                   String tileId,
                                                   BlockBehaviour.Properties blockProperties) {
        return SharedBootstrapBlockHelper.createCarrierScriptedBlock(
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
            }
        );
    }

    public static Block createPlainBlock(BlockBehaviour.Properties blockProperties) {
        return SharedBootstrapBlockHelper.createPlainBlock(blockProperties);
    }

    public static Item createBlockItem(Block block) {
        return SharedBootstrapBlockHelper.createBlockItem(block);
    }

    @SuppressWarnings("unchecked")
    public static BlockEntityType<?> createScriptedBlockEntityType(String tileId,
                                                                    List<Block> blocks,
                                                                    Function<Block, String> blockIdResolver) {
        return SharedBootstrapBlockHelper.createScriptedBlockEntityType(
            tileId,
            blocks,
            blockIdResolver,
            (type, pos, state, resolvedTileId, blockId) -> new ScriptedBlockEntity(type, pos, state, resolvedTileId, blockId),
            ScriptedBlockEntity::registerType
        );
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
