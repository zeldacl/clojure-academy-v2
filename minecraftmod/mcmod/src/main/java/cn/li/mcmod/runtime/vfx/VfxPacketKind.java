package cn.li.mcmod.runtime.vfx;

/** Stable wire tags; enum declaration order is not part of the protocol. */
public enum VfxPacketKind {
    CATALOG_HELLO(1), CATALOG_ACK(2), SPAWN(3), DELTA(4), EVENT(5),
    DESTROY(6), RELEASE(7), SNAPSHOT(8), OWNER_RESET(9);

    private final int wireId;
    VfxPacketKind(int wireId) { this.wireId = wireId; }
    public int wireId() { return wireId; }

    public static VfxPacketKind fromWireId(int wireId) {
        for (VfxPacketKind kind : values()) if (kind.wireId == wireId) return kind;
        throw new IllegalArgumentException("unknown VFX packet wire id " + wireId);
    }
}
