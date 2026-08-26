package cn.li.mcmod.runtime;

import java.util.Objects;

/** Neutral resource identity used by UI Render IR; resolved by each MC backend. */
public record UiResourceRef(String namespace, String path, Kind kind) {
    public UiResourceRef {
        namespace = require(namespace, "namespace");
        path = require(path, "path");
        kind = Objects.requireNonNull(kind, "kind");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }

    public enum Kind { TEXTURE, FONT, MODEL }
}