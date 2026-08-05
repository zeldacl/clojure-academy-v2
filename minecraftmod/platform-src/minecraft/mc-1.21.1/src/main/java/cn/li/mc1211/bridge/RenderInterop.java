package cn.li.mc1211.bridge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 1.21 VertexConsumer helpers (addVertex / set* API; endVertex removed).
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

    public static void addVertex(VertexConsumer vc, float x, float y, float z) {
        vc.addVertex(x, y, z);
    }
}
