package cn.li.presentation.core;

public interface RenderBackend {
    BackendCapabilities capabilities();
    void submit(FramePacket packet, RenderStage stage);
    void reloadResources(ResourceGeneration generation);
}
