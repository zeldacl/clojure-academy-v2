package cn.li.mc1201.block;

import net.minecraft.world.level.block.RenderShape;

/**
 * Shared scripted-block render-shape fallback policy.
 * Content-specific render shape metadata must be supplied by content descriptors.
 */
public final class ScriptedRenderShapes {

    private ScriptedRenderShapes() {
    }

    public static RenderShape resolveDefault(String blockId) {
        return RenderShape.MODEL;
    }
}
