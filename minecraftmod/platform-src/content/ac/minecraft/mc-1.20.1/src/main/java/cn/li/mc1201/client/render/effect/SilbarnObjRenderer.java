package cn.li.mc1201.client.render.effect;

import cn.li.mc1201.clj.ClojureInterop;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Custom OBJ-model renderer for entity_silbarn, matching the upstream
 * EntitySilbarn$RenderSibarn (spinning shard, hidden once hit).
 *
 * <p>The render namespace is resolved at render time from the neutral
 * {@code cn.li.mcmod.spi.entity-render-registry} (registered by content
 * modules during client init). This avoids hardcoding an AC namespace
 * in the shared Minecraft layer.</p>
 */
public final class SilbarnObjRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final String REGISTRY_NS = "cn.li.mcmod.spi.entity-render-registry";
    private static final String HOOK_ID = "silbarn";

    /** Cached after first successful resolution. */
    private static volatile String renderNamespace = null;

    static {
        try {
            ClojureInterop.requireNamespace(REGISTRY_NS);
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public SilbarnObjRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static String resolveRenderNamespace() {
        if (renderNamespace != null) {
            return renderNamespace;
        }
        try {
            Object result = ClojureInterop.invoke(REGISTRY_NS, "get-entity-render-ns", HOOK_ID);
            if (result instanceof String) {
                String ns = (String) result;
                ClojureInterop.requireNamespace(ns);
                renderNamespace = ns;
                return ns;
            }
        } catch (Throwable ignored) {
            // Content module may not have registered yet — skip rendering.
        }
        return null;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        String ns = resolveRenderNamespace();
        if (ns == null) {
            return; // render namespace not yet registered — skip rendering
        }
        boolean hit = ScriptedRenderAccess.isSilbarnHit(entity);
        ClojureInterop.invoke(
            ns,
            "render!",
            entity.getId(),
            hit,
            (double) entity.tickCount,
            (double) entityYaw,
            (double) partialTick,
            poseStack,
            bufferSource,
            packedLight,
            OverlayTexture.NO_OVERLAY
        );
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
