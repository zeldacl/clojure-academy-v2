package cn.li.mc262.shim;

import cn.li.mc262.gui.CMenuBridge;
import clojure.lang.IFn;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Universal CMenuBridge skeleton for Clojure callbacks.
 * 26.2: clicked takes {@link ContainerInput} instead of ClickType.
 */
public class DelegatingCMenuBridge extends CMenuBridge {

    private IFn stillValidFn;
    private IFn removedFn;
    private IFn broadcastChangesFn;
    private IFn clickedFn;
    private IFn quickMoveStackFn;

    public DelegatingCMenuBridge(MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    public DelegatingCMenuBridge withStillValid(IFn fn) { this.stillValidFn = fn; return this; }
    public DelegatingCMenuBridge withRemoved(IFn fn) { this.removedFn = fn; return this; }
    public DelegatingCMenuBridge withBroadcastChanges(IFn fn) { this.broadcastChangesFn = fn; return this; }
    public DelegatingCMenuBridge withClicked(IFn fn) { this.clickedFn = fn; return this; }
    public DelegatingCMenuBridge withQuickMoveStack(IFn fn) { this.quickMoveStackFn = fn; return this; }

    @Override
    public boolean stillValid(Player player) {
        if (stillValidFn != null) {
            Object r = stillValidFn.invoke(this, player);
            return r instanceof Boolean ? (Boolean) r : true;
        }
        return true;
    }

    @Override
    public void removed(Player player) {
        if (removedFn != null) {
            removedFn.invoke(this, player);
        } else {
            super.removed(player);
        }
    }

    @Override
    public void broadcastChanges() {
        if (broadcastChangesFn != null) {
            broadcastChangesFn.invoke(this);
        } else {
            super.broadcastChanges();
        }
    }

    @Override
    public void clicked(int slotIndex, int button, ContainerInput clickType, Player player) {
        if (clickedFn != null) {
            clickedFn.invoke(this, slotIndex, button, clickType, player);
        } else {
            super.clicked(slotIndex, button, clickType, player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (quickMoveStackFn != null) {
            return (ItemStack) quickMoveStackFn.invoke(this, player, slotIndex);
        }
        return ItemStack.EMPTY;
    }
}
