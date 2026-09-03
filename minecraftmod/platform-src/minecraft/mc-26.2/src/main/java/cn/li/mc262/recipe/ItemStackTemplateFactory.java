package cn.li.mc262.recipe;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;

/** Directly typed bridge for Clojure datagen; keeps constructor resolution out of hot/runtime code. */
public final class ItemStackTemplateFactory {
    private ItemStackTemplateFactory() {}

    public static ItemStackTemplate create(Holder<Item> item, int count) {
        return new ItemStackTemplate(item, count, DataComponentPatch.EMPTY);
    }
}
