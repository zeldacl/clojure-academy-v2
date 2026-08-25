package cn.li.mcmod.runtime.vfx;
public record VfxBatch(VfxRenderStage stage, int materialId, int primitiveId, int instanceCount, ParticleBuffer particles) {
    public VfxBatch {
        if (stage == null || instanceCount < 0) throw new IllegalArgumentException("invalid VFX batch");
        if (particles != null && instanceCount > particles.size()) throw new IllegalArgumentException("instanceCount");
    }
}