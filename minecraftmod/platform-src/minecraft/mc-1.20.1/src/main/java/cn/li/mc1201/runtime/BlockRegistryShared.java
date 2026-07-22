package cn.li.mc1201.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class BlockRegistryShared {
    private BlockRegistryShared() {
    }

    public static Block getBlockById(String blockId) {
        try {
            if (blockId == null || blockId.isEmpty()) {
                return null;
            }
            ResourceLocation id = new ResourceLocation(blockId);
            Block block = BuiltInRegistries.BLOCK.get(id);
            return block == Blocks.AIR ? null : block;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Block findBlock(String namespace, String path) {
        try {
            if (namespace == null || namespace.isEmpty() || path == null || path.isEmpty()) {
                return null;
            }
            ResourceLocation id = new ResourceLocation(namespace, path);
            return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getBlockKey(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.toString() : null;
    }

    public static boolean isAirBlock(Block block, Block airBlock) {
        return block == null || block == airBlock;
    }

    public static Block getAirBlock() {
        return Blocks.AIR;
    }

    public static Block blockByItem(Item item) {
        if (item == null) {
            return null;
        }
        return Block.byItem(item);
    }

    public static boolean isPlaceableBlockItem(Item item) {
        Block block = blockByItem(item);
        return block != null && block != Blocks.AIR;
    }
}
