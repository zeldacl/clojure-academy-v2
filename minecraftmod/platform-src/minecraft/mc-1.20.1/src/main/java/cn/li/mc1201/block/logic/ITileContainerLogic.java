package cn.li.mc1201.block.logic;

import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public interface ITileContainerLogic {
    int getSize(AbstractScriptedBlockEntity be);

    ItemStack getItem(AbstractScriptedBlockEntity be, int slot);

    void setItem(AbstractScriptedBlockEntity be, int slot, ItemStack stack);

    ItemStack removeItem(AbstractScriptedBlockEntity be, int slot, int amount);

    ItemStack removeItemNoUpdate(AbstractScriptedBlockEntity be, int slot);

    void clearContent(AbstractScriptedBlockEntity be);

    boolean stillValid(AbstractScriptedBlockEntity be, Player player);

    int[] getSlotsForFace(AbstractScriptedBlockEntity be, Direction side);

    boolean canPlaceItemThroughFace(AbstractScriptedBlockEntity be, int slot, ItemStack stack, Direction side);

    boolean canTakeItemThroughFace(AbstractScriptedBlockEntity be, int slot, ItemStack stack, Direction side);
}
