package cn.li.mc1201.client.render;

import cn.li.mc1201.client.render.effect.BloodSplashRenderer;
import cn.li.mc1201.client.render.effect.DiamondShieldRenderer;
import cn.li.mc1201.client.render.effect.GenericArcRenderer;
import cn.li.mc1201.client.render.effect.IntensifyEffectRenderer;
import cn.li.mc1201.client.render.effect.MdBallRenderer;
import cn.li.mc1201.client.render.effect.MdShieldRenderer;
import cn.li.mc1201.client.render.effect.RippleMarkRenderer;
import cn.li.mc1201.client.render.effect.ScriptedEffectBillboardRenderer;
import cn.li.mc1201.client.render.effect.ScriptedMarkerBillboardRenderer;
import cn.li.mc1201.client.render.effect.SurroundArcRenderer;
import cn.li.mc1201.client.render.effect.TpMarkingRenderer;
import cn.li.mc1201.client.render.effect.WireMarkerRenderer;
import cn.li.mc1201.entity.ScriptedEffectEntity;
import cn.li.mc1201.entity.ScriptedMarkerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Shared renderer-id dispatch for scripted effect/marker entities.
 */
public final class EffectRendererDispatcher {

    private EffectRendererDispatcher() {
    }

    public static EntityRendererProvider<ScriptedEffectEntity> pickEffectRenderer(String rendererId) {
        String id = rendererId == null ? "" : rendererId;
        return switch (id) {
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

    public static EntityRendererProvider<ScriptedMarkerEntity> pickMarkerRenderer(String rendererId) {
        String id = rendererId == null ? "" : rendererId;
        return switch (id) {
            case "tp-marking" -> TpMarkingRenderer::new;
            case "wire-marker" -> WireMarkerRenderer::new;
            case "marker-billboard" -> ScriptedMarkerBillboardRenderer::new;
            default -> ScriptedMarkerBillboardRenderer::new;
        };
    }
}
