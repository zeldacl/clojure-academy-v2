package cn.li.mc262.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

/** Shared render-state extraction and draw-plan access for scripted renderers. */
abstract class AbstractScriptedGeometryRenderer<T extends Entity>
        extends EntityRenderer<T, ScriptedEntityRenderState<T>> {
    protected final String configuredRendererId;

    protected AbstractScriptedGeometryRenderer(EntityRendererProvider.Context context,
                                               String configuredRendererId) {
        super(context);
        this.configuredRendererId = configuredRendererId == null ? "" : configuredRendererId;
    }

    @Override
    public ScriptedEntityRenderState<T> createRenderState() {
        return new ScriptedEntityRenderState<>();
    }

    @Override
    public void extractRenderState(T entity, ScriptedEntityRenderState<T> state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.entity = entity;
        state.partialTick = partialTick;
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
        state.entityId = entity.getId();
        state.ageTicks = ScriptedRenderAccess.getAgeTicks(entity);
        state.rendererId = configuredRendererId;
        state.rendererKey = configuredRendererId;
    }

    protected static void line(VertexConsumer consumer, Matrix4f matrix,
                               float x1, float y1, float z1, float x2, float y2, float z2,
                               int red, int green, int blue, int alpha) {
        consumer.addVertex(matrix, x1, y1, z1).setColor(red, green, blue, alpha)
                .setNormal(0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, x2, y2, z2).setColor(red, green, blue, alpha)
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}
