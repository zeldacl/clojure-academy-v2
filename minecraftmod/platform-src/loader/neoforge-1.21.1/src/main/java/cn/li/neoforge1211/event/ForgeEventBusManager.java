package cn.li.neoforge1211.event;

import cn.li.mcmod.ModId;
import cn.li.neoforge1211.MyMod1211;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = MyMod1211.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ForgeEventBusManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModId.ID);

    private ForgeEventBusManager() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeCommandRegistrar.registerAll(event, LOGGER);
    }
}
