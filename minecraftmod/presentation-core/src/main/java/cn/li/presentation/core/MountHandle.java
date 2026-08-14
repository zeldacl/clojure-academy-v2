package cn.li.presentation.core;

public record MountHandle(long value) {
    public MountHandle {
        if (value <= 0) throw new IllegalArgumentException("mount handle must be positive");
    }
}
