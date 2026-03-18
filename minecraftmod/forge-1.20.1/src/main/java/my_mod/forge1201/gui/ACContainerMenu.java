package my_mod.forge1201.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.player.Player;

/**
 * Thin bridge class to expose protected AbstractContainerMenu APIs to Clojure.
 *
 * <p>We avoid clojure.lang.Reflector here because we want static resolution and
 * also because some methods are protected on AbstractContainerMenu.</p>
 */
public abstract class ACContainerMenu extends AbstractContainerMenu {
    protected ACContainerMenu(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    public void publicAddDataSlot(DataSlot slot) {
        this.addDataSlot(slot);
    }

    public void publicAddSlot(Slot slot) {
        this.addSlot(slot);
    }

    public void publicRemoved(Player player) {
        super.removed(player);
    }

    public void publicBroadcastChanges() {
        super.broadcastChanges();
    }

    public void publicClicked(int slotIndex, int button, ClickType clickType, Player player) {
        super.clicked(slotIndex, button, clickType, player);
    }

    public Slot publicGetSlot(int index) {
        return this.getSlot(index);
    }
}

