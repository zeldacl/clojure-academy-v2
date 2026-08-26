package cn.li.mcmod.runtime.vfx;

/** Immutable render batch crossing the neutral VFX/loader boundary. */
public record VfxBatch(VfxRenderStage stage,
                       int materialId,
                       int primitiveId,
                       int instanceCount,
                       ParticleBuffer particles,
                       Object payload) {
    public VfxBatch {
        if (stage == null || instanceCount < 0) throw new IllegalArgumentException("invalid VFX batch");
        if (particles != null && instanceCount > particles.size()) throw new IllegalArgumentException("instanceCount");
    }
}
