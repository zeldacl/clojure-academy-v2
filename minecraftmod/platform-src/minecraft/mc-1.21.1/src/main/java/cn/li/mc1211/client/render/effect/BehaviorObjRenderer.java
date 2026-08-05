package cn.li.mc1211.client.render.effect;

import cn.li.mcbase.clj.ClojureInterop;
import cn.li.mc1211.entity.ScriptedEntitySpecAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Generic OBJ-model renderer for a behavior-driven block-body entity.
 *
 * <p>The render namespace is resolved at render time from the neutral
 * {@code cn.li.mcmod.spi.entity-render-registry} (registered by content
 * modules during client init). This avoids hardcoding an AC namespace
 * in the shared Minecraft layer.</p>
 */
public final class BehaviorObjRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final String REGISTRY_NS = "cn.li.mcmod.spi.entity-render-registry";
    /** Cached after first successful resolution. */
    private static volatile String renderNamespace = null;

    static {
        try {
            ClojureInterop.requireNamespace(REGISTRY_NS);
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public BehaviorObjRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private String resolveRenderNamespace(T entity) {
        if (renderNamespace != null) {
            return renderNamespace;
        }
        try {
            var spec = ScriptedEntitySpecAccess.getScriptedBlockBodySpec(entity.getType());
            String hookId = spec == null ? "" : spec.getHookId();
            Object result = ClojureInterop.invoke(REGISTRY_NS, "get-entity-render-ns", hookId);
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
        String ns = resolveRenderNamespace(entity);
        if (ns == null) {
            return; // render namespace not yet registered — skip rendering
        }
        boolean hit = ScriptedRenderAccess.isBehaviorHit(entity);
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
