package cn.li.forge1201.client;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.client.effect.IntensifyEffectRenderer;
import cn.li.forge1201.client.render.MatterUnitItemDecorator;
import cn.li.forge1201.entity.ModEntities;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.forge1201.entity.ScriptedProjectileEntity;
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

        EntityType<ScriptedProjectileEntity> magHook =
                ModEntities.getEntityType("entity_mag_hook", ScriptedProjectileEntity.class);
        if (magHook != null) {
            event.registerEntityRenderer(magHook, ThrownItemRenderer::new);
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
