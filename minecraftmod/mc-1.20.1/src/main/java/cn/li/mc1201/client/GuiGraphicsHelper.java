package cn.li.mc1201.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {
    }

    /**
     * Wrapper for GuiGraphics.blit() 9-parameter overload.
     */
    public static void blit9(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x, int y,
            int u, int v,
            int width, int height,
            int textureWidth, int textureHeight) {
        graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /**
     * Render a textured quad with custom normalized UV coordinates.
     * Uses only public Minecraft rendering APIs (Tesselator / BufferBuilder).
     * No reflection, no Mixin, no Access Transformer required.
     *
     * <p>The vertices are transformed by the GuiGraphics PoseStack matrix, so the
     * quad honors the current translate/scale (e.g. a container screen's
     * leftPos/topPos offset). Emitting raw {@code bb.vertex(x,y,z)} without the
     * matrix would ignore the pose and render at absolute coordinates.</p>
     */
    public static void blitTexturedQuad(
            GuiGraphics graphics,
            ResourceLocation texture,
            float x1, float y1,
            float x2, float y2,
            float z,
            float u0, float u1,
            float v0, float v1) {
        Matrix4f pose = graphics.pose().last().pose();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(pose, x1, y2, z).uv(u0, v1).endVertex();
        bb.vertex(pose, x2, y2, z).uv(u1, v1).endVertex();
        bb.vertex(pose, x2, y1, z).uv(u1, v0).endVertex();
        bb.vertex(pose, x1, y1, z).uv(u0, v0).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }
}