package cn.li.forge1201.client;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.client.effect.BloodSplashRenderer;
import cn.li.forge1201.client.effect.DiamondShieldRenderer;
import cn.li.forge1201.client.effect.IntensifyEffectRenderer;
import cn.li.forge1201.client.effect.MdShieldRenderer;
import cn.li.forge1201.client.effect.MdBallRenderer;
import cn.li.forge1201.client.effect.GenericArcRenderer;
import cn.li.forge1201.client.effect.RippleMarkRenderer;
import cn.li.forge1201.client.effect.ScriptedBlockBodyRenderer;
import cn.li.forge1201.client.effect.ScriptedEffectBillboardRenderer;
import cn.li.forge1201.client.effect.ScriptedMarkerBillboardRenderer;
import cn.li.forge1201.client.effect.ScriptedRayCompositeRenderer;
import cn.li.forge1201.client.effect.SurroundArcRenderer;
import cn.li.forge1201.client.effect.TpMarkingRenderer;
import cn.li.forge1201.client.effect.WireMarkerRenderer;
import cn.li.forge1201.client.render.MatterUnitItemDecorator;
import cn.li.forge1201.entity.ScriptedBlockBodyEntity;
import cn.li.forge1201.entity.ScriptedBlockBodySpec;
import cn.li.forge1201.entity.ModEntities;
import cn.li.forge1201.entity.ScriptedMarkerEntity;
import cn.li.forge1201.entity.ScriptedMarkerSpec;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.forge1201.entity.ScriptedProjectileEntity;
import cn.li.forge1201.entity.ScriptedRayEntity;
import cn.li.forge1201.entity.ScriptedRaySpec;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge discovers {@code @Mod.EventBusSubscriber} and invokes BER registration on the
 * mod bus. Clojure {@code IEventBus.addListener(Class, Consumer)} for inner event types
 * can fail to match generics, so the handler never ran (no scripted BER in-game).
 */
@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModClientRenderSetup {

    private ModClientRenderSetup() {
    }

    @SubscribeEvent
    public static void onRegisterBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        EntityType<ScriptedEffectEntity> intensify = ModEntities.getEntityType("intensify_effect", ScriptedEffectEntity.class);
        if (intensify != null) {
            event.registerEntityRenderer(intensify, IntensifyEffectRenderer::new);
        }

        for (String registryName : ModEntities.getScriptedEffectRegistryNames()) {
            if ("intensify_effect".equals(registryName)) {
                continue;
            }
            EntityType<ScriptedEffectEntity> effectType = ModEntities.getEntityType(registryName, ScriptedEffectEntity.class);
            if (effectType == null) {
                continue;
            }
            String rendererId = "effect-billboard";
            if (ModEntities.getScriptedEffectSpec(registryName) != null) {
                rendererId = ModEntities.getScriptedEffectSpec(registryName).getRendererId();
            }
            if ("effect-billboard".equals(rendererId)) {
                event.registerEntityRenderer(effectType, ScriptedEffectBillboardRenderer::new);
            } else if ("diamond-shield".equals(rendererId)) {
                event.registerEntityRenderer(effectType, DiamondShieldRenderer::new);
            } else if ("md-shield".equals(rendererId)) {
                event.registerEntityRenderer(effectType, MdShieldRenderer::new);
            } else if ("surround-arc".equals(rendererId)) {
                event.registerEntityRenderer(effectType, SurroundArcRenderer::new);
            } else if ("arc-generic".equals(rendererId)) {
                event.registerEntityRenderer(effectType, GenericArcRenderer::new);
            } else if ("ripple-mark".equals(rendererId)) {
                event.registerEntityRenderer(effectType, RippleMarkRenderer::new);
            } else if ("blood-splash".equals(rendererId)) {
                event.registerEntityRenderer(effectType, BloodSplashRenderer::new);
            } else if ("md-ball".equals(rendererId)) {
                event.registerEntityRenderer(effectType, MdBallRenderer::new);
            }
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
            if ("ray-composite".equals(rendererId)) {
                event.registerEntityRenderer(rayType, ScriptedRayCompositeRenderer::new);
            }
        }

        for (String registryName : ModEntities.getScriptedMarkerRegistryNames()) {
            EntityType<ScriptedMarkerEntity> markerType = ModEntities.getEntityType(registryName, ScriptedMarkerEntity.class);
            if (markerType == null) {
                continue;
            }
            ScriptedMarkerSpec markerSpec = ModEntities.getScriptedMarkerSpec(registryName);
            String rendererId = markerSpec == null ? "marker-billboard" : markerSpec.getRendererId();
            if ("marker-billboard".equals(rendererId)) {
                event.registerEntityRenderer(markerType, ScriptedMarkerBillboardRenderer::new);
            } else if ("tp-marking".equals(rendererId)) {
                event.registerEntityRenderer(markerType, TpMarkingRenderer::new);
            } else if ("wire-marker".equals(rendererId)) {
                event.registerEntityRenderer(markerType, WireMarkerRenderer::new);
            }
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

        // RegisterRenderers can run before FMLClientSetup / resolve-client-fn loads this NS.
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.forge1201.client.init"));
        IFn fn = Clojure.var("cn.li.forge1201.client.init", "register-scripted-block-entity-renderers!");
        fn.invoke(event);
    }

    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        Item matterUnit = BuiltInRegistries.ITEM.get(new ResourceLocation(MyMod1201.MODID, "matter_unit"));
        if (matterUnit != null) {
            event.register(matterUnit, MatterUnitItemDecorator.INSTANCE);
        }
    }
}
