package cn.li.forge1201;

import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles removed legacy registry IDs from old saves.
 * We intentionally ignore these mappings so Forge won't treat them as unhandled missing entries.
 */
@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LegacyMappingHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("my_mod");
    private static final Set<String> REMOVED_CONVERTERS = Set.of(
        "energy_converter",
        "energy_converter_advanced",
        "energy_converter_elite"
    );

    private LegacyMappingHandler() {
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
