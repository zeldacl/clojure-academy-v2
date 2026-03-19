package cn.li.forge1201.platform;

import my_mod.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class Forge1201Platform implements IPlatform {
    private final IBlockHelper blockHelper = new Forge1201BlockHelper(); // 你需要另行实现这个
    private final IItemHelper itemHelper = new Forge1201ItemHelper();

    @Override public IBlockHelper block() { return blockHelper; }
    @Override public IItemHelper item() { return itemHelper; }

    @Override
    public boolean isClientSide(Object level) {
        return (level instanceof Level l) && l.isClientSide;
    }

    @Override
    public void sendMessage(Object player, String message) {
        if (player instanceof Player p) {
            // 1.20.1 使用 Component.literal
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(message), false);
        }
    }
}