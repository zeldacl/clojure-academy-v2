package cn.li.fabric1201.client;

import cn.li.fabric1201.entity.FabricEntities;
import cn.li.fabric1201.shim.FabricClientHelper;
import cn.li.mc1201.client.render.EffectRendererDispatcher;
import cn.li.mc1201.client.render.effect.ScriptedBlockBodyRenderer;
import cn.li.mc1201.client.render.effect.ScriptedRayCompositeRenderer;
import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.world.entity.EntityType;

/**
 * Fabric entity renderer registration for scripted runtime entities.
 */
public final class FabricClientRenderSetup {

    private FabricClientRenderSetup() {
    }

    public static void registerEntityRenderers() {
        registerEffectRenderer();
        registerProjectileRenderer();
        registerRayRenderer();
        registerMarkerRenderer();
        registerBlockBodyRenderer();
    }

    private static void registerEffectRenderer() {
        EntityType<?> effectType = FabricEntities.getEntityType("scripted-effect");
        if (effectType == null) {
            return;
        }
        ScriptedEffectSpec effectSpec = ScriptedEntitySpecAccess.getScriptedEffectSpec(effectType);
        String rendererId = effectSpec == null || effectSpec.getRendererId() == null || effectSpec.getRendererId().isBlank()
                ? "effect-billboard"
                : effectSpec.getRendererId();

        FabricClientHelper.registerEntityRenderer(effectType, EffectRendererDispatcher.pickEffectRenderer(rendererId));
    }

    private static void registerProjectileRenderer() {
        EntityType<?> projectileType = FabricEntities.getEntityType("scripted-projectile");
        if (projectileType == null) {
            return;
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        EntityRendererProvider<?> provider = (EntityRendererProvider) (context -> new ThrownItemRenderer(context));
        FabricClientHelper.registerEntityRenderer(projectileType, provider);
    }

    private static void registerRayRenderer() {
        EntityType<?> rayType = FabricEntities.getEntityType("scripted-ray");
        if (rayType == null) {
            return;
        }
        ScriptedRaySpec raySpec = ScriptedEntitySpecAccess.getScriptedRaySpec(rayType);
        String rendererId = raySpec == null || raySpec.getRendererId() == null || raySpec.getRendererId().isBlank()
                ? "ray-composite"
                : raySpec.getRendererId();

        if ("ray-composite".equals(rendererId)) {
            FabricClientHelper.registerEntityRenderer(rayType, ScriptedRayCompositeRenderer::new);
        }
    }

    private static void registerMarkerRenderer() {
        EntityType<?> markerType = FabricEntities.getEntityType("scripted-marker");
        if (markerType == null) {
            return;
        }
        ScriptedMarkerSpec markerSpec = ScriptedEntitySpecAccess.getScriptedMarkerSpec(markerType);
        String rendererId = markerSpec == null || markerSpec.getRendererId() == null || markerSpec.getRendererId().isBlank()
                ? "marker-billboard"
                : markerSpec.getRendererId();

        FabricClientHelper.registerEntityRenderer(markerType, EffectRendererDispatcher.pickMarkerRenderer(rendererId));
    }

    private static void registerBlockBodyRenderer() {
        EntityType<?> blockBodyType = FabricEntities.getEntityType("scripted-block-body");
        if (blockBodyType == null) {
            return;
        }
        ScriptedBlockBodySpec blockBodySpec = ScriptedEntitySpecAccess.getScriptedBlockBodySpec(blockBodyType);
        String rendererId = blockBodySpec == null || blockBodySpec.getRendererId() == null || blockBodySpec.getRendererId().isBlank()
                ? "block-body"
                : blockBodySpec.getRendererId();

        if ("block-body".equals(rendererId)) {
            FabricClientHelper.registerEntityRenderer(blockBodyType, ScriptedBlockBodyRenderer::new);
        }
    }

}