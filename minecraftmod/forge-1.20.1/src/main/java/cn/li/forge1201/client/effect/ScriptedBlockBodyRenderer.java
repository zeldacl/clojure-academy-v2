package cn.li.forge1201.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.li.forge1201.entity.ScriptedBlockBodyEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

public final class ScriptedBlockBodyRenderer extends EntityRenderer<ScriptedBlockBodyEntity> {
    public ScriptedBlockBodyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedBlockBodyEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        String blockId = entity.getSyncedBlockId();
        BlockState blockState = resolveBlockState(blockId);

        poseStack.pushPose();
        // Center the block model (block models are 0-1, center them at entity origin)
        poseStack.translate(-0.5, 0.0, -0.5);

        if (blockState != null) {
            BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
            blockRenderer.renderSingleBlock(blockState, poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
        } else {
            // Fallback: draw a wire box if block can't be resolved
            float w = Math.max(0.2F, entity.getBbWidth() * 0.5F);
            float h = Math.max(0.2F, entity.getBbHeight());
            poseStack.translate(0.5, 0.0, 0.5); // undo the translate for the box
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
            drawBoxLines(vc, mat, -w, 0.0F, -w, w, h, w, 210);
        }

        poseStack.popPose();
    }

    private static BlockState resolveBlockState(String blockId) {
        try {
            ResourceLocation loc = new ResourceLocation(blockId);
            Block block = BuiltInRegistries.BLOCK.get(loc);
            if (block == null) return null;
            return block.defaultBlockState();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void line(VertexConsumer vc, Matrix4f mat,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             int a) {
        vc.vertex(mat, x1, y1, z1).color(255, 255, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(mat, x2, y2, z2).color(255, 255, 255, a).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    private static void drawBoxLines(VertexConsumer vc, Matrix4f mat,
                                     float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     int alpha) {
        line(vc, mat, x0, y0, z0, x1, y0, z0, alpha);
        line(vc, mat, x1, y0, z0, x1, y0, z1, alpha);
        line(vc, mat, x1, y0, z1, x0, y0, z1, alpha);
        line(vc, mat, x0, y0, z1, x0, y0, z0, alpha);

        line(vc, mat, x0, y1, z0, x1, y1, z0, alpha);
        line(vc, mat, x1, y1, z0, x1, y1, z1, alpha);
        line(vc, mat, x1, y1, z1, x0, y1, z1, alpha);
        line(vc, mat, x0, y1, z1, x0, y1, z0, alpha);

        line(vc, mat, x0, y0, z0, x0, y1, z0, alpha);
        line(vc, mat, x1, y0, z0, x1, y1, z0, alpha);
        line(vc, mat, x1, y0, z1, x1, y1, z1, alpha);
        line(vc, mat, x0, y0, z1, x0, y1, z1, alpha);
    }

    @Override
    public ResourceLocation getTextureLocation(ScriptedBlockBodyEntity entity) {
        return null;
    }
}
