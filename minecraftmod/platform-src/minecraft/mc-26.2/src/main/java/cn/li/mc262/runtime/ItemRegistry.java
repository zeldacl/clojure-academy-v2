package cn.li.mc262.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

public final class ItemRegistry {
    private ItemRegistry() {}
    public static Item get(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }
}
