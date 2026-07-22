package cn.li.mc1201.runtime;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Runtime registration into BuiltInRegistries without Clojure reflection.
 */
public final class RegistryDispatchShared {
    private RegistryDispatchShared() {
    }

    public static Block registerBlock(String namespace, String path, Block instance) {
        ResourceLocation id = new ResourceLocation(namespace, path);
        return Registry.register(BuiltInRegistries.BLOCK, id, instance);
    }

    public static Item registerItem(String namespace, String path, Item instance) {
        ResourceLocation id = new ResourceLocation(namespace, path);
        return Registry.register(BuiltInRegistries.ITEM, id, instance);
    }
}
