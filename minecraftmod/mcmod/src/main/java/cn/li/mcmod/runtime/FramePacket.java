package cn.li.mcmod.runtime;

import cn.li.mcmod.runtime.ui.UiDrawList;

import java.util.List;

/**
 * Immutable render hand-off. No Clojure object or mutable node escapes into
 * rendering.
 *
 * uiByStage is indexed by RenderStage.ordinal(), null where that stage has
 * no UI content this frame — a stage submission becomes a single array
 * index instead of a linear scan/filter over passes, and a stage nobody
 * submits never has commands built for it in the first place (see the
 * refactor plan's Phase 5 for where this array gets populated: one
 * assembly per real frame-id, not once per stage submission).
 */
public record FramePacket(long frameId, UiDrawList[] uiByStage, List<RenderPass> passes) {
    public FramePacket {
        if (frameId < 0) throw new IllegalArgumentException("negative frame id");
        if (uiByStage == null) uiByStage = new UiDrawList[RenderStage.values().length];
        passes = List.copyOf(passes == null ? List.of() : passes);
    }

    public static FramePacket empty(long frameId) {
        return new FramePacket(frameId, null, List.of());
    }

    public UiDrawList uiFor(RenderStage stage) {
        return uiByStage[stage.ordinal()];
    }
}
