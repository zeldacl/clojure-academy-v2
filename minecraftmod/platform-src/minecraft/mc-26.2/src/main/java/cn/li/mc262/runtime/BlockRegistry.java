package cn.li.mc262.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class BlockRegistry {
    private BlockRegistry() {}
    public static Block get(Identifier id) { return BuiltInRegistries.BLOCK.getValue(id); }
    public static String getBlockKey(Object block) {
        if (!(block instanceof Block b)) return null;
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        return id != null ? id.toString() : null;
    }
    public static boolean isPlaceableBlockItem(Object item) {
        return item instanceof BlockItem;
    }
}
