package my_mod.forge1201.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;

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
}

