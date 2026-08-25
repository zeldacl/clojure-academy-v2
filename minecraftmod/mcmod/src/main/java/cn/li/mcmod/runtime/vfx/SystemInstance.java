package cn.li.mcmod.runtime.vfx;

import java.util.Objects;
import java.util.UUID;

public final class SystemInstance {
    private final long instanceId;
    private final int assetId;
    private final UUID owner;
    private final long seed;
    private final long startServerTick;
    private long stateSequence;
    private long eventSequence;
    private long age;
    public SystemInstance(long instanceId, int assetId, UUID owner, long seed, long startServerTick, long stateSequence, long eventSequence) {
        this.instanceId = instanceId; this.assetId = assetId; this.owner = Objects.requireNonNull(owner, "owner");
        this.seed = seed; this.startServerTick = startServerTick; this.stateSequence = stateSequence; this.eventSequence = eventSequence;
    }
    public long instanceId() { return instanceId; }
    public int assetId() { return assetId; }
    public UUID owner() { return owner; }
    public long seed() { return seed; }
    public long startServerTick() { return startServerTick; }
    public long stateSequence() { return stateSequence; }
    public long eventSequence() { return eventSequence; }
    public long age() { return age; }
    public void advance() { age++; }
    public boolean acceptsState(long sequence) { if (sequence <= stateSequence) return false; stateSequence = sequence; return true; }
    public boolean acceptsEvent(long sequence) { if (sequence <= eventSequence) return false; eventSequence = sequence; return true; }
}