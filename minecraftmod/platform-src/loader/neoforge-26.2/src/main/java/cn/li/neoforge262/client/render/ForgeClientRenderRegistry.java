package cn.li.neoforge262.client.render;

import cn.li.mcbase.client.render.RenderProfileBootstrap;
import cn.li.mc262.entity.ScriptedBlockBodyEntity;
import cn.li.mc262.entity.ScriptedProjectileEntity;
import cn.li.mc262.client.render.effect.ScriptedBlockBodyRenderer;
import cn.li.mcbase.clj.ClojureInterop;
import cn.li.mcbase.entity.spec.ScriptedBlockBodySpec;
import cn.li.neoforge262.entity.ModEntities;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Client entity and block-entity renderer registration for NeoForge 26.2. */
public final class ForgeClientRenderRegistry {
    private static final Logger LOGGER = LogManager.getLogger();

    private ForgeClientRenderRegistry() {
    }

    public static void registerEntityAndBlockRenderers(EntityRenderersEvent.RegisterRenderers event) {
        RenderProfileBootstrap.runContentClientInitHooks();

        EntityType<ScriptedProjectileEntity> magHook =
                ModEntities.getEntityType("entity_mag_hook", ScriptedProjectileEntity.class);
        if (magHook != null) {
            event.registerEntityRenderer(magHook, ThrownItemRenderer::new);
        }

        for (String registryName : ModEntities.getScriptedBlockBodyRegistryNames()) {
            EntityType<ScriptedBlockBodyEntity> blockBodyType =
                    ModEntities.getEntityType(registryName, ScriptedBlockBodyEntity.class);
            if (blockBodyType == null) {
                continue;
            }
            event.registerEntityRenderer(blockBodyType, ScriptedBlockBodyRenderer::new);
        }

        try {
            ClojureInterop.requireNamespace("cn.li.neoforge262.client.init");
            IFn fn = Clojure.var("cn.li.neoforge262.client.init", "register-scripted-block-entity-renderers!");
            fn.invoke(event);
        } catch (Throwable t) {
            LOGGER.warn("Scripted BER registration skipped: {}", t.toString());
        }
    }

}
