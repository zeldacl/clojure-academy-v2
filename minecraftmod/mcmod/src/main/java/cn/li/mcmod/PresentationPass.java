package cn.li.mcmod.runtime;

import java.util.List;

/** Immutable stage bucket passed to a version-owned renderer. */
public record PresentationPass(String stage, List<PresentationCommand> commands) {
    public PresentationPass {
        if (stage == null || stage.isBlank()) throw new IllegalArgumentException("stage");
        commands = List.copyOf(commands == null ? List.of() : commands);
    }
}
