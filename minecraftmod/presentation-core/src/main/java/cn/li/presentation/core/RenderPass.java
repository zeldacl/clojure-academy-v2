package cn.li.presentation.core;

import java.util.List;

public record RenderPass(RenderStage stage, List<RenderCommand> commands) {
    public RenderPass {
        if (stage == null) throw new NullPointerException("stage");
        commands = List.copyOf(commands == null ? List.of() : commands);
    }
}
