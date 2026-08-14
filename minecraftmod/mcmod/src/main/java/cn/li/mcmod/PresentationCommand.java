package cn.li.mcmod.runtime;

import java.util.List;

/** Version-neutral render command data. Behaviour stays in Clojure backends. */
public record PresentationCommand(String kind, List<Object> values) {
    public PresentationCommand {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind");
        values = List.copyOf(values == null ? List.of() : values);
    }
}
