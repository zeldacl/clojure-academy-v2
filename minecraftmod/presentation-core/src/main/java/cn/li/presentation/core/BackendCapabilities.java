package cn.li.presentation.core;

public record BackendCapabilities(boolean instancing, boolean streamingBuffers,
                                  boolean uniformBuffers, boolean postProcess,
                                  int maxTextureUnits) {
    public BackendCapabilities {
        if (maxTextureUnits < 0) throw new IllegalArgumentException("negative texture unit count");
    }
    public static BackendCapabilities conservative() {
        return new BackendCapabilities(false, false, false, false, 8);
    }
}
