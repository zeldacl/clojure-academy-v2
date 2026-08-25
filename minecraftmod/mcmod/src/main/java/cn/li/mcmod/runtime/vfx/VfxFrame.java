package cn.li.mcmod.runtime.vfx;
import java.util.List;
public record VfxFrame(long frameId, long resourceGeneration, List<VfxBatch> batches, List<VfxOutput> outputs) {
    public VfxFrame {
        batches = List.copyOf(batches == null ? List.of() : batches);
        outputs = List.copyOf(outputs == null ? List.of() : outputs);
    }
}