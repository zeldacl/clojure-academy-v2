package cn.li.mc1201.item;

import clojure.lang.IFn;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ScriptedItem extends Item {
    private final int enchantability;
    private final List<String> tooltipLines;
    @Nullable
    private final IFn onUseCallback;

    /** Backward-compatible: items without a DSL right-click callback. */
    public ScriptedItem(Properties properties, int enchantability, List<String> tooltipLines) {
        this(properties, enchantability, tooltipLines, null);
    }

    /** Primary constructor: callback is a per-item closure built in item_properties.clj
     *  that encapsulates the DSL :on-use and :on-right-click handlers. */
    public ScriptedItem(Properties properties, int enchantability,
                        List<String> tooltipLines, @Nullable IFn onUseCallback) {
        super(properties);
        this.enchantability = Math.max(0, enchantability);
        this.tooltipLines = tooltipLines == null ? List.of() : List.copyOf(tooltipLines);
        this.onUseCallback = onUseCallback;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                   InteractionHand hand) {
        if (this.onUseCallback != null) {
            Object result = this.onUseCallback.invoke(level, player, hand);
            if (result instanceof InteractionResultHolder<?> holder) {
                @SuppressWarnings("unchecked")
                InteractionResultHolder<ItemStack> typed =
                    (InteractionResultHolder<ItemStack>) holder;
                return typed;
            }
        }
        // No callback or callback returned non-standard — fall through to vanilla.
        // MUST call super.use(), not return a hardcoded value, to preserve
        // vanilla item lifecycles (food eating, bow charging, etc.).
        return super.use(level, player, hand);
    }

    @Override
    public int getEnchantmentValue() {
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
