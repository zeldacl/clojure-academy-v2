package cn.li.fabric1201.client;

import cn.li.fabric1201.entity.FabricEntities;
import cn.li.fabric1201.shim.FabricClientHelper;
import cn.li.mc1201.clj.ClojureInterop;
import cn.li.mc1201.client.font.msdf.MsdfRenderTypes;
import cn.li.mc1201.client.render.EffectRendererDispatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import cn.li.mc1201.client.render.RenderProfileBootstrap;
import cn.li.mc1201.client.particle.SilbarnFragParticle;
import cn.li.mc1201.client.render.effect.ScriptedBlockBodyRenderer;
import cn.li.mc1201.client.render.effect.SilbarnObjRenderer;
import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;

/**
 * Fabric entity renderer registration for scripted runtime entities.
 */
public final class FabricClientRenderSetup {
    private static final Logger LOGGER = LogManager.getLogger();

    private FabricClientRenderSetup() {
    }

    public static void registerClientHooks() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            try {
                context.register(
                        new ResourceLocation("my_mod", "msdf_text"),
                        MsdfRenderTypes.MSDF_TEXT_FORMAT,
                        shader -> {
                            MsdfRenderTypes.setMsdfShader(shader);
                            ClojureInterop.requireNamespace("cn.li.mc1201.gui.cgui.font");
                            ClojureInterop.invoke("cn.li.mc1201.gui.cgui.font",
                                    "set-msdf-shader!", shader);
                        });
                ClojureInterop.requireNamespace("cn.li.mc1201.client.font.msdf-setup");
                ClojureInterop.invoke("cn.li.mc1201.client.font.msdf-setup", "on-shader-ready!");
            } catch (IOException e) {
                LOGGER.error("Failed to register MSDF text shader", e);
            }
        });
    }

    public static void registerParticleProviders() {
        var fragTypeRaw = BuiltInRegistries.PARTICLE_TYPE.get(new ResourceLocation("my_mod", "silbarn_frag"));
        if (fragTypeRaw instanceof SimpleParticleType spt) {
            ParticleFactoryRegistry.getInstance().register(spt, SilbarnFragParticle.Provider::new);
        }
    }

    public static void registerEntityRenderers() {
        RenderProfileBootstrap.runContentClientInitHooks();
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
        FabricClientHelper.registerEntityRenderer(rayType, EffectRendererDispatcher.pickRayRenderer(rendererId));
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
            if (blockBodySpec != null && "silbarn".equals(blockBodySpec.getHookId())) {
                FabricClientHelper.registerEntityRenderer(blockBodyType, SilbarnObjRenderer::new);
            } else {
                FabricClientHelper.registerEntityRenderer(blockBodyType, ScriptedBlockBodyRenderer::new);
            }
        }
    }

}