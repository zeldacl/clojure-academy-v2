package cn.li.forge1201.client;

import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.client.render.ForgeClientRenderRegistry;
import cn.li.mc1201.clj.ClojureInterop;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.event.AddPackFindersEvent;
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

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        ClojureInterop.requireNamespace("cn.li.mc1201.client.font.font-pack-setup");
        ClojureInterop.invoke(
            "cn.li.mc1201.client.font.font-pack-setup",
            "on-add-pack-finders!",
            (java.util.function.Consumer<RepositorySource>) event::addRepositorySource);
    }
}
