package cn.li.mc262.runtime;

import cn.li.mcver.ResourceLocations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class BlockRegistry {
    private BlockRegistry() {}

    public static Block get(Identifier id) {
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    public static Block getBlockById(String blockId) {
        try {
            if (blockId == null || blockId.isEmpty()) return null;
            Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocations.parse(blockId));
            return block == Blocks.AIR ? null : block;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Block findBlock(String namespace, String path) {
        try {
            if (namespace == null || namespace.isEmpty() || path == null || path.isEmpty()) return null;
            return BuiltInRegistries.BLOCK.getValue(ResourceLocations.of(namespace, path));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getBlockKey(Object block) {
        if (!(block instanceof Block b)) return null;
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        return id != null ? id.toString() : null;
    }

    public static boolean isAirBlock(Block block, Block airBlock) {
        return block == null || block == airBlock;
    }

    public static Block getAirBlock() {
        return Blocks.AIR;
    }

    public static Block blockByItem(Item item) {
        if (item == null) return null;
        return Block.byItem(item);
    }

    public static boolean isPlaceableBlockItem(Object item) {
        if (!(item instanceof Item i)) return false;
        Block block = blockByItem(i);
        return block != null && block != Blocks.AIR;
    }
}
