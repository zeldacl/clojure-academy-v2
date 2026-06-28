package cn.li.forge1201.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {

    @Invoker("innerBlit")
    void invokeInnerBlit(
            ResourceLocation texture,
            int x1, int x2,
            int y1, int y2,
            int z,
            float u0, float u1,
            float v0, float v1);
}
