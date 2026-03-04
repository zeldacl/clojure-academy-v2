package my_mod.datagen;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * DataGenerator Event Handler for Forge 1.20.1
 * 
 * Minimal Java wrapper - only serves as annotation container.
 * All business logic is implemented in Clojure (my-mod.forge1201.datagen.event-handler).
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
            Var require = RT.var("clojure.core", "require");
            require.invoke(Symbol.intern("my-mod.forge1201.datagen.event-handler"));
            Var var = RT.var("my-mod.forge1201.datagen.event-handler", "static-gather-data");
            var.invoke(event);
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator handler: " + e);
            e.printStackTrace();
        }
    }
}

