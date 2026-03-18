package my_mod.forge1201.gui;

import net.minecraft.client.gui.GuiGraphics;
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

    public int getLeftPosPublic() {
        return this.leftPos;
    }

    public int getTopPosPublic() {
        return this.topPos;
    }

    public void initPublic() {
        super.init();
    }

    public void onClosePublic() {
        super.onClose();
    }

    // Public wrappers for protected AbstractContainerScreen behavior.
    public void renderPublic(GuiGraphics gg, int mouseX, int mouseY, float partialTicks) {
        super.render(gg, mouseX, mouseY, partialTicks);
    }

    public void renderBgPublic(GuiGraphics gg, float partialTicks, int mouseX, int mouseY) {
        // AbstractContainerScreen#renderBg is abstract; dispatch to the override
        // implemented by the concrete screen (our Clojure proxy overrides it).
        renderBg(gg, partialTicks, mouseX, mouseY);
    }

    public void renderTooltipPublic(GuiGraphics gg, int mouseX, int mouseY) {
        super.renderTooltip(gg, mouseX, mouseY);
    }

    public boolean mouseClickedPublic(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleasedPublic(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseDraggedPublic(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean keyPressedPublic(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTypedPublic(char codePoint, int modifiers) {
        return super.charTyped(codePoint, modifiers);
    }
}
