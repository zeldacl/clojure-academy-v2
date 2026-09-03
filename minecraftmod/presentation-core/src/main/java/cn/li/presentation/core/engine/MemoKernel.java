package cn.li.presentation.core.engine;

/**
 * Folds a subtree's compiler-assigned binding-dependency mask into a single
 * stamp. Two frames with an identical stamp for the same instance are
 * guaranteed to produce identical layout+paint output for that subtree
 * (modulo hash collision — see the refactor plan's debug-mode verification
 * note), so the caller can skip measure/arrange/paint entirely and reuse
 * the previous frame's command slice with a raw array copy.
 */
public final class MemoKernel {
    private static final long SEED = 0x9E3779B97F4A7C15L;
    private static final long MIX = 0xFF51AFD7ED558CCDL;

    private MemoKernel() {
    }

    public static long subtreeStamp(MemoState m, NodeTable t, int node, long scrollRev) {
        long h = SEED;
        int words = t.maskWords;
        int base = node * words;
        for (int w = 0; w < words; w++) {
            long bits = t.depMask[base + w];
            while (bits != 0) {
                int b = Long.numberOfTrailingZeros(bits);
                int bindingIndex = w * 64 + b;
                long r = bindingIndex < m.rev.length ? m.rev[bindingIndex] : 0L;
                h = (h ^ r) * MIX;
                bits &= bits - 1;
            }
        }
        h = (h ^ m.geomRev) * MIX;
        h = (h ^ m.metricsEpoch) * MIX;
        h = (h ^ scrollRev) * MIX;
        return h;
    }
}
