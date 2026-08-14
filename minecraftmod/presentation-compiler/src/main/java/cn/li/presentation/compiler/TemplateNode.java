package cn.li.presentation.compiler;

import cn.li.presentation.core.ActionId;
import cn.li.presentation.core.TemplateId;

import java.util.List;
import java.util.Map;

public record TemplateNode(String type, String key, List<TemplateNode> children,
                           Map<String, Integer> bindings,
                           Map<String, ActionId> actions,
                           Map<String, Object> props) {
    public TemplateNode {
        type = type == null ? "" : type;
        key = key == null ? "" : key;
        children = List.copyOf(children == null ? List.of() : children);
        bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        actions = Map.copyOf(actions == null ? Map.of() : actions);
        props = Map.copyOf(props == null ? Map.of() : props);
    }
}
