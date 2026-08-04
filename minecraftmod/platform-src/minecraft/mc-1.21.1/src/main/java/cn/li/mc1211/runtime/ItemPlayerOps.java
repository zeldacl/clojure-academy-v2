package cn.li.mc1211.runtime;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemPlayerOps {
    private ItemPlayerOps() {
    }

    public static int countPlayerItemById(Object playerObj, String itemId) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty()) {
            return 0;
        }
        Item item = ItemRegistry.getItemById(itemId);
        if (item == null) {
            return 0;
        }
        return ItemInventory.countPlayerItem(player, item);
    }

    public static boolean consumePlayerItemById(Object playerObj, String itemId, int amount) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty() || amount <= 0) {
            return false;
        }
        Item item = ItemRegistry.getItemById(itemId);
        if (item == null) {
            return false;
        }
        return ItemInventory.consumePlayerItem(player, item, amount);
    }

    public static boolean givePlayerItemStack(Object playerObj, Object stackObj) {
        if (!(playerObj instanceof Player player) || !(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        return ItemInventory.givePlayerItemStack(player, stack);
    }
}
