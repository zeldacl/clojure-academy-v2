package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import cn.li.forge1201.entity.ScriptedMarkerEntity;
import cn.li.forge1201.entity.ScriptedMarkerSpec;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class TpMarkingRenderer extends EntityRenderer<ScriptedMarkerEntity> {
    public TpMarkingRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedMarkerEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedMarkerSpec spec = entity.getMarkerSpec();
        boolean ignoreDepth = spec != null && spec.isIgnoreDepth();
        boolean available = spec == null || spec.isAvailable();
        int g1 = available ? 255 : 50;
        int b1 = available ? 255 : 50;
        int g2 = available ? 220 : 90;
        int b2 = available ? 220 : 90;

        float age = entity.getAgeTicks() + partialTick;
        float pulse = 0.65F + 0.1F * (float) Math.sin(age * 0.4F);
        float spin = age * 8.0F;

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }

        try {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(spin));
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
            int alpha = 220;

            ring(vc, mat, pulse, alpha, 255, g1, b1);
            ring(vc, mat, pulse + 0.18F, 160, 255, g2, b2);

            poseStack.popPose();
        } finally {
            if (ignoreDepth) {
                RenderSystem.enableDepthTest();
            }
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f mat, float radius, int alpha, int r, int g, int b) {
        int segments = 20;
        for (int i = 0; i < segments; i++) {
            double a1 = (Math.PI * 2.0D * i) / segments;
            double a2 = (Math.PI * 2.0D * (i + 1)) / segments;
            float x1 = (float) (Math.cos(a1) * radius);
            float z1 = (float) (Math.sin(a1) * radius);
            float x2 = (float) (Math.cos(a2) * radius);
            float z2 = (float) (Math.sin(a2) * radius);
            vc.vertex(mat, x1, 0.02F, z1).color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).endVertex();
            vc.vertex(mat, x2, 0.02F, z2).color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).endVertex();
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedMarkerEntity entity) {
        return null;
    }
}
