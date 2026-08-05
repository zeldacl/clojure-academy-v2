package cn.li.neoforge262.client;

import cn.li.mcbase.clj.ClojureInterop;
import cn.li.mc262.client.render.item.EnergyItemPropertyFunction;
import cn.li.mcver.ResourceLocations;
import cn.li.neoforge262.client.render.ForgeClientRenderRegistry;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

/**
 * Client mod-bus render/GUI registration for NeoForge 26.2.
 *
 * EventBusSubscriber.Bus was removed — register explicitly on the mod bus
 * (same pattern as ForgeModBusListener).
 *
 * OBJ items: do <em>not</em> mirror 1.21.1 {@code ModelEvent} composite baking.
 * NeoForge 26.x ships a built-in {@code neoforge:obj} loader; GUI-vs-hand
 * selection is expressed in item definitions via {@code minecraft:select} +
 * {@code minecraft:display_context} (see item-model-provider datagen).
 */
public final class ModClientRenderSetup {

    private ModClientRenderSetup() {
    }

    public static void register(IEventBus modBus) {
        if (modBus == null) {
            return;
        }
        modBus.addListener(ModClientRenderSetup::onRegisterMenuScreens);
        modBus.addListener(ModClientRenderSetup::onRegisterRenderers);
        modBus.addListener(ModClientRenderSetup::onRegisterFluidModels);
        modBus.addListener(ModClientRenderSetup::onRegisterRangeProperties);
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        ClojureInterop.requireNamespace("cn.li.neoforge262.gui.screen-impl");
        ClojureInterop.invoke(
            "cn.li.neoforge262.gui.screen-impl",
            "register-screens-on-event!",
            event);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        ForgeClientRenderRegistry.registerEntityAndBlockRenderers(event);
    }

    private static void onRegisterFluidModels(RegisterFluidModelsEvent event) {
        ClojureInterop.requireNamespace("cn.li.neoforge262.client.init");
        ClojureInterop.invoke("cn.li.neoforge262.client.init", "register-fluid-render-layers!", event);
    }

    public static void registerFluidModel(
            RegisterFluidModelsEvent event,
            FluidModel.Unbaked model,
            Fluid source,
            Fluid flowing) {
        event.register(model, source, flowing);
    }

    private static void onRegisterRangeProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(ResourceLocations.of("academy", "energy"), EnergyItemPropertyFunction.CODEC);
    }

}
