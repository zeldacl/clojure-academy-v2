package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ScriptedMarkerEntity;
import cn.li.forge1201.entity.ScriptedMarkerSpec;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class ScriptedMarkerBillboardRenderer extends EntityRenderer<ScriptedMarkerEntity> {
    public ScriptedMarkerBillboardRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedMarkerEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedMarkerSpec spec = entity.getMarkerSpec();
        if (spec == null) {
            return;
        }

        float life = Math.max(1.0F, spec.getLifeTicks());
        float age = entity.getAgeTicks() + partialTick;
        float alpha = Math.max(0.0F, 1.0F - (age / life));
        if (alpha <= 0.0F) {
            return;
        }

        int g = spec.isAvailable() ? 255 : 50;
        int b = spec.isAvailable() ? 255 : 50;
        boolean ignoreDepth = spec.isIgnoreDepth();

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }

        try {
            poseStack.pushPose();
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

            Matrix4f mat = poseStack.last().pose();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

            int a = (int) (255 * alpha);
            float size = 0.35F;

            vc.vertex(mat, -size, 0.0F, 0.0F).color(255, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();
            vc.vertex(mat, size, 0.0F, 0.0F).color(255, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();

            vc.vertex(mat, 0.0F, -size, 0.0F).color(255, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();
            vc.vertex(mat, 0.0F, size, 0.0F).color(255, g, b, a).normal(0.0F, 1.0F, 0.0F).endVertex();

            poseStack.popPose();
        } finally {
            if (ignoreDepth) {
                RenderSystem.enableDepthTest();
            }
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedMarkerEntity entity) {
        return null;
    }
}
