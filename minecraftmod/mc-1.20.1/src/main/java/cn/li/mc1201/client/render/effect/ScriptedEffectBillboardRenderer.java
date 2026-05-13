package cn.li.mc1201.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import cn.li.mc1201.clj.ClojureInterop;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

public final class ScriptedEffectBillboardRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final String SCRIPT_RENDER_RUNTIME_NS = "cn.li.mc1201.client.render.script-render-runtime";
    private static final float ARC_DEFAULT_LENGTH = 20.0F;
    private static final float ARC_SHOW_WIGGLE = 0.2F;
    private static final float ARC_HIDE_WIGGLE = 0.2F;
    private static final float ARC_TEX_WIGGLE = 0.5F;
    private static final int ARC_SEGMENTS = 20;

    static {
        try {
            ClojureInterop.requireNamespace(SCRIPT_RENDER_RUNTIME_NS);
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public ScriptedEffectBillboardRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static String drawPlanKind(String rendererId) {
        Object kindObj = ClojureInterop.invoke(
                SCRIPT_RENDER_RUNTIME_NS,
                "draw-plan-kind",
                rendererId
        );
        return kindObj == null ? "" : kindObj.toString();
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedEffectSpec spec = ScriptedRenderAccess.getEffectSpec(entity);
        String rendererId = spec == null || spec.getRendererId() == null ? "" : spec.getRendererId();
        String kind = drawPlanKind(rendererId);

        switch (kind) {
            case "ring-lines" -> renderRingLines(entity, partialTick, poseStack, bufferSource);
            case "polyline-arc" -> renderPolylineArc(entity, partialTick, poseStack, bufferSource);
            case "billboard-cross" -> renderBillboardCross(entity, spec, partialTick, poseStack, bufferSource);
            default -> throw new IllegalArgumentException("Unsupported draw-plan kind for effect rendererId="
                    + rendererId + ": " + kind);
        }
    }

    private void renderBillboardCross(T entity,
                                      ScriptedEffectSpec spec,
                                      float partialTick,
                                      PoseStack poseStack,
                                      MultiBufferSource bufferSource) {
        float life = Math.max(1.0F, spec == null ? 15.0F : spec.getLifeTicks());
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
        float alpha = Math.max(0.0F, 1.0F - (age / life));
        if (alpha <= 0.0F) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        int a = (int) (255 * alpha);
        float size = 0.6F;

        vc.vertex(mat, -size, 0.0F, 0.0F).color(180, 220, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(mat, size, 0.0F, 0.0F).color(180, 220, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();

        vc.vertex(mat, 0.0F, -size, 0.0F).color(180, 220, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(mat, 0.0F, size, 0.0F).color(180, 220, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();

        poseStack.popPose();
    }

    private void renderRingLines(T entity,
                                 float partialTick,
                                 PoseStack poseStack,
                                 MultiBufferSource bufferSource) {
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        for (int layer = 0; layer < 3; layer++) {
            float cyclePos = (age + layer * 1.2F) % 3.6F;
            float ringAlpha = Math.max(0.0F, 1.0F - (cyclePos / 3.6F));
            float ringRadius = 0.4F + cyclePos * 0.5F;
            int alpha = (int) (200 * ringAlpha);
            if (alpha <= 0) {
                continue;
            }

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

    private void renderPolylineArc(T entity,
                                   float partialTick,
                                   PoseStack poseStack,
                                   MultiBufferSource bufferSource) {
        float life = 20.0F;
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
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
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        float prevX = 0.0F;
        float prevY = 0.0F;
        float prevZ = 0.0F;

        int a = (int) (255.0F * alpha);
        for (int i = 1; i <= ARC_SEGMENTS; i++) {
            float t = (float) i / (float) ARC_SEGMENTS;
            float z = ARC_DEFAULT_LENGTH * t;

            float wave = (entity.getId() * 0.37F) + age * 0.35F + t * 7.0F;
            float phaseAmp = ARC_SHOW_WIGGLE * (1.0F - progress) + ARC_HIDE_WIGGLE * progress;
            float wiggleX = (float) Math.sin(wave) * ARC_TEX_WIGGLE * phaseAmp;
            float wiggleY = (float) Math.cos(wave * 1.17F) * ARC_TEX_WIGGLE * phaseAmp * 0.6F;

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
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
