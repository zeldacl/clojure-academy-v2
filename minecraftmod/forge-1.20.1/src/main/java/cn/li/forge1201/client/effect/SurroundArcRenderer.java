package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Renders electric surround-arc effect using pre-generated jagged lightning templates.
 * Templates are generated once at startup; the renderer cycles through them every 2 ticks
 * to simulate flickering electricity, matching the original AcademyCraft ArcFactory style.
 */
public final class SurroundArcRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final int TEMPLATE_COUNT = 15;
    private static final int ARC_COUNT = 5;     // arcs per template
    private static final int PASSES = 4;        // subdivision passes (detail)

    // Each template: float[ARC_COUNT][2 * 3 * segments] where segments = 2^PASSES
    // Stored as flat arrays of (x,y,z) pairs per line segment
    private static final float[][][] TEMPLATES = new float[TEMPLATE_COUNT][][];

    static {
        Random rand = new Random(0xDEADBEEF);
        int segs = 1 << PASSES; // 16 segments per arc
        for (int t = 0; t < TEMPLATE_COUNT; t++) {
            TEMPLATES[t] = new float[ARC_COUNT][];
            for (int a = 0; a < ARC_COUNT; a++) {
                TEMPLATES[t][a] = generateArc(rand, segs);
            }
        }
    }

    /**
     * Generate one arc as a flat array of (x,y,z) line-segment endpoints.
     * The arc spans from one random point on the entity bounding box surface
     * to another, with recursive midpoint displacement to create a jagged path.
     */
    private static float[] generateArc(Random rand, int segs) {
        // Start and end: random points on a unit box surface at about radius 1.2
        float r = 1.2F;
        float h = 1.0F;
        float startAngle = rand.nextFloat() * 2.0F * (float)Math.PI;
        float endAngle   = startAngle + (float)Math.PI * (0.7F + rand.nextFloat() * 0.6F);

        // Points on a cylinder of radius r, height h (centered at 0)
        float[] pts = new float[(segs + 1) * 3];
        pts[0] = (float)Math.cos(startAngle) * r;
        pts[1] = rand.nextFloat() * h - h * 0.5F;
        pts[2] = (float)Math.sin(startAngle) * r;
        pts[segs*3]   = (float)Math.cos(endAngle) * r;
        pts[segs*3+1] = rand.nextFloat() * h - h * 0.5F;
        pts[segs*3+2] = (float)Math.sin(endAngle) * r;

        // Recursive midpoint displacement
        subdivideMidpoint(rand, pts, 0, segs, PASSES, r * 0.35F);

        // Flatten into line-segment pairs (each consecutive pair of points)
        float[] segments = new float[segs * 6];
        for (int i = 0; i < segs; i++) {
            int src = i * 3;
            int dst = i * 6;
            segments[dst]   = pts[src];
            segments[dst+1] = pts[src+1];
            segments[dst+2] = pts[src+2];
            segments[dst+3] = pts[src+3];
            segments[dst+4] = pts[src+4];
            segments[dst+5] = pts[src+5];
        }
        return segments;
    }

    private static void subdivideMidpoint(Random rand, float[] pts, int from, int to, int depth, float offset) {
        if (depth == 0 || to - from <= 1) return;
        int mid = (from + to) / 2;
        // Interpolate midpoint
        pts[mid*3]   = (pts[from*3]   + pts[to*3])   * 0.5F + (rand.nextFloat() - 0.5F) * 2 * offset;
        pts[mid*3+1] = (pts[from*3+1] + pts[to*3+1]) * 0.5F + (rand.nextFloat() - 0.5F) * 2 * offset * 0.6F;
        pts[mid*3+2] = (pts[from*3+2] + pts[to*3+2]) * 0.5F + (rand.nextFloat() - 0.5F) * 2 * offset;
        subdivideMidpoint(rand, pts, from, mid, depth-1, offset * 0.6F);
        subdivideMidpoint(rand, pts, mid,  to,  depth-1, offset * 0.6F);
    }

    public SurroundArcRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        float age = entity.getAgeTicks() + partialTick;
        float alpha = Math.max(0.0F, 1.0F - (age / 100.0F));
        if (alpha <= 0.0F) {
            return;
        }

        // Cycle templates every 2 ticks for flicker
        int templateIdx = ((int)(entity.getAgeTicks() / 2)) % TEMPLATE_COUNT;
        float[][] template = TEMPLATES[templateIdx];

        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        int a = (int)(255 * alpha);

        // Scale to entity size
        float scaleX = Math.max(0.5F, entity.getBbWidth() * 0.55F);
        float scaleY = Math.max(0.5F, entity.getBbHeight() * 0.55F);

        for (float[] arc : template) {
            int segs = arc.length / 6;
            for (int s = 0; s < segs; s++) {
                int i = s * 6;
                float x1 = arc[i]   * scaleX;
                float y1 = arc[i+1] * scaleY + entity.getBbHeight() * 0.5F;
                float z1 = arc[i+2] * scaleX;
                float x2 = arc[i+3] * scaleX;
                float y2 = arc[i+4] * scaleY + entity.getBbHeight() * 0.5F;
                float z2 = arc[i+5] * scaleX;
                vc.vertex(mat, x1, y1, z1).color(100, 180, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
                vc.vertex(mat, x2, y2, z2).color(180, 220, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
            }
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return null;
    }
}
