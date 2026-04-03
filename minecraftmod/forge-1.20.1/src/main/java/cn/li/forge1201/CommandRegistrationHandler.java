package cn.li.forge1201;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge event handler for command registration.
 * Properly decorated with @SubscribeEvent to be picked up by Forge EVENT_BUS.
 */
@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CommandRegistrationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("my_mod");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            LOGGER.info("[CommandRegistrationHandler] RegisterCommandsEvent called");
            IFn handler = Clojure.var("cn.li.forge1201.commands", "register-all-commands");
            LOGGER.info("[CommandRegistrationHandler] Handler resolved: {}", handler);
            handler.invoke(event.getDispatcher(), event.getBuildContext());
            LOGGER.info("[CommandRegistrationHandler] Commands registered successfully");
        } catch (Exception e) {
            LOGGER.error("[CommandRegistrationHandler] Failed to register commands:", e);
        } catch (Throwable t) {
            LOGGER.error("[CommandRegistrationHandler] THROWABLE in command registration:", t);
        }
    }
}
