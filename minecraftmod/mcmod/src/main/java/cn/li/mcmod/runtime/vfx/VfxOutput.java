package cn.li.mcmod.runtime.vfx;
public record VfxOutput(VfxOutputKind kind, int value, float amount, String resourceId,
                        String instanceKey, boolean looping, double x, double y, double z) {
    public VfxOutput(VfxOutputKind kind, int value, float amount, String resourceId) {
        this(kind, value, amount, resourceId, null, false, 0.0, 0.0, 0.0);
    }

    public VfxOutput {
        if (kind == null || !Float.isFinite(amount)) throw new IllegalArgumentException("invalid VFX output");
    }
}
