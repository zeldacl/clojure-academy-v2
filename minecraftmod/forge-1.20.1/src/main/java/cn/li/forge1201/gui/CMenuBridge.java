package cn.li.forge1201.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;

/**
 * Bridge menu class exposing protected AbstractContainerMenu APIs as public
 * methods for Clojure proxy implementations.
 */
public abstract class CMenuBridge extends AbstractContainerMenu {
    protected CMenuBridge(MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    public Slot addSlotPublic(Slot slot) {
        return super.addSlot(slot);
    }

    public DataSlot addDataSlotPublic(DataSlot dataSlot) {
        return super.addDataSlot(dataSlot);
    }

    public void callSuperRemoved(Player player) {
        super.removed(player);
    }

    public void callSuperBroadcastChanges() {
        super.broadcastChanges();
    }

    public void callSuperClicked(int slotIndex, int button, ClickType clickType, Player player) {
        super.clicked(slotIndex, button, clickType, player);
    }
}
