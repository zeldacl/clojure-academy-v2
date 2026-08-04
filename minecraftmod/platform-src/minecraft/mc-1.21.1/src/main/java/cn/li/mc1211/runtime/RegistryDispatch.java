package cn.li.mc1211.runtime;

import cn.li.mcver.ResourceLocations;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Runtime registration into BuiltInRegistries without Clojure reflection.
 */
public final class RegistryDispatch {
    private RegistryDispatch() {
    }

    public static Block registerBlock(String namespace, String path, Block instance) {
        ResourceLocation id = ResourceLocations.of(namespace, path);
        return Registry.register(BuiltInRegistries.BLOCK, id, instance);
    }

    public static Item registerItem(String namespace, String path, Item instance) {
        ResourceLocation id = ResourceLocations.of(namespace, path);
        return Registry.register(BuiltInRegistries.ITEM, id, instance);
    }

    public static Fluid registerFluid(String namespace, String path, Fluid instance) {
        ResourceLocation id = ResourceLocations.of(namespace, path);
        return Registry.register(BuiltInRegistries.FLUID, id, instance);
    }
}
