package cn.li.mc262.client;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3x2f;

/** Thin GuiGraphicsExtractor helpers for 26.2. */
@OnlyIn(Dist.CLIENT)
public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {}

    public static void blit9(Object graphics, Identifier texture,
                             int x, int y, int w, int h,
                             int u, int v, int regionW, int regionH,
                             int texW, int texH,
                             int borderL, int borderT, int borderR, int borderB) {
        if (!(graphics instanceof GuiGraphicsExtractor gge)) {
            return;
        }
        int left = Math.min(Math.max(0, borderL), Math.min(w, regionW));
        int top = Math.min(Math.max(0, borderT), Math.min(h, regionH));
        int right = Math.min(Math.max(0, borderR), Math.min(w - left, regionW - left));
        int bottom = Math.min(Math.max(0, borderB), Math.min(h - top, regionH - top));
        int centerW = Math.max(0, w - left - right);
        int centerH = Math.max(0, h - top - bottom);
        int sourceCenterW = Math.max(0, regionW - left - right);
        int sourceCenterH = Math.max(0, regionH - top - bottom);

        blitSlice(gge, texture, x, y, left, top, u, v, left, top, texW, texH);
        blitSlice(gge, texture, x + left, y, centerW, top,
                u + left, v, sourceCenterW, top, texW, texH);
        blitSlice(gge, texture, x + left + centerW, y, right, top,
                u + regionW - right, v, right, top, texW, texH);
        blitSlice(gge, texture, x, y + top, left, centerH,
                u, v + top, left, sourceCenterH, texW, texH);
        blitSlice(gge, texture, x + left, y + top, centerW, centerH,
                u + left, v + top, sourceCenterW, sourceCenterH, texW, texH);
        blitSlice(gge, texture, x + left + centerW, y + top, right, centerH,
                u + regionW - right, v + top, right, sourceCenterH, texW, texH);
        blitSlice(gge, texture, x, y + top + centerH, left, bottom,
                u, v + regionH - bottom, left, bottom, texW, texH);
        blitSlice(gge, texture, x + left, y + top + centerH, centerW, bottom,
                u + left, v + regionH - bottom, sourceCenterW, bottom, texW, texH);
        blitSlice(gge, texture, x + left + centerW, y + top + centerH, right, bottom,
                u + regionW - right, v + regionH - bottom, right, bottom, texW, texH);
    }

    private static void blitSlice(GuiGraphicsExtractor graphics, Identifier texture,
                                  int x, int y, int width, int height,
                                  int u, int v, int sourceWidth, int sourceHeight,
                                  int textureWidth, int textureHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
                x, y, (float) u, (float) v,
                width, height, sourceWidth, sourceHeight, textureWidth, textureHeight);
    }

    public static void blitTexturedQuad(Object graphics, Identifier texture,
                                        float x1, float y1, float x2, float y2, float z,
                                        float u0, float u1, float v0, float v1) {
        if (!(graphics instanceof GuiGraphicsExtractor gge)) {
            return;
        }
        int x = Math.round(Math.min(x1, x2));
        int y = Math.round(Math.min(y1, y2));
        int w = Math.max(1, Math.round(Math.abs(x2 - x1)));
        int h = Math.max(1, Math.round(Math.abs(y2 - y1)));
        gge.blit(texture, x, y, w, h, u0, v0, u1, v1);
    }

    /** Compatibility overload matching older float-free call sites. */
    public static void blitTexturedQuad(Object graphics, Identifier texture,
                                        int x0, int y0, int x1, int y1,
                                        float u0, float v0, float u1, float v1) {
        blitTexturedQuad(graphics, texture,
                (float) x0, (float) y0, (float) x1, (float) y1, 0f,
                u0, u1, v0, v1);
    }

    /** Full-texture blit at pixel size (w×h). */
    public static void blit(Object graphics, Identifier texture, int x, int y, int w, int h) {
        if (!(graphics instanceof GuiGraphicsExtractor gge)) {
            return;
        }
        gge.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, w, h);
    }

    /**
     * Full-texture blit using vanilla's {@code SRC_ALPHA, ONE} pipeline.
     *
     * <p>This is extraction-safe: blend state belongs to the submitted render
     * state instead of being mutated globally while the GUI tape is recorded.
     */
    public static void blitAdditive(Object graphics, Identifier texture,
                                    int x, int y, int w, int h, int argb) {
        if (!(graphics instanceof GuiGraphicsExtractor gge) || texture == null || w <= 0 || h <= 0) {
            return;
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
        gge.submitGuiElementRenderState(new BlitRenderState(
                RenderPipelines.MOJANG_LOGO,
                TextureSetup.singleTexture(tex.getTextureView(), tex.getSampler()),
                new Matrix3x2f(gge.pose()),
                x, y, x + w, y + h,
                0f, 1f, 0f, 1f,
                argb,
                gge.peekScissorStack()));
    }

    /**
     * Textured quad with fractional UVs and a per-quad ARGB tint.
     *
     * <p>Every public {@code GuiGraphicsExtractor.blit} overload that accepts
     * fractional UVs pins the tint to -1, and the ones that accept a tint take
     * texel UVs against a texture size the caller does not know. Submitting the
     * BlitRenderState directly is the only way to get both, which the progress
     * bar needs for its multi-stop colour ramp.
     *
     * <p>The caller's pose is captured as-is, so a sheared pose yields a
     * parallelogram; the current scissor still clips it.
     */
    public static void blitTintedQuad(Object graphics, Identifier texture,
                                      int x0, int y0, int x1, int y1,
                                      float u0, float u1, float v0, float v1,
                                      int argb) {
        if (!(graphics instanceof GuiGraphicsExtractor gge) || texture == null) {
            return;
        }
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
        GpuTextureView view = tex.getTextureView();
        GpuSampler sampler = tex.getSampler();
        gge.submitGuiElementRenderState(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(view, sampler),
                new Matrix3x2f(gge.pose()),
                x0, y0, x1, y1,
                u0, u1, v0, v1,
                argb,
                gge.peekScissorStack()));
    }

    /**
     * Submit a textured quad through a custom extraction-safe GUI pipeline.
     *
     * <p>The optional second texture is bound as Sampler1. The packed ARGB
     * colour is available to custom shaders as the vertex {@code Color}, which
     * is how 26.2 GUI nodes carry per-draw scalar parameters without mutable
     * shader uniforms.</p>
     */
    public static void blitPipeline(Object graphics, RenderPipeline pipeline,
                                    Identifier texture0, Identifier texture1,
                                    int x0, int y0, int x1, int y1,
                                    float u0, float u1, float v0, float v1,
                                    int argb) {
        if (!(graphics instanceof GuiGraphicsExtractor gge)
                || pipeline == null || texture0 == null || x0 >= x1 || y0 >= y1) {
            return;
        }
        AbstractTexture first = Minecraft.getInstance().getTextureManager().getTexture(texture0);
        TextureSetup textures;
        if (texture1 == null) {
            textures = TextureSetup.singleTexture(first.getTextureView(), first.getSampler());
        } else {
            AbstractTexture second = Minecraft.getInstance().getTextureManager().getTexture(texture1);
            textures = TextureSetup.doubleTexture(
                    first.getTextureView(), first.getSampler(),
                    second.getTextureView(), second.getSampler());
        }
        gge.submitGuiElementRenderState(new BlitRenderState(
                pipeline,
                textures,
                new Matrix3x2f(gge.pose()),
                x0, y0, x1, y1,
                u0, u1, v0, v1,
                argb,
                gge.peekScissorStack()));
    }

    /** Submit a solid rectangle with pipeline-owned depth/blend state. */
    public static void fillPipeline(Object graphics, RenderPipeline pipeline,
                                    int x0, int y0, int x1, int y1, int argb) {
        if (graphics instanceof GuiGraphicsExtractor gge && pipeline != null) {
            gge.fill(pipeline, x0, y0, x1, y1, argb);
        }
    }

    /** Sprite-sheet region blit. */
    public static void blitRegion(Object graphics, Identifier texture,
                                  int x, int y, int w, int h,
                                  float u, float v, int regionW, int regionH,
                                  int texW, int texH) {
        if (!(graphics instanceof GuiGraphicsExtractor gge)) {
            return;
        }
        gge.blit(RenderPipelines.GUI_TEXTURED, texture,
                x, y, u, v, w, h, regionW, regionH, texW, texH);
    }
}
