package cn.li.forge1201.datagen;

import cn.li.mc1201.clj.ClojureInterop;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * DataGenerator Event Handler for Forge 1.20.1
 * 
 * Minimal Java wrapper - only serves as annotation container.
 * All business logic is implemented in Clojure (cn.li.forge1201.datagen.event-handler).
 * 
 * This allows us to keep DataGenerator logic in Clojure for consistency,
 * while using Java only for Forge-required annotations.
 */
@Mod.EventBusSubscriber(modid = "my_mod", bus = Mod.EventBusSubscriber.Bus.MOD)
public class DataGeneratorSetup {
    
    @SubscribeEvent
    public static void onGatherData(GatherDataEvent event) {
        // Delegate to Clojure implementation via Clojure runtime
        try {
            System.out.println("[my_mod] DataGeneratorSetup.onGatherData invoked");
            String ns = "cn.li.forge1201.datagen.event-handler";
            ClojureInterop.requireNamespace(ns);
            ClojureInterop.invoke(ns, "static-gather-data", event);
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator handler: " + e);
            e.printStackTrace();
        }
    }
}

