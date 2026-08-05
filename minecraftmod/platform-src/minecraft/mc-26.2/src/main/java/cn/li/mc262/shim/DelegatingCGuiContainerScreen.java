package cn.li.mc262.shim;

import cn.li.mc262.gui.CGuiContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class DelegatingCGuiContainerScreen<T extends AbstractContainerMenu> extends CGuiContainerScreen<T> {
    public DelegatingCGuiContainerScreen(T menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }
}
