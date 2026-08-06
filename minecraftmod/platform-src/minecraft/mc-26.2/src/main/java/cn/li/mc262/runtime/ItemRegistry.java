package cn.li.mc262.runtime;

import cn.li.mcbase.runtime.ItemPlayerOps;

import cn.li.mcver.ResourceLocations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ItemRegistry {
    static {
        ItemPlayerOps.installItemIdLookup(ItemRegistry::getItemById);
    }

    private ItemRegistry() {}

    public static Item get(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    public static Item getItemById(String itemId) {
        try {
            if (itemId == null || itemId.isEmpty()) return null;
            Item item = BuiltInRegistries.ITEM.getValue(ResourceLocations.parse(itemId));
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

    public static String getItemKey(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id != null ? id.toString() : null;
    }
}
