package cn.li.presentation.core;

import java.util.List;

/** Immutable render hand-off. No Clojure object or mutable node escapes into rendering. */
public record FramePacket(long frameId, List<RenderPass> passes) {
    public FramePacket {
        if (frameId < 0) throw new IllegalArgumentException("negative frame id");
        passes = List.copyOf(passes == null ? List.of() : passes);
    }
    public static FramePacket empty(long frameId) { return new FramePacket(frameId, List.of()); }
}
