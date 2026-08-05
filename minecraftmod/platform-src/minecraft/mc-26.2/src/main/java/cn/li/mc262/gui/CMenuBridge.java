package cn.li.mc262.gui;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared menu bridge exposing protected AbstractContainerMenu APIs as public
 * methods for Clojure proxy implementations.
 *
 * 26.2: {@code ClickType} renamed to {@link ContainerInput}.
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

    public void callSuperClicked(int slotIndex, int button, ContainerInput clickType, Player player) {
        super.clicked(slotIndex, button, clickType, player);
    }

    public boolean callSuperMoveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        return super.moveItemStackTo(stack, startIndex, endIndex, reverseDirection);
    }

    /**
     * The Clojure container backing this menu. Set by menu/proxy.clj at creation.
     */
    public Object cljContainer;
}
