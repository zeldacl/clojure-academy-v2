package cn.li.mc262.runtime;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class ItemInventory {
    private ItemInventory() {}
    public static ItemStack getMainHand(Inventory inv) { return inv.getSelectedItem(); }
    public static ItemStack getOffhand(Inventory inv) { return inv.getItem(Inventory.SLOT_OFFHAND); }
}
