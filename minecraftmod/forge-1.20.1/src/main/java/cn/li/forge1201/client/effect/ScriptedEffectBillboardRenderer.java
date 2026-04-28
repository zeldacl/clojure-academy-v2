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
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class ScriptedEffectBillboardRenderer extends EntityRenderer<ScriptedEffectEntity> {
    public ScriptedEffectBillboardRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ScriptedEffectEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        ScriptedEffectSpec spec = ModEntities.getScriptedEffectSpec(entity.getType());
        float life = Math.max(1.0F, spec == null ? 15.0F : spec.getLifeTicks());
        float age = entity.getAgeTicks() + partialTick;
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

    @Override
    public ResourceLocation getTextureLocation(ScriptedEffectEntity entity) {
        return null;
    }
}
