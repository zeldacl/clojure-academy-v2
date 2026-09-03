package cn.li.presentation.core.engine;

import java.util.Arrays;

/**
 * Per-mount memoization state: a revision counter per binding, bumped only
 * when that binding's resolved value actually changed since the last
 * frame. This is what makes a "clean frame" (no state change at all) cost
 * zero traversal: {@link MemoKernel#subtreeStamp} folds only the handful
 * of revisions a subtree's compiler-assigned dep-mask actually depends on,
 * so the cost of checking "did anything I care about change" is
 * O(#bindings-in-this-subtree), never O(#nodes).
 */
public final class MemoState {
    long[] rev;
    Object[] lastVal;
    long epoch;
    long[] scrollRev;
    long metricsEpoch;
    long geomRev;
    boolean[] scrollTouched;

    public MemoState(int bindingCount, int scrollSlotCount) {
        rev = new long[Math.max(bindingCount, 1)];
        lastVal = new Object[Math.max(bindingCount, 1)];
        scrollRev = new long[Math.max(scrollSlotCount, 1)];
        scrollTouched = new boolean[Math.max(scrollSlotCount, 1)];
    }

    /**
     * Refresh binding revisions against a freshly-resolved value array.
     * {@code ==} fast path first (persistent-collection identity), then
     * {@code equals} — a value a producer rebuilt from scratch but that is
     * structurally unchanged still counts as unchanged, at the cost of an
     * equals() call instead of a pointer compare. O(bound.length).
     */
    public void refreshRevs(Object[] bound) {
        epoch++;
        for (int i = 0; i < bound.length; i++) {
            Object v = bound[i];
            Object pv = lastVal[i];
            if (v == pv) continue;
            if (v != null && v.equals(pv)) continue;
            lastVal[i] = v;
            rev[i] = epoch;
        }
    }

    /** Bump one scroll node's revision slot (its offset changed since the last frame). */
    public void touchScroll(int scrollSlot) {
        epoch++;
        scrollRev[scrollSlot] = epoch;
        scrollTouched[scrollSlot] = true;
    }

    /** Force the next stamp comparison to miss for every subtree (view resized, view mounted, etc). */
    public void invalidateGeometry() {
        geomRev = ++epoch;
    }

    /** Force the next stamp comparison to miss for every text node (font face became ready/unready). */
    public void invalidateMetrics() {
        metricsEpoch = ++epoch;
    }

    public void ensureCapacity(int bindingCount, int scrollSlotCount) {
        if (bindingCount > rev.length) {
            rev = Arrays.copyOf(rev, bindingCount);
            lastVal = Arrays.copyOf(lastVal, bindingCount);
        }
        if (scrollSlotCount > scrollRev.length) {
            scrollRev = Arrays.copyOf(scrollRev, scrollSlotCount);
            scrollTouched = Arrays.copyOf(scrollTouched, scrollSlotCount);
        }
    }
}
