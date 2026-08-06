package cn.li.mcver;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;

/**
 * Cross-version BuiltInRegistries lookups.
 * Classic: {@code Registry.get}; 26.2: {@code getValue}.
 */
public final class RegistryValues {
    private RegistryValues() {
    }

    @Nullable
    public static Item getItem(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == Items.AIR ? null : item;
    }

    @Nullable
    public static Block getBlock(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == null || block == Blocks.AIR ? null : block;
    }
}
