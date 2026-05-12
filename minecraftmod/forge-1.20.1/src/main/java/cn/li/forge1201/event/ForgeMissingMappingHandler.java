package cn.li.forge1201.event;

import cn.li.forge1201.MyMod1201;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.slf4j.Logger;

import java.util.Set;

final class ForgeMissingMappingHandler {
    private static final Set<String> REMOVED_CONVERTERS = Set.of(
        "energy_converter",
        "energy_converter_advanced",
        "energy_converter_elite"
    );

    private ForgeMissingMappingHandler() {
    }

    static void ignoreLegacyConverters(MissingMappingsEvent event, Logger logger) {
        for (MissingMappingsEvent.Mapping<Block> mapping : event.getMappings(ForgeRegistries.BLOCKS.getRegistryKey(), MyMod1201.MODID)) {
            if (isRemovedConverter(mapping.getKey())) {
                mapping.ignore();
                logger.info("Ignored removed legacy block mapping: {}", mapping.getKey());
            }
        }

        for (MissingMappingsEvent.Mapping<Item> mapping : event.getMappings(ForgeRegistries.ITEMS.getRegistryKey(), MyMod1201.MODID)) {
            if (isRemovedConverter(mapping.getKey())) {
                mapping.ignore();
                logger.info("Ignored removed legacy item mapping: {}", mapping.getKey());
            }
        }
    }

    private static boolean isRemovedConverter(ResourceLocation key) {
        return key != null
            && MyMod1201.MODID.equals(key.getNamespace())
            && REMOVED_CONVERTERS.contains(key.getPath());
    }
}
