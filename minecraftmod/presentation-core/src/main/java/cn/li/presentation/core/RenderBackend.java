package cn.li.presentation.core;

import cn.li.mcmod.runtime.FramePacket;
import cn.li.mcmod.runtime.RenderStage;

public interface RenderBackend {
    BackendCapabilities capabilities();
    void submit(FramePacket packet, RenderStage stage);
    void reloadResources(ResourceGeneration generation);
}
