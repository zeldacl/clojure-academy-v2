package cn.li.forge1201.client;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.client.render.ForgeClientRenderRegistry;
import cn.li.forge1201.client.render.MatterUnitItemDecorator;
import cn.li.forge1201.entity.ModEntities;
import cn.li.mc1201.entity.ScriptedBlockBodyEntity;
import cn.li.mc1201.entity.ScriptedEffectEntity;
import cn.li.mc1201.entity.ScriptedMarkerEntity;
import cn.li.mc1201.entity.ScriptedProjectileEntity;
import cn.li.mc1201.entity.ScriptedRayEntity;
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
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
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
        ForgeClientRenderRegistry.registerEntityAndBlockRenderers(event);
    }

    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        ForgeClientRenderRegistry.registerItemDecorations(event);
    }
}
