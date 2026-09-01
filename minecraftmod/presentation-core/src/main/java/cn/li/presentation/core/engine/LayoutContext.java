package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiTextMetrics;

/**
 * Per-frame inputs threaded through {@link LayoutKernel}: everything the
 * algorithm needs beyond the immutable {@link NodeTable} and the mutable
 * {@link LayoutArena} it writes into.
 *
 * scrollOffsetByNode is indexed by NodeTable node index (not arena
 * instance — a scroll node is never itself repeated by a collection) and
 * must have length >= NodeTable.n; a null array means "no scrolling", 0f
 * default per slot means "not scrolled". The control plane owns clamping
 * offsets to [0, maxOffset] before building this context; the engine only
 * applies whatever offset it is given.
 */
public record LayoutContext(BindResolver resolver, UiTextMetrics metrics, float[] scrollOffsetByNode) {
    public float scrollOffset(int node) {
        return scrollOffsetByNode == null || node >= scrollOffsetByNode.length ? 0f : scrollOffsetByNode[node];
    }
}
