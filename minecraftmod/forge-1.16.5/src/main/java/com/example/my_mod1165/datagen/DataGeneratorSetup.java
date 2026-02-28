package com.example.my_mod1165.datagen;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import clojure.lang.RT;
import clojure.lang.Var;

/**
 * DataGenerator Event Handler for Forge 1.16.5
 * 
 * Minimal Java wrapper - only serves as annotation container.
 * All business logic is implemented in Clojure (my-mod.forge1165.datagen.event-handler).
 * 
 * This allows us to keep DataGenerator logic in Clojure for consistency,
 * while using Java only for Forge-required annotations.
 */
@Mod.EventBusSubscriber(modid = "my_mod", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class DataGeneratorSetup {
    
    @SubscribeEvent
    public static void onGatherData(GatherDataEvent event) {
        // Delegate to Clojure implementation via Clojure runtime
        try {
            Var var = RT.var("my-mod.forge1165.datagen.event-handler", "static-gather-data");
            var.invoke(event);
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator handler: " + e);
            e.printStackTrace();
        }
    }
}

