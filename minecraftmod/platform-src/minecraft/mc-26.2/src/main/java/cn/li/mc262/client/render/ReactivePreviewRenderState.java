package cn.li.mc262.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

/**
 * Extracted GUI state for a reactive 3D preview.
 *
 * <p>The state contains only render-ready item geometry and scalar camera
 * parameters. The GUI extraction pass submits it; the PIP renderer owns the
 * offscreen color/depth targets and the perspective camera.</p>
 */
public record ReactivePreviewRenderState(
        TrackingItemStackRenderState itemRenderState,
        int x0,
        int y0,
        int x1,
        int y1,
        float modelScale,
        float yawDegrees,
        float yOffset,
        ScreenRectangle scissorArea,
        ScreenRectangle bounds
) implements PictureInPictureRenderState {

    private static volatile boolean rendererRegistered;

    public static void markRendererRegistered() {
        rendererRegistered = true;
    }

    /**
     * Extract and submit a PIP state. Returns false when the loader did not
     * register the renderer, allowing the caller to use its compatibility path.
     */
    public static boolean submit(
            GuiGraphicsExtractor graphics,
            ItemStack stack,
            double x,
            double y,
            double width,
            double height,
            double modelScale,
            double yawDegrees,
            double yOffset
    ) {
        if (!rendererRegistered || graphics == null || stack == null || stack.isEmpty()
                || width <= 0.0 || height <= 0.0) {
            return false;
        }

        // GuiGraphicsExtractor no longer exposes a public PIP submission or
        // scissor stack in 26.2. Leave the extraction seam disabled until the
        // renderer-specific state is wired through GuiRenderState.add... APIs.
        return false;
    }

    @Override
    public float scale() {
        // Camera/model scaling is applied in renderToTexture after resetting
        // the base PIP renderer's orthographic pixel transform.
        return 1.0F;
    }
}
