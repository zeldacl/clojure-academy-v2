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

public final class WireMarkerRenderer extends EntityRenderer<ScriptedMarkerEntity> {
    public WireMarkerRenderer(EntityRendererProvider.Context context) {
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
        int g2 = available ? 80 : 20;
        int b2 = available ? 80 : 20;

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }

        try {
            poseStack.pushPose();
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
            float s = 0.5F;
            int a = 200;

            edge(vc, mat, -s, -s, -s, s, -s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, s, -s, -s, s, -s, s, a, g1, b1, g2, b2);
            edge(vc, mat, s, -s, s, -s, -s, s, a, g1, b1, g2, b2);
            edge(vc, mat, -s, -s, s, -s, -s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, -s, s, -s, s, s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, s, s, -s, s, s, s, a, g1, b1, g2, b2);
            edge(vc, mat, s, s, s, -s, s, s, a, g1, b1, g2, b2);
            edge(vc, mat, -s, s, s, -s, s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, -s, -s, -s, -s, s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, s, -s, -s, s, s, -s, a, g1, b1, g2, b2);
            edge(vc, mat, s, -s, s, s, s, s, a, g1, b1, g2, b2);
            edge(vc, mat, -s, -s, s, -s, s, s, a, g1, b1, g2, b2);

            poseStack.popPose();
        } finally {
            if (ignoreDepth) {
                RenderSystem.enableDepthTest();
            }
        }
    }

    private static void edge(VertexConsumer vc, Matrix4f mat,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             int a,
                             int g1,
                             int b1,
                             int g2,
                             int b2) {
        vc.vertex(mat, x1, y1, z1).color(255, g1, b1, a).normal(0.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(mat, x2, y2, z2).color(255, g2, b2, a).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedMarkerEntity entity) {
        return null;
    }
}
