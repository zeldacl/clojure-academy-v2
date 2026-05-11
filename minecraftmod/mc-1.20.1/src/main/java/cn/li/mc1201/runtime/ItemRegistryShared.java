package cn.li.mc1201.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ItemRegistryShared {
    private ItemRegistryShared() {
    }

    public static String getItemRegistryPath(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.getPath() : null;
    }

    public static Item getItemById(String itemId) {
        try {
            if (itemId == null || itemId.isEmpty()) {
                return null;
            }
            ResourceLocation id = new ResourceLocation(itemId);
            Item item = BuiltInRegistries.ITEM.get(id);
            return item == Items.AIR ? null : item;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ItemStack createItemStackById(String itemId, int count) {
        Item item = getItemById(itemId);
        if (item == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }
}
