package cn.li.presentation.compiler;

import cn.li.presentation.core.TemplateId;

import java.util.List;
import java.util.Map;

public record CompiledTemplate(TemplateId id, String contentHash, int schemaVersion,
                               TemplateNode root, Map<String, Integer> bindings,
                               Map<String, Integer> actions) {
    public CompiledTemplate {
        bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        actions = Map.copyOf(actions == null ? Map.of() : actions);
    }
}
