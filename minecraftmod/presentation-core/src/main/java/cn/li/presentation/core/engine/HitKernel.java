package cn.li.presentation.core.engine;

/**
 * Geometric hit testing over an already-arranged {@link LayoutArena}. This
 * kernel answers exactly one question — "what is under this point" — and
 * nothing about what that means: whether a hit fires :activate vs :hover,
 * how a progress-drag ratio is computed, which :on handler applies, all of
 * that is the control plane's job, looked up from the original artifact
 * keyed by the node index this returns. That split keeps the engine
 * MC/Clojure-free and keeps a single point release from needing two
 * kernels to agree on event semantics.
 *
 * A single top-to-bottom recursive walk (topmost = last-painted = last
 * child wins) replaces the pre-rewrite runtime's three independent full-
 * tree traversals (hit-action/hit-hover/hit-scroll), each of which
 * re-derived layout via its own duplicate rect computation. Because it reads the
 * committed arena, it structurally cannot disagree with what was painted.
 *
 * Allocation is proportional to the depth and branching of the actual hit
 * path (one small int[] per visited level, sized to that level's child
 * count), not to total tree size — a large improvement over the old
 * per-event full-tree seq/vector allocation, though not literally zero;
 * a future pass could thread a reusable per-mount scratch stack through
 * here to close that gap if profiling shows it matters.
 */
public final class HitKernel {
    private HitKernel() {
    }

    /**
     * @param instance      arena instance index of the hit node
     * @param node          NodeTable node index of the hit node
     * @param item          enclosing collection item, or null
     * @param itemIndex     index within the enclosing collection, or -1
     * @param enclosingScroll instance index of the nearest IS_SCROLL ancestor
     *                      found along the hit path (including the hit node
     *                      itself), or -1 if none — lets the control plane
     *                      route wheel/drag-scroll without a second traversal
     */
    public record Hit(int instance, int node, Object item, int itemIndex, int enclosingScroll) {
    }

    public static Hit topmostAt(NodeTable t, LayoutArena a, BindResolver resolver, int root, float px, float py) {
        return hit(t, a, resolver, root, px, py, -1);
    }

    /**
     * The innermost IS_SCROLL container whose clip and own rect contain the
     * point, or -1. Unlike {@link #topmostAt}, this does not require a
     * HIT_TESTABLE/FOCUSABLE node at the point — mouse wheel and drag-scroll
     * need to know which scroll container the pointer is over even when it
     * isn't hovering an interactive child of that container.
     */
    public static int enclosingScrollAt(NodeTable t, LayoutArena a, BindResolver resolver, int root, float px, float py) {
        return scrollWalk(t, a, resolver, root, px, py, -1);
    }

    private static int scrollWalk(NodeTable t, LayoutArena a, BindResolver resolver, int inst, float px, float py, int scrollAncestor) {
        if (!LayoutKernel.visible(t, a, resolver, inst)) return scrollAncestor;
        int clip = a.clipOf[inst];
        if (clip >= 0 && !inRegion(a.clipRects, clip, px, py)) return scrollAncestor;
        int node = a.nodeOf[inst];
        int myScroll = (t.has(node, NodeFlags.IS_SCROLL) && inRect(a, inst, px, py)) ? inst : scrollAncestor;
        int end = a.subtreeEnd[inst];
        int result = myScroll;
        for (int c = LayoutKernel.firstChild(a, inst); c >= 0; c = LayoutKernel.nextSibling(a, c, end)) {
            // Prefer a scroll found in a child subtree. Non-scroll siblings that
            // cover the same point (e.g. tutorial's absolute scrollbar strip over
            // the markdown pane) must NOT wipe a previously found scroll back to
            // the ancestor — that made mouse-wheel over mid-panel content a no-op.
            int childResult = scrollWalk(t, a, resolver, c, px, py, myScroll);
            if (childResult != myScroll) {
                result = childResult;
            }
        }
        return result;
    }

    private static Hit hit(NodeTable t, LayoutArena a, BindResolver resolver, int inst, float px, float py, int scrollAncestor) {
        if (!LayoutKernel.visible(t, a, resolver, inst)) return null;

        int clip = a.clipOf[inst];
        if (clip >= 0 && !inRegion(a.clipRects, clip, px, py)) return null;

        int node = a.nodeOf[inst];
        int myScroll = t.has(node, NodeFlags.IS_SCROLL) ? inst : scrollAncestor;

        int end = a.subtreeEnd[inst];
        int childCount = firstLevelChildCount(a, inst, end);
        if (childCount > 0) {
            int[] children = new int[childCount];
            int k = 0;
            for (int c = LayoutKernel.firstChild(a, inst); c >= 0; c = LayoutKernel.nextSibling(a, c, end)) {
                children[k++] = c;
            }
            for (int i = k - 1; i >= 0; i--) {
                Hit childHit = hit(t, a, resolver, children[i], px, py, myScroll);
                if (childHit != null) return childHit;
            }
        }

        if (!t.has(node, NodeFlags.HIT_TESTABLE) && !t.has(node, NodeFlags.FOCUSABLE)) return null;
        if (!inRect(a, inst, px, py)) return null;
        return new Hit(inst, node, a.itemOf[inst], a.itemIndexOf[inst], myScroll);
    }

    private static int firstLevelChildCount(LayoutArena a, int inst, int end) {
        int n = 0;
        for (int c = LayoutKernel.firstChild(a, inst); c >= 0; c = LayoutKernel.nextSibling(a, c, end)) n++;
        return n;
    }

    private static boolean inRect(LayoutArena a, int inst, float px, float py) {
        float x = a.x(inst);
        float y = a.y(inst);
        float w = a.w(inst);
        float h = a.h(inst);
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static boolean inRegion(float[] rects, int idx, float px, float py) {
        int b = idx * 4;
        float x = rects[b];
        float y = rects[b + 1];
        float w = rects[b + 2];
        float h = rects[b + 3];
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
