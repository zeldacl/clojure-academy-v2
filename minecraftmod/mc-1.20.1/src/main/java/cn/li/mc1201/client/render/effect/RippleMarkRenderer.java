package cn.li.mc1201.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Deprecated placeholder.
 *
 * Render logic was unified into ScriptedEffectBillboardRenderer.
 */
@Deprecated
public final class RippleMarkRenderer<T extends Entity> extends EntityRenderer<T> {
    public RippleMarkRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        throw new UnsupportedOperationException("RippleMarkRenderer has been retired; use ScriptedEffectBillboardRenderer");
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
