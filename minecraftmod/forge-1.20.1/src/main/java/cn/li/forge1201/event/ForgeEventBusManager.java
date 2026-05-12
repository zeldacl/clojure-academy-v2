package cn.li.forge1201.event;

import cn.li.forge1201.MyMod1201;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeEventBusManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("my_mod");

    private ForgeEventBusManager() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ForgeCommandRegistrar.registerAll(event, LOGGER);
    }

    @SubscribeEvent
    public static void onMissingMappings(MissingMappingsEvent event) {
        ForgeMissingMappingHandler.ignoreLegacyConverters(event, LOGGER);
    }
}
