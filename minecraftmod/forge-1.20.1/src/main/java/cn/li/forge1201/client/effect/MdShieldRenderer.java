package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class MdShieldRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("my_mod", "textures/effects/mdshield.png");
    private static final float BASE_SIZE = 1.8F;

    public MdShieldRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        float age = entity.getAgeTicks() + partialTick;

        // Scale lerp: 0.2 → 1.0 over 15 ticks (matching original EntityMdShield)
        float scaleFactor = lerp(0.2F, 1.0F, Math.min(age / 15.0F, 1.0F));
        float size = BASE_SIZE * scaleFactor * 0.5F;

        // Alpha lerp: 0 → 1.0 over 6 ticks
        float alpha = Math.min(age / 6.0F, 1.0F);
        if (alpha <= 0.0F) {
            return;
        }

        // Rotation: ~9°/tick
        float rotation = age * 9.0F;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));

        Matrix4f mat = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(TEXTURE));
        int a = (int) (alpha * 255);

        quad(vc, mat, normal, packedLight, -size, -size, size, size, a);

        poseStack.popPose();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, Matrix3f normal,
                              int packedLight,
                              float x0, float y0, float x1, float y1, int a) {
        vc.vertex(mat, x0, y0, 0.0F).color(255, 255, 255, a).uv(0.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x0, y1, 0.0F).color(255, 255, 255, a).uv(0.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x1, y1, 0.0F).color(255, 255, 255, a).uv(1.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x1, y0, 0.0F).color(255, 255, 255, a).uv(1.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return TEXTURE;
    }
}
