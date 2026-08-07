package cn.li.mc262.client.render.effect;

import cn.li.mcbase.entity.spec.ScriptedEffectSpec;
import cn.li.mcver.ResourceLocations;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

public final class ScriptedEffectBillboardRenderer<T extends Entity>
        extends AbstractScriptedGeometryRenderer<T> {
    public ScriptedEffectBillboardRenderer(EntityRendererProvider.Context context) {
        this(context, "effect-billboard");
    }

    public ScriptedEffectBillboardRenderer(EntityRendererProvider.Context context, String rendererId) {
        super(context, rendererId);
    }

    @Override
    public void extractRenderState(T entity, ScriptedEntityRenderState<T> state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        ScriptedEffectSpec spec = ScriptedRenderAccess.getEffectSpec(entity);
        state.lifeTicks = spec == null ? 15 : spec.getLifeTicks();
        state.activeArcs = ScriptedRenderAccess.getActiveArcs(entity);
    }

    @Override
    public void submit(ScriptedEntityRenderState<T> state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        switch (state.rendererKey) {
            case "ring-lines" -> submitRings(state, poseStack, collector);
            case "polyline-arc" -> submitPolyline(state, poseStack, collector);
            case "billboard-cross" -> submitCross(state, poseStack, collector, camera);
            case "animated-billboard" -> submitAnimatedBillboard(state, poseStack, collector, camera);
            case "spinning-double-sided" -> submitSpinningCoin(state, poseStack, collector);
            case "tiered-zigzag" -> TieredZigzagArcRenderer.submit(state, poseStack, collector);
            case "spinning-shield" -> submitShield(state, poseStack, collector, false);
            case "diamond-pyramid" -> submitShield(state, poseStack, collector, true);
            default -> throw new IllegalArgumentException(
                    "Unsupported mc-26.2 scripted effect renderer: " + state.rendererId
                            + " (key=" + state.rendererKey + ")");
        }
    }

    private static void submitCross(ScriptedEntityRenderState<?> state, PoseStack stack,
                                    SubmitNodeCollector collector, CameraRenderState camera) {
        float alpha = lifeAlpha(state);
        float size = Math.max(0.01F, planFloat(state.rendererId, "size", 0.6F));
        int r = Mth.clamp(planInt(state.rendererId, "color-r", 180), 0, 255);
        int g = Mth.clamp(planInt(state.rendererId, "color-g", 220), 0, 255);
        int b = Mth.clamp(planInt(state.rendererId, "color-b", 255), 0, 255);
        int a = (int) (255.0F * alpha);
        stack.pushPose();
        stack.mulPose(camera.orientation);
        collector.submitCustomGeometry(stack, RenderTypes.LINES_TRANSLUCENT, (pose, vc) -> {
            Matrix4f matrix = pose.pose();
            line(vc, matrix, -size, 0, 0, size, 0, 0, r, g, b, a);
            line(vc, matrix, 0, -size, 0, 0, size, 0, r, g, b, a);
        });
        stack.popPose();
    }

    private static void submitRings(ScriptedEntityRenderState<?> state, PoseStack stack,
                                    SubmitNodeCollector collector) {
        collector.submitCustomGeometry(stack, RenderTypes.LINES_TRANSLUCENT, (pose, vc) -> {
            Matrix4f matrix = pose.pose();
            for (int layer = 0; layer < 3; layer++) {
                float cycle = (state.ageTicks + state.partialTick + layer * 1.2F) % 3.6F;
                float radius = 0.4F + cycle * 0.5F;
                int alpha = (int) (200.0F * (1.0F - cycle / 3.6F));
                for (int i = 0; i < 16; i++) {
                    double a0 = Math.PI * 2.0D * i / 16.0D;
                    double a1 = Math.PI * 2.0D * (i + 1) / 16.0D;
                    line(vc, matrix,
                            (float) Math.cos(a0) * radius, 0.02F, (float) Math.sin(a0) * radius,
                            (float) Math.cos(a1) * radius, 0.02F, (float) Math.sin(a1) * radius,
                            145, 95, 255, alpha);
                }
            }
        });
    }

    private static void submitPolyline(ScriptedEntityRenderState<?> state, PoseStack stack,
                                       SubmitNodeCollector collector) {
        float progress = Mth.clamp((state.ageTicks + state.partialTick)
                / Math.max(1.0F, state.lifeTicks), 0.0F, 1.0F);
        int segments = Mth.clamp(planInt(state.rendererId, "segments", 20), 2, 80);
        float length = Math.max(0.1F, planFloat(state.rendererId, "length", 20.0F));
        float amplitude = Math.max(0.0F, planFloat(state.rendererId, "wiggle-amp", 0.5F));
        float frequency = Math.max(0.0F, planFloat(state.rendererId, "wiggle-freq", 7.0F));
        int alpha = (int) (255.0F * Math.min(Mth.clamp(progress / 0.2F, 0, 1),
                Mth.clamp((1.0F - progress) / 0.2F, 0, 1)));
        stack.pushPose();
        stack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        stack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        collector.submitCustomGeometry(stack, RenderTypes.LINES_TRANSLUCENT, (pose, vc) -> {
            Matrix4f matrix = pose.pose();
            float px = 0, py = 0, pz = 0;
            for (int i = 1; i <= segments; i++) {
                float t = (float) i / segments;
                float wave = state.entityId * 0.37F + state.ageInTicks * 0.35F + t * frequency;
                float x = (float) Math.sin(wave) * amplitude * 0.2F;
                float y = (float) Math.cos(wave * 1.17F) * amplitude * 0.12F;
                float z = length * t;
                line(vc, matrix, px, py, pz, x, y, z, 145, 210, 255, alpha);
                px = x; py = y; pz = z;
            }
        });
        stack.popPose();
    }

    private static void submitAnimatedBillboard(ScriptedEntityRenderState<?> state, PoseStack stack,
                                                SubmitNodeCollector collector, CameraRenderState camera) {
        String prefix = planParamString(state.rendererId, "texture-prefix", "");
        int frames = Math.max(1, planInt(state.rendererId, "frame-count", 1));
        float frameMs = Math.max(1.0F, planFloat(state.rendererId, "frame-ms", 50.0F));
        int frame = Mth.clamp((int) (((state.ageTicks + state.partialTick) * 50.0F) / frameMs), 0, frames - 1);
        Identifier texture = parseTexture(prefix + frame + ".png");
        if (texture == null) return;
        float half = Math.max(0.01F, planFloat(state.rendererId, "half-size", 0.5F));
        stack.pushPose();
        stack.translate(0.0F, planFloat(state.rendererId, "offset-y", 0.0F),
                planFloat(state.rendererId, "offset-z", 0.0F));
        stack.mulPose(camera.orientation);
        submitQuad(stack, collector, texture, half, half, 255);
        stack.popPose();
    }

    private static void submitSpinningCoin(ScriptedEntityRenderState<?> state, PoseStack stack,
                                           SubmitNodeCollector collector) {
        Identifier front = parseTexture(planParamString(state.rendererId, "front-texture", ""));
        Identifier back = parseTexture(planParamString(state.rendererId, "back-texture", ""));
        if (front == null || back == null) return;
        float scale = Math.max(0.01F, planFloat(state.rendererId, "scale", 0.3F));
        float period = Math.max(1.0F, planFloat(state.rendererId, "rotation-period-ms", 300.0F));
        stack.pushPose();
        stack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        stack.translate(planFloat(state.rendererId, "offset-x", -0.63F),
                planFloat(state.rendererId, "offset-y", 1.0F),
                planFloat(state.rendererId, "offset-z", 0.3F));
        stack.scale(scale, scale, scale);
        stack.mulPose(Axis.YP.rotation((state.ageTicks + state.partialTick) * 50.0F
                * ((float) Math.PI * 2.0F / period)));
        submitQuad(stack, collector, front, 0.5F, 0.5F, 255);
        stack.mulPose(Axis.YP.rotationDegrees(180.0F));
        submitQuad(stack, collector, back, 0.5F, 0.5F, 255);
        stack.popPose();
    }

    private static void submitShield(ScriptedEntityRenderState<?> state, PoseStack stack,
                                     SubmitNodeCollector collector, boolean pyramid) {
        Identifier texture = parseTexture(planParamString(state.rendererId, "texture", ""));
        if (texture == null) return;
        float age = state.ageTicks + state.partialTick;
        float scale = Math.max(0.01F, planFloat(state.rendererId, "scale", 1.8F))
                * (0.2F + 0.8F * Mth.clamp(age / 15.0F, 0, 1));
        int alpha = (int) (255.0F * Mth.clamp(age / 6.0F, 0, 1));
        stack.pushPose();
        stack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        stack.mulPose(Axis.XP.rotationDegrees(state.xRot));
        stack.scale(scale, scale, scale);
        if (!pyramid) {
            stack.mulPose(Axis.ZP.rotationDegrees(age * 0.1F));
            submitQuad(stack, collector, texture, 0.5F, 0.5F, alpha);
        } else {
            collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(texture), (pose, vc) -> {
                Matrix4f m = pose.pose();
                float[][] rim = {{-1,0,0},{0,-1,0},{1,0,0},{0,1,0}};
                for (int i = 0; i < 4; i++) {
                    vertex(vc, pose, m, rim[i][0], rim[i][1], 0, 0, 1, alpha);
                    vertex(vc, pose, m, rim[(i + 1) % 4][0], rim[(i + 1) % 4][1], 0, 1, 1, alpha);
                    vertex(vc, pose, m, 0, 0, 1, 0.5F, 0, alpha);
                    vertex(vc, pose, m, 0, 0, 1, 0.5F, 0, alpha);
                }
            });
        }
        stack.popPose();
    }

    private static void submitQuad(PoseStack stack, SubmitNodeCollector collector,
                                   Identifier texture, float halfWidth, float halfHeight, int alpha) {
        collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(texture), (pose, vc) -> {
            Matrix4f m = pose.pose();
            vertex(vc, pose, m, -halfWidth, -halfHeight, 0, 0, 1, alpha);
            vertex(vc, pose, m, halfWidth, -halfHeight, 0, 1, 1, alpha);
            vertex(vc, pose, m, halfWidth, halfHeight, 0, 1, 0, alpha);
            vertex(vc, pose, m, -halfWidth, halfHeight, 0, 0, 0, alpha);
        });
    }

    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose,
                               Matrix4f matrix, float x, float y, float z, float u, float v, int alpha) {
        vc.addVertex(matrix, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0x00F000F0)
                .setNormal(pose, 0, 0, 1);
    }

    private static Identifier parseTexture(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return ResourceLocations.parse(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static float lifeAlpha(ScriptedEntityRenderState<?> state) {
        return Mth.clamp(1.0F - (state.ageTicks + state.partialTick)
                / Math.max(1.0F, state.lifeTicks), 0.0F, 1.0F);
    }
}
