package cn.li.mcmod.runtime;

import java.util.List;

/** Opaque frame envelope shared by AC and version backends. */
public record PresentationFrame(long frameId, List<PresentationPass> passes) {
    public PresentationFrame {
        if (frameId < 0) throw new IllegalArgumentException("negative frame id");
        passes = List.copyOf(passes == null ? List.of() : passes);
    }
}
