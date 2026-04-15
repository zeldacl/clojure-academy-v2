package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.effect.IntensifyEffectEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class IntensifyEffectRenderer extends EntityRenderer<IntensifyEffectEntity> {
    public IntensifyEffectRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(IntensifyEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        if (entity.getActiveArcs().isEmpty()) {
            return;
        }

        poseStack.pushPose();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f pose = poseStack.last().pose();

        float entityFade = 1.0F - Math.min(1.0F, (entity.getAgeTicks() + partialTick) / 15.0F);
        float time = entity.getAgeTicks() + partialTick;

        for (IntensifyEffectEntity.ArcData arc : entity.getActiveArcs()) {
            float arcFade = Math.max(0.0F, Math.min(1.0F, arc.lifeTicks / 3.0F));
            float flicker = 0.72F + (0.28F * Math.abs((float) Math.sin((time * 2.4F) + arc.flickerSeed)));
            float alpha = Math.max(0.08F, 0.9F * arcFade * entityFade * flicker);
            int a = (int) (alpha * 255.0F);

            float phase = (time * 0.65F) + arc.phase;

            for (float[][] points : arc.strands) {
                float strandScale = (points == arc.strands[0]) ? 1.0F : 0.75F;
                for (int i = 0; i < points.length - 1; i++) {
                    float[] p1 = points[i];
                    float[] p2 = points[i + 1];

                    float t1 = (float) i / (float) (points.length - 1);
                    float t2 = (float) (i + 1) / (float) (points.length - 1);
                    float wob1 = (float) Math.sin((t1 * 9.0F) + phase) * 0.018F * strandScale;
                    float wob2 = (float) Math.sin((t2 * 9.0F) + phase) * 0.018F * strandScale;

                    float x1 = p1[0] + wob1;
                    float y1 = p1[1] + (wob1 * 0.35F);
                    float z1 = p1[2] - wob1;

                    float x2 = p2[0] + wob2;
                    float y2 = p2[1] + (wob2 * 0.35F);
                    float z2 = p2[2] - wob2;

                    int c1r = (int) (170 + (35 * flicker));
                    int c1g = (int) (220 + (25 * flicker));
                    int c1b = 255;

                    int c2r = (int) (110 + (35 * flicker));
                    int c2g = (int) (180 + (35 * flicker));
                    int c2b = 255;

                    consumer.vertex(pose, x1, y1, z1)
                            .color(c1r, c1g, c1b, a)
                            .normal(0.0F, 1.0F, 0.0F)
                            .endVertex();
                    consumer.vertex(pose, x2, y2, z2)
                            .color(c2r, c2g, c2b, a)
                            .normal(0.0F, 1.0F, 0.0F)
                            .endVertex();
                }
            }
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(IntensifyEffectEntity entity) {
        return null;
    }
}
