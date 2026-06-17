package cn.li.forge1201.client.render;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.entity.ModEntities;
import cn.li.mc1201.client.font.msdf.MsdfRenderTypes;
import cn.li.mc1201.client.render.EffectRendererDispatcher;
import cn.li.mc1201.client.render.ModRenderTypes;
import cn.li.mc1201.client.render.RenderProfileBootstrap;
import cn.li.mc1201.client.render.effect.ScriptedBlockBodyRenderer;
import cn.li.mc1201.entity.ScriptedBlockBodyEntity;
import cn.li.mc1201.entity.ScriptedEffectEntity;
import cn.li.mc1201.entity.ScriptedMarkerEntity;
import cn.li.mc1201.entity.ScriptedProjectileEntity;
import cn.li.mc1201.entity.ScriptedRayEntity;
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * Centralized client rendering registration entrypoint for Forge.
 */
public final class ForgeClientRenderRegistry {
    private static ShaderInstance plasmaBodyShader;

    private ForgeClientRenderRegistry() {
    }

    public static void registerEntityAndBlockRenderers(EntityRenderersEvent.RegisterRenderers event) {
        RenderProfileBootstrap.runContentClientInitHooks();

        for (String registryName : ModEntities.getScriptedEffectRegistryNames()) {
            EntityType<ScriptedEffectEntity> effectType = ModEntities.getEntityType(registryName, ScriptedEffectEntity.class);
            if (effectType == null) {
                continue;
            }
            String rendererId = "effect-billboard";
            if (ModEntities.getScriptedEffectSpec(registryName) != null) {
                rendererId = ModEntities.getScriptedEffectSpec(registryName).getRendererId();
            }
            event.registerEntityRenderer(effectType, EffectRendererDispatcher.pickEffectRenderer(rendererId));
        }

        EntityType<ScriptedProjectileEntity> magHook =
            ModEntities.getEntityType("entity_mag_hook", ScriptedProjectileEntity.class);
        if (magHook != null) {
            event.registerEntityRenderer(magHook, ThrownItemRenderer::new);
        }

        for (String registryName : ModEntities.getScriptedRayRegistryNames()) {
            EntityType<ScriptedRayEntity> rayType = ModEntities.getEntityType(registryName, ScriptedRayEntity.class);
            if (rayType == null) {
                continue;
            }
            ScriptedRaySpec raySpec = ModEntities.getScriptedRaySpec(registryName);
            String rendererId = raySpec == null ? "ray-composite" : raySpec.getRendererId();
            event.registerEntityRenderer(rayType, EffectRendererDispatcher.pickRayRenderer(rendererId));
        }

        for (String registryName : ModEntities.getScriptedMarkerRegistryNames()) {
            EntityType<ScriptedMarkerEntity> markerType = ModEntities.getEntityType(registryName, ScriptedMarkerEntity.class);
            if (markerType == null) {
                continue;
            }
            ScriptedMarkerSpec markerSpec = ModEntities.getScriptedMarkerSpec(registryName);
            String rendererId = markerSpec == null ? "marker-billboard" : markerSpec.getRendererId();
            event.registerEntityRenderer(markerType, EffectRendererDispatcher.pickMarkerRenderer(rendererId));
        }

        for (String registryName : ModEntities.getScriptedBlockBodyRegistryNames()) {
            EntityType<ScriptedBlockBodyEntity> blockBodyType = ModEntities.getEntityType(registryName, ScriptedBlockBodyEntity.class);
            if (blockBodyType == null) {
                continue;
            }
            ScriptedBlockBodySpec spec = ModEntities.getScriptedBlockBodySpec(registryName);
            String rendererId = spec == null ? "block-body" : spec.getRendererId();
            if ("block-body".equals(rendererId)) {
                event.registerEntityRenderer(blockBodyType, ScriptedBlockBodyRenderer::new);
            }
        }

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.forge1201.client.init"));
        IFn fn = Clojure.var("cn.li.forge1201.client.init", "register-scripted-block-entity-renderers!");
        fn.invoke(event);
    }

    public static void registerItemDecorations(RegisterItemDecorationsEvent event) {
        // Content-specific item decorators are registered by descriptor-driven client hooks.
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(MyMod1201.MODID, "plasma_body"),
                ModRenderTypes.PLASMA_BODY_FORMAT),
            shader -> {
                plasmaBodyShader = shader;
                ModRenderTypes.setPlasmaBodyShader(shader);
            }
        );
        event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(MyMod1201.MODID, "msdf_text"),
                MsdfRenderTypes.MSDF_TEXT_FORMAT),
            MsdfRenderTypes::setMsdfShader
        );
    }

    public static ShaderInstance getPlasmaBodyShader() {
        return plasmaBodyShader;
    }
}
