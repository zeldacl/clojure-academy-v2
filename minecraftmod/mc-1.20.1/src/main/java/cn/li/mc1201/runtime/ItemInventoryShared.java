package cn.li.mc1201.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemInventoryShared {
    private ItemInventoryShared() {
    }

    public static boolean isItemStackEmpty(Object stack) {
        return ((ItemStack) stack).isEmpty();
    }

    public static Object getItemFromStack(Object stack) {
        return ((ItemStack) stack).getItem();
    }

    public static int getItemStackCount(Object stack) {
        return ((ItemStack) stack).getCount();
    }

    public static String getItemKeyString(Object item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey((Item) item);
        return key != null ? key.getNamespace() + ":" + key.getPath() : null;
    }

    public static int countPlayerItem(Player player, Item item) {
        if (player == null || item == null) {
            return 0;
        }
        int total = 0;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean consumePlayerItem(Player player, Item item, int amount) {
        if (player == null || item == null || amount <= 0) {
            return false;
        }
        int remaining = amount;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    public static boolean givePlayerItemStack(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        ItemStack copy = stack.copy();
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
        return true;
    }
}
