package cn.li.mcmod.runtime.vfx;
public record VfxOutput(VfxOutputKind kind, int value, float amount, String resourceId) {
    public VfxOutput {
        if (kind == null || !Float.isFinite(amount)) throw new IllegalArgumentException("invalid VFX output");
    }
}