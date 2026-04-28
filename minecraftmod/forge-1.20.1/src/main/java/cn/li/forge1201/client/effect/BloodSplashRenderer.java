package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ModEntities;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.forge1201.entity.ScriptedEffectSpec;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class BloodSplashRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final ResourceLocation[] SPLASH_FRAMES = new ResourceLocation[10];

    static {
        for (int i = 0; i < SPLASH_FRAMES.length; i++) {
            SPLASH_FRAMES[i] = new ResourceLocation("my_mod", "textures/effects/blood_splash/" + i + ".png");
        }
    }

    public BloodSplashRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedEffectSpec spec = ModEntities.getScriptedEffectSpec(entity.getType());
        float life = Math.max(1.0F, spec == null ? 10.0F : spec.getLifeTicks());
        float age = entity.getAgeTicks() + partialTick;
        if (age >= life) {
            return;
        }

        int frame = Math.min(SPLASH_FRAMES.length - 1, Math.max(0, (int) age));
        float size = Math.max(0.8F, entity.getBbWidth());

        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(size, size, size);

        Matrix4f mat = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(SPLASH_FRAMES[frame]));
        int alpha = 200;

        quad(vc, mat, normal, packedLight, -0.5F, -0.5F, 0.5F, 0.5F, alpha, 213, 29, 29);

        poseStack.popPose();
    }

    private static void quad(VertexConsumer vc,
                             Matrix4f mat,
                             Matrix3f normal,
                             int packedLight,
                             float x0,
                             float y0,
                             float x1,
                             float y1,
                             int a,
                             int r,
                             int g,
                             int b) {
        vc.vertex(mat, x0, y0, 0.0F).color(r, g, b, a).uv(0.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x0, y1, 0.0F).color(r, g, b, a).uv(0.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x1, y1, 0.0F).color(r, g, b, a).uv(1.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(mat, x1, y0, 0.0F).color(r, g, b, a).uv(1.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return SPLASH_FRAMES[0];
    }
}
