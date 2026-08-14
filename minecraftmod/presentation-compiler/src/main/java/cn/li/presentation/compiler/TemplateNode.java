package cn.li.presentation.compiler;

import cn.li.presentation.core.ActionId;
import cn.li.presentation.core.TemplateId;

import java.util.List;
import java.util.Map;

public record TemplateNode(String type, String key, List<TemplateNode> children,
                           Integer bindingId, ActionId action,
                           Map<String, Object> props) {
    public TemplateNode(String type, String key, List<TemplateNode> children,
                        Integer bindingId, ActionId action) {
        this(type, key, children, bindingId, action, Map.of());
    }

    public TemplateNode {
        type = type == null ? "" : type;
        key = key == null ? "" : key;
        children = List.copyOf(children == null ? List.of() : children);
        props = Map.copyOf(props == null ? Map.of() : props);
    }
}
