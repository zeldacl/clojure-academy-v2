package cn.li.presentation.core;

import java.util.Objects;

public record TemplateId(String value) {
    public TemplateId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("template id must not be empty");
    }
}
