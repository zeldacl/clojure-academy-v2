package cn.li.forge1201.event;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Var;
import cn.li.forge1201.MyMod1201;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeEventBusManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("my_mod");
    private static final Set<String> REMOVED_CONVERTERS = Set.of(
        "energy_converter",
        "energy_converter_advanced",
        "energy_converter_elite"
    );

    private ForgeEventBusManager() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("cn.li.forge1201.commands"));

            Var handler = (Var) Clojure.var("cn.li.forge1201.commands", "register-all-commands");
            if (!handler.isBound()) {
                throw new IllegalStateException("register-all-commands is unbound after require");
            }

            handler.invoke(event.getDispatcher(), event.getBuildContext());
        } catch (Throwable t) {
            LOGGER.error("[ForgeEventBusManager] Failed to register commands", t);
        }
    }

    @SubscribeEvent
    public static void onMissingMappings(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<Block> mapping : event.getMappings(ForgeRegistries.BLOCKS.getRegistryKey(), MyMod1201.MODID)) {
            if (isRemovedConverter(mapping.getKey())) {
                mapping.ignore();
                LOGGER.info("Ignored removed legacy block mapping: {}", mapping.getKey());
            }
        }

        for (MissingMappingsEvent.Mapping<Item> mapping : event.getMappings(ForgeRegistries.ITEMS.getRegistryKey(), MyMod1201.MODID)) {
            if (isRemovedConverter(mapping.getKey())) {
                mapping.ignore();
                LOGGER.info("Ignored removed legacy item mapping: {}", mapping.getKey());
            }
        }
    }

    private static boolean isRemovedConverter(ResourceLocation key) {
        return key != null
            && MyMod1201.MODID.equals(key.getNamespace())
            && REMOVED_CONVERTERS.contains(key.getPath());
    }
}
