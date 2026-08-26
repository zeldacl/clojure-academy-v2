package cn.li.presentation.core;

import java.util.Objects;

/** Stable UI resource identity; version backends resolve it to native handles. */
public record ResourceRef(String namespace, String path, Kind kind) {
    public ResourceRef {
        namespace = require(namespace, "namespace");
        path = require(path, "path");
        kind = Objects.requireNonNull(kind, "kind");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }

    public enum Kind { TEXTURE, FONT, MODEL, SOUND }
}