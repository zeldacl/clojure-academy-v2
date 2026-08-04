package cn.li.mc1211.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import cn.li.mc1211.clj.ClojureInterop;
import cn.li.mc1211.entity.ScriptedEffectEntity;
import cn.li.mcbase.entity.spec.ScriptedEffectSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class ScriptedEffectBillboardRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final String SCRIPT_RENDER_RUNTIME_NS = "cn.li.mcbase.client.render.script-render-runtime";
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

    private static String drawPlanParamString(String rendererId, String paramKey, String defaultValue) {
        Object value = ClojureInterop.invoke(
                SCRIPT_RENDER_RUNTIME_NS,
                "draw-plan-param-string",
                rendererId,
                paramKey,
                defaultValue
        );
        return value instanceof String string ? string : defaultValue;
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
            case "billboard-cross" -> renderBillboardCross(entity, spec, rendererId, partialTick, poseStack, bufferSource);
            case "animated-billboard" -> renderAnimatedBillboard(entity, rendererId, partialTick, poseStack, bufferSource);
            case "spinning-double-sided" -> renderSpinningDoubleSided(
                    entity, rendererId, partialTick, poseStack, bufferSource, packedLight);
            case "tiered-zigzag" -> TieredZigzagArcRenderer.render(entity, spec, rendererId, partialTick, poseStack, bufferSource);
            case "spinning-shield" -> renderSpinningShield(entity, rendererId, partialTick, poseStack, bufferSource);
            case "diamond-pyramid" -> renderDiamondPyramid(entity, rendererId, partialTick, poseStack, bufferSource);
            default -> throw new IllegalArgumentException("Unsupported renderer key for effect rendererId="
                    + rendererId + ": " + rendererKey);
        }
    }

    private static final float BILLBOARD_CROSS_DEFAULT_SIZE = 0.6F;
    private static final int BILLBOARD_CROSS_DEFAULT_R = 180;
    private static final int BILLBOARD_CROSS_DEFAULT_G = 220;
    private static final int BILLBOARD_CROSS_DEFAULT_B = 255;

    private static final float SHIELD_DEFAULT_SCALE = 1.8F;
    // Original RenderMdShield spins at lerpf(0.8, 2, ...) degrees PER SECOND
    // (GameTimer seconds) - a full turn takes minutes, so the shield is
    // visually static. A fast spin makes the diamond's bounding width
    // oscillate ~41% (45-degree corners), reading as "suddenly smaller then
    // restored".
    private static final float SHIELD_DEFAULT_SPIN_DEG_PER_TICK = 0.1F;

    /**
     * LightShield / JetEngine shield (matching original RenderMdShield):
     * one translucent quad, oriented to the owner's yaw/pitch, spinning
     * around its own normal (in-plane - never edge-on like the coin
     * renderer), SIZE grows from 0.2x over 15 ticks, alpha fades in over
     * 6 ticks.
     */
    private void renderSpinningShield(T entity,
                                      String rendererId,
                                      float partialTick,
                                      PoseStack poseStack,
                                      MultiBufferSource bufferSource) {
        String textureId = drawPlanParamString(rendererId, "texture", "");
        ResourceLocation texture = ResourceLocation.tryParse(textureId);
        if (texture == null) {
            return;
        }
        Player owner = entity instanceof ScriptedEffectEntity effect ? effect.getOwnerPlayer() : null;
        if (owner == null) {
            return;
        }
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
        float growth = Mth.clamp(age / 15.0F, 0.0F, 1.0F);
        float size = Math.max(0.01F, drawPlanParamFloat(rendererId, "scale", SHIELD_DEFAULT_SCALE))
                * (0.2F + 0.8F * growth);
        float alpha = Mth.clamp(age / 6.0F, 0.0F, 1.0F);
        float rotation = (age * drawPlanParamFloat(rendererId, "spin-deg-per-tick", SHIELD_DEFAULT_SPIN_DEG_PER_TICK))
                % 360.0F;

        float yaw = owner.yBodyRot;
        float pitch = owner.getXRot();
        if (owner == Minecraft.getInstance().player
                && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            yaw = owner.getYRot();
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));
        poseStack.scale(size, size, 1.0F);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
        int a = (int) (255.0F * alpha);
        int fullBright = 0x00F000F0;

        vc.addVertex(mat, -0.5F, -0.5F, 0.0F).setColor(255, 255, 255, a)
                .setUv(0.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, 0.5F, -0.5F, 0.0F).setColor(255, 255, 255, a)
                .setUv(1.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, 0.5F, 0.5F, 0.0F).setColor(255, 255, 255, a)
                .setUv(1.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, -0.5F, 0.5F, 0.0F).setColor(255, 255, 255, a)
                .setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);

        poseStack.popPose();
    }

    /**
     * JetEngine's diamond shield (matching original EntityDiamondShield +
     * RenderDiamondShield): a 3D DIAMOND PYRAMID - four triangle faces from
     * the apex (0,0,1) to the four rim vertices of the diamond base
     * (-1,0,0)(0,-1,0)(1,0,0)(0,1,0), oriented to the owner's yaw/pitch.
     * SIZE grows from 0.2x over 15 ticks and alpha fades in over 6 ticks
     * (same growth as the light-shield). Each face is emitted as a
     * degenerate QUAD (apex repeated) since entityTranslucent is QUADS.
     */
    private void renderDiamondPyramid(T entity,
                                      String rendererId,
                                      float partialTick,
                                      PoseStack poseStack,
                                      MultiBufferSource bufferSource) {
        String textureId = drawPlanParamString(rendererId, "texture", "");
        ResourceLocation texture = ResourceLocation.tryParse(textureId);
        if (texture == null) {
            return;
        }
        Player owner = entity instanceof ScriptedEffectEntity effect ? effect.getOwnerPlayer() : null;
        if (owner == null) {
            return;
        }
        float age = ScriptedRenderAccess.getAgeTicks(entity) + partialTick;
        float growth = Mth.clamp(age / 15.0F, 0.0F, 1.0F);
        float size = Math.max(0.01F, drawPlanParamFloat(rendererId, "scale", 1.8F))
                * (0.2F + 0.8F * growth);
        float alpha = Mth.clamp(age / 6.0F, 0.0F, 1.0F);

        float yaw = owner.yBodyRot;
        float pitch = owner.getXRot();
        if (owner == Minecraft.getInstance().player
                && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            yaw = owner.getYRot();
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.scale(size, size, size);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
        int a = (int) (255.0F * alpha);
        int fullBright = 0x00F000F0;

        // Upstream mesh UVs: {0,0}{1,1}{0,0}{1,1} for the four rim vertices,
        // {0,1} for the shared apex - the diamond texture runs continuously
        // ACROSS the faces (each face reuses the apex UV), so the pyramid
        // reads as one solid gem, not four independent texture-stretched
        // triangles.
        float[][] rim = {{-1.0F, 0.0F, 0.0F}, {0.0F, -1.0F, 0.0F}, {1.0F, 0.0F, 0.0F}, {0.0F, 1.0F, 0.0F}};
        float[][] rimUvs = {{0.0F, 0.0F}, {1.0F, 1.0F}, {0.0F, 0.0F}, {1.0F, 1.0F}};
        float[] apex = {0.0F, 0.0F, 1.0F};
        float[] apexUv = {0.0F, 1.0F};
        for (int f = 0; f < 4; f++) {
            float[][] face = {rim[f], rim[(f + 1) % 4], apex, apex};
            float[][] faceUvs = {rimUvs[f], rimUvs[(f + 1) % 4], apexUv, apexUv};
            for (int i = 0; i < 4; i++) {
                vc.addVertex(mat, face[i][0], face[i][1], face[i][2]).setColor(255, 255, 255, a)
                        .setUv(faceUvs[i][0], faceUvs[i][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                        .setNormal(pose, 0.0F, 0.0F, 1.0F);
            }
        }

        poseStack.popPose();
    }

    private void renderSpinningDoubleSided(T entity,
                                           String rendererId,
                                           float partialTick,
                                           PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           int packedLight) {
        String frontId = drawPlanParamString(rendererId, "front-texture", "");
        String backId = drawPlanParamString(rendererId, "back-texture", "");
        ResourceLocation frontTexture = ResourceLocation.tryParse(frontId);
        ResourceLocation backTexture = ResourceLocation.tryParse(backId);
        if (frontTexture == null || backTexture == null) {
            return;
        }

        Player owner = entity instanceof ScriptedEffectEntity effect ? effect.getOwnerPlayer() : null;
        if (owner != null && entity.getY() < owner.getY()) {
            return;
        }

        float scale = Math.max(0.01F, drawPlanParamFloat(rendererId, "scale", 0.3F));
        float offsetX = drawPlanParamFloat(rendererId, "offset-x", -0.63F);
        float offsetY = drawPlanParamFloat(rendererId, "offset-y", 1.0F);
        float offsetZ = drawPlanParamFloat(rendererId, "offset-z", 0.3F);
        float periodMs = Math.max(1.0F, drawPlanParamFloat(rendererId, "rotation-period-ms", 300.0F));
        float ageMs = (ScriptedRenderAccess.getAgeTicks(entity) + partialTick) * 50.0F;
        float rotation = (ageMs % periodMs) * ((float) (Math.PI * 2.0) / periodMs);

        float seed = entity.getId() * 0.7548777F;
        float axisX = 0.1F + Mth.abs(Mth.sin(seed * 1.7F));
        float axisY = Mth.abs(Mth.sin(seed * 2.3F + 0.7F));
        float axisZ = Mth.abs(Mth.cos(seed * 1.1F + 0.2F));
        float axisLength = Mth.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        axisX /= axisLength;
        axisY /= axisLength;
        axisZ /= axisLength;

        float ownerYaw = owner == null ? entity.getYRot() : owner.yBodyRot;
        boolean firstPersonOwner = owner != null
                && owner == Minecraft.getInstance().player
                && Minecraft.getInstance().options.getCameraType().isFirstPerson();
        if (firstPersonOwner) {
            ownerYaw = owner.getYRot();
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-ownerYaw));
        poseStack.translate(offsetX, offsetY, offsetZ);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0.5F, 0.5F, 0.0F);
        poseStack.mulPose(new Quaternionf().rotationAxis(rotation, axisX, axisY, axisZ));
        poseStack.translate(-0.5F, -0.5F, 0.0F);

        drawCoinFace(poseStack, bufferSource, frontTexture, packedLight, 0.03125F, false);
        drawCoinFace(poseStack, bufferSource, backTexture, packedLight, -0.03125F, true);
        poseStack.popPose();
    }

    private static void drawCoinFace(PoseStack poseStack,
                                     MultiBufferSource bufferSource,
                                     ResourceLocation texture,
                                     int packedLight,
                                     float z,
                                     boolean reverse) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        float normalZ = reverse ? -1.0F : 1.0F;

        if (!reverse) {
            coinVertex(vc, mat, pose, 0.0F, 0.0F, z, 0.0F, 1.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 1.0F, 0.0F, z, 1.0F, 1.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 1.0F, 1.0F, z, 1.0F, 0.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 0.0F, 1.0F, z, 0.0F, 0.0F, packedLight, normalZ);
        } else {
            coinVertex(vc, mat, pose, 1.0F, 0.0F, z, 0.0F, 1.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 0.0F, 0.0F, z, 1.0F, 1.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 0.0F, 1.0F, z, 1.0F, 0.0F, packedLight, normalZ);
            coinVertex(vc, mat, pose, 1.0F, 1.0F, z, 0.0F, 0.0F, packedLight, normalZ);
        }
    }

    private static void coinVertex(VertexConsumer vc,
                                   Matrix4f mat,
                                   PoseStack.Pose pose,
                                   float x,
                                   float y,
                                   float z,
                                   float u,
                                   float v,
                                   int packedLight,
                                   float normalZ) {
        vc.addVertex(mat, x, y, z).setColor(255, 255, 255, 255)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight)
                .setNormal(pose, 0.0F, 0.0F, normalZ);
    }

    private void renderAnimatedBillboard(T entity,
                                         String rendererId,
                                         float partialTick,
                                         PoseStack poseStack,
                                         MultiBufferSource bufferSource) {
        String prefix = drawPlanParamString(rendererId, "texture-prefix", "");
        int frameCount = Math.max(1, drawPlanParamInt(rendererId, "frame-count", 1));
        float frameMs = Math.max(1.0F, drawPlanParamFloat(rendererId, "frame-ms", 50.0F));
        float ageMs = (ScriptedRenderAccess.getAgeTicks(entity) + partialTick) * 50.0F;
        int frame = Mth.clamp((int) (ageMs / frameMs), 0, frameCount - 1);
        ResourceLocation texture = ResourceLocation.tryParse(prefix + frame + ".png");
        if (texture == null) {
            return;
        }

        float halfSize = Math.max(0.01F, drawPlanParamFloat(rendererId, "half-size", 0.5F));
        float offsetY = drawPlanParamFloat(rendererId, "offset-y", 0.0F);
        float offsetZ = drawPlanParamFloat(rendererId, "offset-z", 0.0F);
        float yaw = -entity.getYRot() * ((float) Math.PI / 180.0F);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.translate(0.0F, offsetY, offsetZ);
        poseStack.mulPose(Axis.YP.rotation(-yaw));
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
        int fullBright = 0x00F000F0;

        vc.addVertex(mat, -halfSize, -halfSize, 0.0F).setColor(255, 255, 255, 255)
                .setUv(0.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, halfSize, -halfSize, 0.0F).setColor(255, 255, 255, 255)
                .setUv(1.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, halfSize, halfSize, 0.0F).setColor(255, 255, 255, 255)
                .setUv(1.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(mat, -halfSize, halfSize, 0.0F).setColor(255, 255, 255, 255)
                .setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(fullBright)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);

        poseStack.popPose();
    }

    private void renderBillboardCross(T entity,
                                      ScriptedEffectSpec spec,
                                      String rendererId,
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
        float size = Math.max(0.01F, drawPlanParamFloat(rendererId, "size", BILLBOARD_CROSS_DEFAULT_SIZE));
        int r = Mth.clamp(drawPlanParamInt(rendererId, "color-r", BILLBOARD_CROSS_DEFAULT_R), 0, 255);
        int g = Mth.clamp(drawPlanParamInt(rendererId, "color-g", BILLBOARD_CROSS_DEFAULT_G), 0, 255);
        int b = Mth.clamp(drawPlanParamInt(rendererId, "color-b", BILLBOARD_CROSS_DEFAULT_B), 0, 255);

        vc.addVertex(mat, -size, 0.0F, 0.0F).setColor(r, g, b, a).setNormal(0.0F, 1.0F, 0.0F);
        vc.addVertex(mat, size, 0.0F, 0.0F).setColor(r, g, b, a).setNormal(0.0F, 1.0F, 0.0F);

        vc.addVertex(mat, 0.0F, -size, 0.0F).setColor(r, g, b, a).setNormal(0.0F, 1.0F, 0.0F);
        vc.addVertex(mat, 0.0F, size, 0.0F).setColor(r, g, b, a).setNormal(0.0F, 1.0F, 0.0F);

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
                vc.addVertex(mat, x1, 0.02F, z1).setColor(150, 100, 255, alpha).setNormal(0.0F, 1.0F, 0.0F);
                vc.addVertex(mat, x2, 0.02F, z2).setColor(120, 80, 200, alpha).setNormal(0.0F, 1.0F, 0.0F);
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

            vc.addVertex(mat, prevX, prevY, prevZ).setColor(110, 190, 255, a).setNormal(0.0F, 1.0F, 0.0F);
            vc.addVertex(mat, x, y, z).setColor(200, 230, 255, a).setNormal(0.0F, 1.0F, 0.0F);

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
