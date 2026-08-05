package cn.li.mc262.shim;

import cn.li.mc262.gui.CMenuBridge;
import net.minecraft.world.inventory.MenuType;

public class DelegatingCMenuBridge extends CMenuBridge {
    public DelegatingCMenuBridge(MenuType<?> type, int containerId) {
        super(type, containerId);
    }
}
