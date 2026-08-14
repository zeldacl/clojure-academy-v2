package cn.li.presentation.core;

public record FrameContext(long frameId, float deltaSeconds, int width, int height) {
    public FrameContext {
        if (frameId < 0 || deltaSeconds < 0 || width < 0 || height < 0)
            throw new IllegalArgumentException("invalid frame context");
    }
}
