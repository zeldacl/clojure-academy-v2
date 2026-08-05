package cn.li.mc262.gui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class CGuiContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    public CGuiContainerScreen(T menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }
}
