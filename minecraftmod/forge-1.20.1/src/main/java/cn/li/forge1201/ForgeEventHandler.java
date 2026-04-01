package cn.li.forge1201;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge event handler for block interactions.
 * Properly decorated with @SubscribeEvent to be picked up by Forge EVENT_BUS.
 */
@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("my_mod");
    
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            LOGGER.info("[ForgeEventHandler] onRightClickBlock called, event: {}", event);
            IFn handler = Clojure.var("cn.li.forge1201.events", "handle-right-click-event");
            LOGGER.info("[ForgeEventHandler] Handler resolved: {}", handler);
            handler.invoke(event);
            LOGGER.info("[ForgeEventHandler] Handler invoked successfully");
        } catch (Exception e) {
            LOGGER.error("[ForgeEventHandler] Failed to handle right-click event:", e);
        } catch (Throwable t) {
            LOGGER.error("[ForgeEventHandler] THROWABLE in right-click handler:", t);
        }
    }
}
