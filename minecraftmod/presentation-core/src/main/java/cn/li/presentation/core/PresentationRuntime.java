package cn.li.presentation.core;

import cn.li.mcmod.runtime.FramePacket;

public interface PresentationRuntime {
    MountHandle mount(HostDescriptor host, TemplateId template, PresentationViewModel model);
    void transact(Runnable mutation);
    void dispatch(MountHandle mount, PresentationInputEvent event);
    FramePacket extract(FrameContext context);
    void unmount(MountHandle mount);
}
