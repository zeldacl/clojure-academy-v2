package cn.li.mcmod.runtime.vfx;

/** Immutable render batch crossing the neutral VFX/loader boundary.
 *
 *  primitive is a stable string ("line"/"quad"/"particle"), not an int
 *  code: every loader's presentation_world_renderer.clj gates on
 *  #{"line" "quad" "particle"} directly, and an int primitiveId re-encoded
 *  via (str ...) on the producing side (ability-runtime's compose.clj)
 *  never matched that set -- no VFX draw batch reached a renderer through
 *  this record. Fixed by making the producer and every consumer agree on
 *  the same string vocabulary instead of a numeric encoding neither side's
 *  gate actually decoded. */
public record VfxBatch(VfxRenderStage stage,
                       int materialId,
                       String primitive,
                       int instanceCount,
                       ParticleBuffer particles,
                       Object payload) {
    public VfxBatch {
        if (stage == null || instanceCount < 0) throw new IllegalArgumentException("invalid VFX batch");
        if (particles != null && instanceCount > particles.size()) throw new IllegalArgumentException("instanceCount");
    }
}
