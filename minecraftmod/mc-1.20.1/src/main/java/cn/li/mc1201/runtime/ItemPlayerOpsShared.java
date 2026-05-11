package cn.li.mc1201.runtime;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemPlayerOpsShared {
    private ItemPlayerOpsShared() {
    }

    public static int countPlayerItemById(Object playerObj, String itemId) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty()) {
            return 0;
        }
        Item item = ItemRegistryShared.getItemById(itemId);
        if (item == null) {
            return 0;
        }
        return ItemInventoryShared.countPlayerItem(player, item);
    }

    public static boolean consumePlayerItemById(Object playerObj, String itemId, int amount) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty() || amount <= 0) {
            return false;
        }
        Item item = ItemRegistryShared.getItemById(itemId);
        if (item == null) {
            return false;
        }
        return ItemInventoryShared.consumePlayerItem(player, item, amount);
    }

    public static boolean givePlayerItemStack(Object playerObj, Object stackObj) {
        if (!(playerObj instanceof Player player) || !(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        return ItemInventoryShared.givePlayerItemStack(player, stack);
    }
}
