package cn.li.mc1201.block;

import net.minecraft.world.level.block.RenderShape;

import java.util.Locale;
import java.util.Set;

/**
 * Shared scripted-block render-shape policy.
 */
public final class ScriptedRenderShapes {

    private static final Set<String> BER_ONLY_BLOCK_IDS = Set.of(
        "phase-gen",
        "cat-engine",
        "solar-gen",
        "wind-gen-base",
        "wind-gen-base-part",
        "wind-gen-main",
        "wind-gen-main-part",
        "wind-gen-pillar",
        "wireless-matrix",
        "wireless-matrix-part",
        "developer-normal",
        "developer-normal-part",
        "developer-advanced",
        "developer-advanced-part"
    );

    private ScriptedRenderShapes() {
    }

    public static RenderShape resolveDefault(String blockId) {
        String normalizedId = blockId == null
            ? ""
            : blockId.toLowerCase(Locale.ROOT).replace('_', '-');
        if (BER_ONLY_BLOCK_IDS.contains(normalizedId)) {
            return RenderShape.ENTITYBLOCK_ANIMATED;
        }
        return RenderShape.MODEL;
    }
}
