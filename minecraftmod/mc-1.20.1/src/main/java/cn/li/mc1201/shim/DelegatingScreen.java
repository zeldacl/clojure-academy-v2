package cn.li.mc1201.shim;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import clojure.lang.IFn;

/** Universal Screen skeleton — replaces all Screen proxy sites.
 *  Takes IFn callbacks via constructor and chained with* setters.
 *  Each callback receives the DelegatingScreen instance as first argument. */
public class DelegatingScreen extends Screen {
    private IFn renderFn;
    private IFn keyPressedFn;
    private IFn charTypedFn;
    private IFn mouseClickedFn;
    private IFn removedFn;

    // Optional callbacks — set via with* methods
    private IFn mouseReleasedFn;
    private IFn mouseDraggedFn;
    private IFn mouseMovedFn;
    private IFn mouseScrolledFn;
    private IFn isPauseScreenFn;

    public DelegatingScreen(Component title,
                            IFn renderFn, IFn keyPressedFn, IFn charTypedFn,
                            IFn mouseClickedFn, IFn removedFn) {
        super(title);
        this.renderFn = renderFn;
        this.keyPressedFn = keyPressedFn;
        this.charTypedFn = charTypedFn;
        this.mouseClickedFn = mouseClickedFn;
        this.removedFn = removedFn;
    }

    /** Public accessor so Clojure code can call renderBackground on this instance. */
    @Override public void renderBackground(GuiGraphics gg) {
        super.renderBackground(gg);
    }

    // -- with* setters for optional callbacks (builder pattern) --

    public DelegatingScreen withMouseReleased(IFn fn) { this.mouseReleasedFn = fn; return this; }
    public DelegatingScreen withMouseDragged(IFn fn) { this.mouseDraggedFn = fn; return this; }
    public DelegatingScreen withMouseMoved(IFn fn) { this.mouseMovedFn = fn; return this; }
    public DelegatingScreen withMouseScrolled(IFn fn) { this.mouseScrolledFn = fn; return this; }
    public DelegatingScreen withIsPauseScreen(IFn fn) { this.isPauseScreenFn = fn; return this; }

    // -- Delegated methods (this passed as first arg to each IFn) --

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (renderFn != null) {
            renderFn.invoke(this, graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override public boolean keyPressed(int key, int scancode, int modifiers) {
        if (keyPressedFn != null) {
            Object r = keyPressedFn.invoke(this, key, scancode, modifiers);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override public boolean charTyped(char ch, int modifiers) {
        if (charTypedFn != null) {
            Object r = charTypedFn.invoke(this, ch, modifiers);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.charTyped(ch, modifiers);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseClickedFn != null) {
            Object r = mouseClickedFn.invoke(this, mouseX, mouseY, button);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mouseReleasedFn != null) {
            Object r = mouseReleasedFn.invoke(this, mouseX, mouseY, button);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mouseDraggedFn != null) {
            Object r = mouseDraggedFn.invoke(this, mouseX, mouseY, button, dragX, dragY);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public void mouseMoved(double mouseX, double mouseY) {
        if (mouseMovedFn != null) {
            mouseMovedFn.invoke(this, mouseX, mouseY);
        } else {
            super.mouseMoved(mouseX, mouseY);
        }
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseScrolledFn != null) {
            Object r = mouseScrolledFn.invoke(this, mouseX, mouseY, delta);
            return r instanceof Boolean ? (Boolean) r : false;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean isPauseScreen() {
        if (isPauseScreenFn != null) {
            Object r = isPauseScreenFn.invoke(this);
            return r instanceof Boolean ? (Boolean) r : true;
        }
        return super.isPauseScreen();
    }

    @Override public void removed() {
        if (removedFn != null) removedFn.invoke(this);
        super.removed();
    }
}
