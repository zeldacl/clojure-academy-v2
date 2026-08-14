package cn.li.presentation.core;

import java.util.Objects;

public record HostDescriptor(String id, HostKind kind, int width, int height,
                             SafeInsets safeInsets, InputPolicy inputPolicy) {
    public HostDescriptor {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        safeInsets = safeInsets == null ? SafeInsets.ZERO : safeInsets;
        inputPolicy = inputPolicy == null ? InputPolicy.PASSTHROUGH : inputPolicy;
        if (width < 0 || height < 0) throw new IllegalArgumentException("negative host dimensions");
    }

    public enum HostKind { HUD, WORLD_UI, VFX, FIRST_PERSON, CAMERA, POST_PROCESS, SCREEN }
    public enum InputPolicy { PASSTHROUGH, CAPTURE }
    public record SafeInsets(int left, int top, int right, int bottom) {
        public static final SafeInsets ZERO = new SafeInsets(0, 0, 0, 0);
        public SafeInsets { if (left < 0 || top < 0 || right < 0 || bottom < 0) throw new IllegalArgumentException("negative inset"); }
    }
}
