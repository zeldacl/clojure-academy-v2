package cn.li.mc262.block;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface ScriptedRenderShapeResolver {
    RenderShape resolve(String blockId, BlockState state);
}
