package cn.li.fabric1201.client;

import cn.li.fabric1201.entity.FabricEntities;
import cn.li.fabric1201.shim.FabricClientHelper;
import cn.li.mc1201.client.render.effect.BloodSplashRenderer;
import cn.li.mc1201.client.render.effect.DiamondShieldRenderer;
import cn.li.mc1201.client.render.effect.GenericArcRenderer;
import cn.li.mc1201.client.render.effect.IntensifyEffectRenderer;
import cn.li.mc1201.client.render.effect.MdBallRenderer;
import cn.li.mc1201.client.render.effect.MdShieldRenderer;
import cn.li.mc1201.client.render.effect.RippleMarkRenderer;
import cn.li.mc1201.client.render.effect.ScriptedBlockBodyRenderer;
import cn.li.mc1201.client.render.effect.ScriptedEffectBillboardRenderer;
import cn.li.mc1201.client.render.effect.ScriptedMarkerBillboardRenderer;
import cn.li.mc1201.client.render.effect.ScriptedRayCompositeRenderer;
import cn.li.mc1201.client.render.effect.SurroundArcRenderer;
import cn.li.mc1201.client.render.effect.TpMarkingRenderer;
import cn.li.mc1201.client.render.effect.WireMarkerRenderer;
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

        FabricClientHelper.registerEntityRenderer(effectType, pickEffectRenderer(rendererId));
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

        if ("tp-marking".equals(rendererId)) {
            FabricClientHelper.registerEntityRenderer(markerType, TpMarkingRenderer::new);
        } else if ("wire-marker".equals(rendererId)) {
            FabricClientHelper.registerEntityRenderer(markerType, WireMarkerRenderer::new);
        } else {
            FabricClientHelper.registerEntityRenderer(markerType, ScriptedMarkerBillboardRenderer::new);
        }
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

    private static EntityRendererProvider<?> pickEffectRenderer(String rendererId) {
        return switch (rendererId) {
            case "diamond-shield" -> DiamondShieldRenderer::new;
            case "md-shield" -> MdShieldRenderer::new;
            case "surround-arc" -> SurroundArcRenderer::new;
            case "arc-generic" -> GenericArcRenderer::new;
            case "ripple-mark" -> RippleMarkRenderer::new;
            case "blood-splash" -> BloodSplashRenderer::new;
            case "md-ball" -> MdBallRenderer::new;
            case "intensify-effect" -> IntensifyEffectRenderer::new;
            case "effect-billboard" -> ScriptedEffectBillboardRenderer::new;
            default -> ScriptedEffectBillboardRenderer::new;
        };
    }
}