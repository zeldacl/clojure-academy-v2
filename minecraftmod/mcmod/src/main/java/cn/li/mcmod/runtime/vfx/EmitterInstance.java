package cn.li.mcmod.runtime.vfx;

public final class EmitterInstance {
    private final int emitterId;
    private final ParticleBuffer particles;
    private long age;
    private boolean enabled = true;
    public EmitterInstance(int emitterId, int capacity) { this.emitterId = emitterId; particles = new ParticleBuffer(capacity); }
    public int emitterId() { return emitterId; }
    public ParticleBuffer particles() { return particles; }
    public long age() { return age; }
    public void advance() { age++; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}