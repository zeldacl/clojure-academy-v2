package cn.li.forge1201.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ScriptedItem extends Item {
    private final int enchantability;
    private final List<String> tooltipLines;

    public ScriptedItem(Properties properties, int enchantability, List<String> tooltipLines) {
        super(properties);
        this.enchantability = Math.max(0, enchantability);
        this.tooltipLines = tooltipLines == null ? List.of() : List.copyOf(tooltipLines);
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return enchantability;
    }

    @Override
    public void appendHoverText(ItemStack stack,
                                @Nullable Level level,
                                List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        for (String line : tooltipLines) {
            if (line != null && !line.isEmpty()) {
                tooltip.add(Component.literal(line));
            }
        }
    }
}
