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
    private static final float ARC_DEFAULT_SHOW_WIGGLE = 0.2F;
    private static final float ARC_DEFAULT_HIDE_WIGGLE = 0.2F;
    private static final float ARC_DEFAULT_WIGGLE_AMP = 0.5F;
    private static final float ARC_DEFAULT_WIGGLE_FREQ = 7.0F;
    private static final int ARC_DEFAULT_SEGMENTS = 20;
    private static final int ARC_MAX_SEGMENTS = 80;

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

    private static String drawPlanRendererKey(String rendererId) {
        Object keyObj = ClojureInterop.invoke(
                SCRIPT_RENDER_RUNTIME_NS,
                "draw-plan-renderer-key",
                rendererId
        );
        return keyObj == null ? "" : keyObj.toString();
    }

    private static float drawPlanParamFloat(String rendererId, String paramKey, float defaultValue) {
        Object value = ClojureInterop.invoke(
                SCRIPT_RENDER_RUNTIME_NS,
                "draw-plan-param-double",
                rendererId,
                paramKey,
                (double) defaultValue
        );
        return value instanceof Number number ? number.floatValue() : defaultValue;
    }

    private static int drawPlanParamInt(String rendererId, String paramKey, int defaultValue) {
        Object value = ClojureInterop.invoke(
                SCRIPT_RENDER_RUNTIME_NS,
                "draw-plan-param-int",
                rendererId,
                paramKey,
                defaultValue
        );
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedEffectSpec spec = ScriptedRenderAccess.getEffectSpec(entity);
        String rendererId = spec == null || spec.getRendererId() == null ? "" : spec.getRendererId();
        String rendererKey = drawPlanRendererKey(rendererId);

        switch (rendererKey) {
            case "ring-lines" -> renderRingLines(entity, partialTick, poseStack, bufferSource);
            case "polyline-arc" -> renderPolylineArc(entity, spec, rendererId, partialTick, poseStack, bufferSource);
            case "billboard-cross" -> renderBillboardCross(entity, spec, partialTick, poseStack, bufferSource);
            case "tiered-zigzag" -> TieredZigzagArcRenderer.render(entity, spec, rendererId, partialTick, poseStack, bufferSource);
            default -> throw new IllegalArgumentException("Unsupported renderer key for effect rendererId="
                    + rendererId + ": " + rendererKey);
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
                                   ScriptedEffectSpec spec,
                                   String rendererId,
                                   float partialTick,
                                   PoseStack poseStack,
                                   MultiBufferSource bufferSource) {
        float life = Math.max(1.0F, spec == null ? 20.0F : spec.getLifeTicks());
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
        float progress = Mth.clamp(age / life, 0.0F, 1.0F);

        int segments = Mth.clamp(drawPlanParamInt(rendererId, "segments", ARC_DEFAULT_SEGMENTS), 2, ARC_MAX_SEGMENTS);
        float length = Math.max(0.1F, drawPlanParamFloat(rendererId, "length", ARC_DEFAULT_LENGTH));
        float showWiggle = Math.max(0.0F, drawPlanParamFloat(rendererId, "show-wiggle", ARC_DEFAULT_SHOW_WIGGLE));
        float hideWiggle = Math.max(0.0F, drawPlanParamFloat(rendererId, "hide-wiggle", ARC_DEFAULT_HIDE_WIGGLE));
        float wiggleAmp = Math.max(0.0F, drawPlanParamFloat(rendererId, "wiggle-amp", ARC_DEFAULT_WIGGLE_AMP));
        float wiggleFreq = Math.max(0.0F, drawPlanParamFloat(rendererId, "wiggle-freq", ARC_DEFAULT_WIGGLE_FREQ));

        float showFactor = Mth.clamp(progress / showWiggle, 0.0F, 1.0F);
        float hideFactor = Mth.clamp((1.0F - progress) / hideWiggle, 0.0F, 1.0F);
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
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / (float) segments;
            float z = length * t;

            float wave = (entity.getId() * 0.37F) + age * 0.35F + t * wiggleFreq;
            float phaseAmp = showWiggle * (1.0F - progress) + hideWiggle * progress;
            float wiggleX = (float) Math.sin(wave) * wiggleAmp * phaseAmp;
            float wiggleY = (float) Math.cos(wave * 1.17F) * wiggleAmp * phaseAmp * 0.6F;

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
