package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.systems.RenderSystem;
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

public final class DiamondShieldRenderer extends EntityRenderer<ScriptedEffectEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("my_mod", "textures/effects/diamond_shield.png");

    // Pyramid vertices: 4 base corners + 1 apex
    // Base: (-1,0,0), (0,0,-1), (1,0,0), (0,0,1) ; Apex: (0,1,0)
    private static final float[][] VERTS = {
        {-1, 0,  0},  // 0 - left
        { 0, 0, -1},  // 1 - back
        { 1, 0,  0},  // 2 - right
        { 0, 0,  1},  // 3 - front
        { 0, 1,  0},  // 4 - apex
    };

    // 4 triangular faces: (base0, base1, apex) as degenerate quads
    private static final int[][] FACES = {
        {0, 1, 4},
        {1, 2, 4},
        {2, 3, 4},
        {3, 0, 4},
    };

    // UVs per vertex per face - mapping from original (u,v) pairs
    // Original: v0→(0,0), v1→(1,1), v2→(0,0), v3→(1,1), apex→(0,1)
    // Face UV: [u0,v0, u1,v1, uApex,vApex]
    private static final float[][] FACE_UVS = {
        {0,0,  1,1,  0.5f,1},
        {1,1,  0,0,  0.5f,1},
        {0,0,  1,1,  0.5f,1},
        {1,1,  0,0,  0.5f,1},
    };

    public DiamondShieldRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        RenderSystem.disableDepthTest();
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));
        poseStack.scale(1.5F, 1.5F, 1.5F);

        Matrix4f mat = poseStack.last().pose();
        Matrix3f norm = poseStack.last().normal();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucentCull(TEXTURE));

        for (int f = 0; f < FACES.length; f++) {
            int[] face = FACES[f];
            float[] uvs = FACE_UVS[f];
            float[] v0 = VERTS[face[0]];
            float[] v1 = VERTS[face[1]];
            float[] v2 = VERTS[face[2]]; // apex

            // Normal for this face (cross product of edges)
            float ex = v1[0]-v0[0], ey = v1[1]-v0[1], ez = v1[2]-v0[2];
            float fx = v2[0]-v0[0], fy = v2[1]-v0[1], fz = v2[2]-v0[2];
            float nx = ey*fz - ez*fy, ny = ez*fx - ex*fz, nz = ex*fy - ey*fx;
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (len > 0) { nx/=len; ny/=len; nz/=len; }

            // Render as degenerate quad (v0, v1, apex, apex)
            vc.vertex(mat, v0[0], v0[1], v0[2]).color(255,255,255,220)
                    .uv(uvs[0], uvs[1]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                    .normal(norm, nx, ny, nz).endVertex();
            vc.vertex(mat, v1[0], v1[1], v1[2]).color(255,255,255,220)
                    .uv(uvs[2], uvs[3]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                    .normal(norm, nx, ny, nz).endVertex();
            vc.vertex(mat, v2[0], v2[1], v2[2]).color(255,255,255,220)
                    .uv(uvs[4], uvs[5]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                    .normal(norm, nx, ny, nz).endVertex();
            // Repeat apex to close the quad (degenerate)
            vc.vertex(mat, v2[0], v2[1], v2[2]).color(255,255,255,220)
                    .uv(uvs[4], uvs[5]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight)
                    .normal(norm, nx, ny, nz).endVertex();
        }

        poseStack.popPose();
        RenderSystem.enableDepthTest();
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return TEXTURE;
    }
}
