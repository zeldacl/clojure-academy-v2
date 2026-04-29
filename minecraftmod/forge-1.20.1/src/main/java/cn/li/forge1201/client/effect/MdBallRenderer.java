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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class MdBallRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final ResourceLocation GLOW_TEXTURE =
            new ResourceLocation("my_mod", "textures/effects/mdball/glow.png");
    private static final ResourceLocation[] CORE_FRAMES = new ResourceLocation[5];

    static {
        for (int i = 0; i < CORE_FRAMES.length; i++) {
            CORE_FRAMES[i] = new ResourceLocation("my_mod", "textures/effects/mdball/" + i + ".png");
        }
    }

    public MdBallRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedEffectSpec spec = ModEntities.getScriptedEffectSpec(entity.getType());
        float life = Math.max(1.0F, spec == null ? 50.0F : spec.getLifeTicks());
        float age = entity.getAgeTicks() + partialTick;
        if (age >= life) {
            return;
        }

        float alpha = computeAlpha(age, life);
        if (alpha <= 0.0F) {
            return;
        }

        float phase = age * 0.35F + entity.getId() * 0.61F;
        float alphaWiggle = 0.65F + 0.35F * (0.5F + 0.5F * (float) Math.sin(phase * 2.1F));
        float size = computeSize(age, life);
        int frame = Math.floorMod((int) (age * 2.0F + entity.getId()), CORE_FRAMES.length);
        int fullbright = 0x00F000F0;

        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

        Matrix4f mat = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        int glowAlpha = clampAlpha(alpha * (0.3F + alphaWiggle * 0.7F));
        int coreAlpha = clampAlpha(alpha * (0.8F + alphaWiggle * 0.2F));

        VertexConsumer glow = bufferSource.getBuffer(RenderType.entityTranslucent(GLOW_TEXTURE));
        quad(glow, mat, normal, fullbright, -0.35F * size, -0.35F * size, 0.35F * size, 0.35F * size,
                216, 248, 216, glowAlpha);

        VertexConsumer core = bufferSource.getBuffer(RenderType.entityTranslucent(CORE_FRAMES[frame]));
        quad(core, mat, normal, fullbright, -0.25F * size, -0.25F * size, 0.25F * size, 0.25F * size,
                230, 255, 230, coreAlpha);

        poseStack.popPose();
    }

    private static int clampAlpha(float value) {
        if (value <= 0.0F) {
            return 0;
        }
        if (value >= 1.0F) {
            return 255;
        }
        return (int) (value * 255.0F);
    }

    private static float computeAlpha(float age, float life) {
        float t = age / life;
        if (t < 0.12F) {
            return t / 0.12F * 0.6F;
        }
        if (t < 0.8F) {
            return 0.6F;
        }
        if (t < 0.97F) {
            return 0.6F + ((t - 0.8F) / 0.17F) * 0.4F;
        }
        return Math.max(0.0F, 1.0F - ((t - 0.97F) / 0.03F));
    }

    private static float computeSize(float age, float life) {
        float t = age / life;
        if (t < 0.88F) {
            return 1.0F;
        }
        if (t < 0.97F) {
            return 1.0F + ((t - 0.88F) / 0.09F) * 0.5F;
        }
        return Math.max(0.0F, 1.5F - ((t - 0.97F) / 0.03F) * 1.5F);
    }

    private static void quad(VertexConsumer vc,
                             Matrix4f mat,
                             Matrix3f normal,
                             int packedLight,
                             float x0,
                             float y0,
                             float x1,
                             float y1,
                             int r,
                             int g,
                             int b,
                             int a) {
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
        return CORE_FRAMES[0];
    }
}
