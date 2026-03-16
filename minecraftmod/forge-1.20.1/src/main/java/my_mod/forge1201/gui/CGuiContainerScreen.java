package my_mod.forge1201.gui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;

/**
 * Small helper subclass that exposes public methods to adjust protected
 * imageWidth/imageHeight fields from Clojure code safely.
 */
public abstract class CGuiContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    public CGuiContainerScreen(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    /** Set both imageWidth and imageHeight. */
    public void setImageSize(int w, int h) {
        this.imageWidth = w;
        this.imageHeight = h;
    }

    public int getImageWidthPublic() {
        return this.imageWidth;
    }

    public int getImageHeightPublic() {
        return this.imageHeight;
    }
}
