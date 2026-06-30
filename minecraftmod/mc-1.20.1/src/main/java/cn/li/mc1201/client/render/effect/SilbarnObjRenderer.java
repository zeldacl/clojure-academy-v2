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
 */
public final class SilbarnObjRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final String SILBARN_RENDER_NS = "cn.li.ac.content.entities.silbarn-render";

    static {
        try {
            ClojureInterop.requireNamespace(SILBARN_RENDER_NS);
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public SilbarnObjRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        boolean hit = ScriptedRenderAccess.isSilbarnHit(entity);
        ClojureInterop.invoke(
            SILBARN_RENDER_NS,
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
