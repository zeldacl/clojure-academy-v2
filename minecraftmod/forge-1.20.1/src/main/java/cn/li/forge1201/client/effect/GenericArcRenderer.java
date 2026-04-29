package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Generic forward arc renderer with deterministic wiggle and fade profile.
 */
public final class GenericArcRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final float DEFAULT_LENGTH = 20.0F;
    private static final float SHOW_WIGGLE = 0.2F;
    private static final float HIDE_WIGGLE = 0.2F;
    private static final float TEX_WIGGLE = 0.5F;
    private static final int SEGMENTS = 20;

    public GenericArcRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        float life = 20.0F;
        float age = entity.getAgeTicks() + partialTick;
        float progress = Mth.clamp(age / life, 0.0F, 1.0F);

        float showFactor = Mth.clamp(progress / 0.2F, 0.0F, 1.0F);
        float hideFactor = Mth.clamp((1.0F - progress) / 0.2F, 0.0F, 1.0F);
        float alpha = Math.min(showFactor, hideFactor);
        if (alpha <= 0.01F) {
            return;
        }

        float yaw = -entity.getYRot() * ((float) Math.PI / 180.0F);
        float pitch = entity.getXRot() * ((float) Math.PI / 180.0F);

        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(yaw));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotation(pitch));

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        float baseLength = DEFAULT_LENGTH;
        float prevX = 0.0F;
        float prevY = 0.0F;
        float prevZ = 0.0F;

        int a = (int) (255.0F * alpha);
        for (int i = 1; i <= SEGMENTS; i++) {
            float t = (float) i / (float) SEGMENTS;
            float z = baseLength * t;

            float wave = (entity.getId() * 0.37F) + age * 0.35F + t * 7.0F;
            float phaseAmp = SHOW_WIGGLE * (1.0F - progress) + HIDE_WIGGLE * progress;
            float wiggleX = (float) Math.sin(wave) * TEX_WIGGLE * phaseAmp;
            float wiggleY = (float) Math.cos(wave * 1.17F) * TEX_WIGGLE * phaseAmp * 0.6F;

            float x = wiggleX;
            float y = wiggleY;

            vc.vertex(mat, prevX, prevY, prevZ).color(110, 190, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
            vc.vertex(mat, x, y, z).color(200, 230, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();

            prevX = x;
            prevY = y;
            prevZ = z;
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return null;
    }
}
