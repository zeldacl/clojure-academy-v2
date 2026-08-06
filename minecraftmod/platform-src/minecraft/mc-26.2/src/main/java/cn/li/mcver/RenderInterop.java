package cn.li.mcver;

import cn.li.mc262.client.render.SubmitNodeRenderBufferAdapter;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Cross-version VertexConsumer helpers.
 * 26.2: addVertex / set* plus deferred SubmitNode collector path.
 */
public final class RenderInterop {
    private RenderInterop() {
    }

    public static void submitVertex(
            VertexConsumer vc,
            PoseStack poseStack,
            float x, float y, float z,
            float r, float g, float b, float a,
            float u, float v,
            int overlay, int light,
            float nx, float ny, float nz) {
        if (vc instanceof SubmitNodeRenderBufferAdapter.DeferredVertexConsumer deferred) {
            deferred.submitVertex(
                    poseStack,
                    x, y, z,
                    r, g, b, a,
                    u, v,
                    overlay, light,
                    nx, ny, nz);
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setUv2(light & 0xFFFF, (light >> 16) & 0xFFFF)
                .setNormal(pose, nx, ny, nz);
    }

    public static void addColoredVertex(VertexConsumer vc, float x, float y, float z, float r, float g, float b, float a) {
        vc.addVertex(x, y, z).setColor(r, g, b, a);
    }

    public static void addColoredVertex(Object vc, double x, double y, double z, int r, int g, int b, int a) {
        if (vc instanceof VertexConsumer consumer) {
            addColoredVertex(consumer, (float) x, (float) y, (float) z,
                    r / 255f, g / 255f, b / 255f, a / 255f);
        }
    }

    public static void addVertex(VertexConsumer vc, float x, float y, float z) {
        vc.addVertex(x, y, z);
    }

    public static void addVertex(Object vc, double x, double y, double z) {
        if (vc instanceof VertexConsumer consumer) {
            addVertex(consumer, (float) x, (float) y, (float) z);
        }
    }
}
