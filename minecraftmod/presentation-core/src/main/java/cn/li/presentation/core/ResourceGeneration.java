package cn.li.presentation.core;

public record ResourceGeneration(long value) {
    public ResourceGeneration { if (value < 0) throw new IllegalArgumentException("negative resource generation"); }
}
