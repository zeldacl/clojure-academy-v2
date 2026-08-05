package cn.li.mc262.client.render.effect;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.2 stub: entity render pipeline moved to EntityRenderState + SubmitNodeCollector.
 * Full port deferred; keeps registration constructors compiling.
 */
@OnlyIn(Dist.CLIENT)
public final class ScriptedEffectBillboardRenderer<T extends Entity> extends EntityRenderer<T, EntityRenderState> {
    public ScriptedEffectBillboardRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
