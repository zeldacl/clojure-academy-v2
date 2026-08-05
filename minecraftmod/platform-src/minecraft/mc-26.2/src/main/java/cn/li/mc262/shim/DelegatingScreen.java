package cn.li.mc262.shim;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class DelegatingScreen extends Screen {
    public DelegatingScreen(Component title) { super(title); }
}
