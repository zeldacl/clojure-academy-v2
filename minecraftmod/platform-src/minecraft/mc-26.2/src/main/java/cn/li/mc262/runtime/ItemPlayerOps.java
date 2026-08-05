package cn.li.mc262.runtime;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemPlayerOps {
    private ItemPlayerOps() {}
    public static Item getItemById(String id) { return ItemRegistry.get(net.minecraft.resources.Identifier.parse(id)); }
    public static int countPlayerItem(Player player, Item item) { return 0; }
    public static void consumePlayerItem(Player player, Item item, int count) {}
    public static void givePlayerItemStack(Player player, ItemStack stack) { player.getInventory().add(stack); }
}
