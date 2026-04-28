package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class RippleMarkRenderer extends EntityRenderer<ScriptedEffectEntity> {
    public RippleMarkRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        float age = entity.getAgeTicks() + partialTick;
        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        for (int layer = 0; layer < 3; layer++) {
            float cyclePos = (age + layer * 1.2F) % 3.6F;
            float ringAlpha = Math.max(0.0F, 1.0F - (cyclePos / 3.6F));
            float ringRadius = 0.4F + cyclePos * 0.5F;
            int alpha = (int) (200 * ringAlpha);
            if (alpha <= 0) continue;

            int segments = 16;
            for (int i = 0; i < segments; i++) {
                double a1 = (Math.PI * 2.0 * i) / segments;
                double a2 = (Math.PI * 2.0 * (i + 1)) / segments;
                float x1 = (float) (Math.cos(a1) * ringRadius);
                float z1 = (float) (Math.sin(a1) * ringRadius);
                float x2 = (float) (Math.cos(a2) * ringRadius);
                float z2 = (float) (Math.sin(a2) * ringRadius);
                vc.vertex(mat, x1, 0.02F, z1).color(150, 100, 255, alpha).normal(0.0F, 1.0F, 0.0F).endVertex();
                vc.vertex(mat, x2, 0.02F, z2).color(120, 80, 200, alpha).normal(0.0F, 1.0F, 0.0F).endVertex();
            }
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return null;
    }
}
