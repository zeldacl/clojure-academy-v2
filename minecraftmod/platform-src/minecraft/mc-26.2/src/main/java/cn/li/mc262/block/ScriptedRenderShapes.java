package cn.li.mc262.block;

import net.minecraft.world.level.block.RenderShape;

public final class ScriptedRenderShapes {
    private ScriptedRenderShapes() {}
    public static RenderShape entityAnimated() { return RenderShape.MODEL; }
    public static RenderShape model() { return RenderShape.MODEL; }
    public static RenderShape invisible() { return RenderShape.INVISIBLE; }
}
