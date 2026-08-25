package cn.li.mcmod.runtime.vfx;
public sealed interface VfxPacket permits VfxCatalogHello, VfxCatalogAck, VfxLifecyclePacket { VfxPacketKind kind(); }