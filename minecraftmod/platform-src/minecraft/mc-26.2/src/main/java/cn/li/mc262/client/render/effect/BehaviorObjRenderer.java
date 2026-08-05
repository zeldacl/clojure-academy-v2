package cn.li.mc262.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

@OnlyIn(Dist.CLIENT)
public final class BehaviorObjRenderer<T extends Entity>
        extends AbstractScriptedGeometryRenderer<T> {
    public BehaviorObjRenderer(EntityRendererProvider.Context context) {
        super(context, "silbarn");
    }

    @Override
    public void extractRenderState(T entity, ScriptedEntityRenderState<T> state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.behaviorHit = ScriptedRenderAccess.isBehaviorHit(entity);
    }

    @Override
    public void submit(ScriptedEntityRenderState<T> state, PoseStack stack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.behaviorHit) {
            return;
        }
        float ax = 0.3F + Math.floorMod(state.entityId * 17, 71) / 71.0F;
        float ay = 0.4F + Math.floorMod(state.entityId * 31, 67) / 67.0F;
        float az = 0.2F + Math.floorMod(state.entityId * 43, 73) / 73.0F;
        stack.pushPose();
        stack.scale(0.38F, 0.38F, 0.38F);
        stack.mulPose(Axis.of(new org.joml.Vector3f(ax, ay, az).normalize())
                .rotationDegrees(state.ageInTicks * 1.5F));
        stack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        collector.submitCustomGeometry(stack, RenderTypes.lightning(), (pose, vc) -> {
            Matrix4f matrix = pose.pose();
            float[][] points = {{0,0.85F,0},{0,-0.85F,0},{-0.65F,0,-0.65F},
                    {0.65F,0,-0.65F},{0.65F,0,0.65F},{-0.65F,0,0.65F}};
            int[][] faces = {{0,2,3},{0,3,4},{0,4,5},{0,5,2},
                    {1,3,2},{1,4,3},{1,5,4},{1,2,5}};
            for (int i = 0; i < faces.length; i++) {
                int[] face = faces[i];
                int r = i < 4 ? 175 : 105;
                int g = i < 4 ? 105 : 65;
                int b = i < 4 ? 235 : 170;
                solidVertex(vc, matrix, points[face[0]], r, g, b);
                solidVertex(vc, matrix, points[face[1]], r, g, b);
                solidVertex(vc, matrix, points[face[2]], r, g, b);
                solidVertex(vc, matrix, points[face[2]], r, g, b);
            }
        });
        stack.popPose();
        super.submit(state, stack, collector, camera);
    }

    private static void solidVertex(com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                    Matrix4f matrix, float[] point, int r, int g, int b) {
        consumer.addVertex(matrix, point[0], point[1], point[2]).setColor(r, g, b, 235);
    }
}
