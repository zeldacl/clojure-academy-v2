package cn.li.presentation.core.engine;

import java.util.Arrays;

/**
 * Layout output buffer, indexed by instance (not node — a collection
 * expands its template subtree N times, so more than one instance can
 * share the same {@link NodeTable} node index). Owned by one mount, reused
 * across frames; arrays only grow, never shrink, and only reallocate when
 * the instance count exceeds current capacity (steady state: zero
 * reallocation once a view's tree size stabilizes).
 *
 * Instances are written in pre-order during {@code LayoutKernel.arrange},
 * which makes {@link #subtreeEnd} the mechanism that turns "skip this
 * subtree" into a single array read instead of a recursive tree walk:
 * both paint and hit-test are linear forward/reverse scans over these
 * arrays, never a recursive descent over a node graph.
 *
 * Two instances of this class per mount ("cur"/"prev"), swapped on commit.
 * Pointer events between frames hit-test against the committed ("prev"
 * from the writer's perspective, "cur" from the reader's) arena — layout
 * and hit-testing structurally cannot desync, because they read the exact
 * same geometry.
 *
 * Instances carry no explicit child-list pointers. Because {@link
 * #subtreeEnd} is written in pre-order, the whole tree shape is recoverable
 * from two facts: the first child of instance i (if any) is i+1, and the
 * next sibling of a child starting at c is exactly subtreeEnd[c] — walking
 * a child list is a tight loop over subtreeEnd, not a linked-list traversal.
 * See {@link LayoutKernel#firstChild} / {@link LayoutKernel#nextSibling}.
 */
public final class LayoutArena {
    private int cap;
    public int n;

    public int[] nodeOf;
    public int[] parentOf;
    /** Exclusive end index of this instance's subtree in pre-order. */
    public int[] subtreeEnd;

    /** 4 floats/instance: x, y, w, h (arrange output, absolute/screen space). */
    public float[] rect;
    /** 2 floats/instance: measured width, measured height (measure output). */
    public float[] meas;

    public int[] clipOf;
    public int[] visible;
    public Object[] itemOf;
    public int[] itemIndexOf;

    public long[] stamp;
    public int[] cmdStart;
    public int[] cmdEnd;

    public float[] clipRects;
    public int clipCount;

    public long[] animStart;

    public LayoutArena(int initialCapacity) {
        grow(Math.max(1, initialCapacity));
    }

    public void ensure(int need) {
        if (need <= cap) return;
        int c = cap;
        while (c < need) c <<= 1;
        grow(c);
    }

    public int capacity() {
        return cap;
    }

    public float x(int i) { return rect[i * 4]; }
    public float y(int i) { return rect[i * 4 + 1]; }
    public float w(int i) { return rect[i * 4 + 2]; }
    public float h(int i) { return rect[i * 4 + 3]; }
    public float measW(int i) { return meas[i * 2]; }
    public float measH(int i) { return meas[i * 2 + 1]; }

    public void setRect(int i, float x, float y, float w, float h) {
        int b = i * 4;
        rect[b] = x;
        rect[b + 1] = y;
        rect[b + 2] = w;
        rect[b + 3] = h;
    }

    public void setMeas(int i, float w, float h) {
        int b = i * 2;
        meas[b] = w;
        meas[b + 1] = h;
    }

    public void ensureClipCapacity(int need) {
        if (need * 4 <= clipRects.length) return;
        int c = Math.max(4, clipRects.length);
        while (c < need * 4) c <<= 1;
        clipRects = Arrays.copyOf(clipRects, c);
    }

    public int pushClip(float x, float y, float w, float h) {
        ensureClipCapacity(clipCount + 1);
        int idx = clipCount++;
        int b = idx * 4;
        clipRects[b] = x;
        clipRects[b + 1] = y;
        clipRects[b + 2] = w;
        clipRects[b + 3] = h;
        return idx;
    }

    public void resetClips() {
        clipCount = 0;
    }

    private void grow(int c) {
        nodeOf = growInts(nodeOf, c);
        parentOf = growInts(parentOf, c, -1);
        subtreeEnd = growInts(subtreeEnd, c);
        rect = growFloats(rect, c * 4);
        meas = growFloats(meas, c * 2);
        clipOf = growInts(clipOf, c, -1);
        visible = growInts(visible, c);
        itemOf = growObjects(itemOf, c);
        itemIndexOf = growInts(itemIndexOf, c, -1);
        stamp = growLongs(stamp, c);
        cmdStart = growInts(cmdStart, c);
        cmdEnd = growInts(cmdEnd, c);
        animStart = growLongs(animStart, c);
        if (clipRects == null) clipRects = new float[16];
        cap = c;
    }

    private static int[] growInts(int[] a, int size) {
        int[] next = new int[size];
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }

    private static int[] growInts(int[] a, int size, int fill) {
        int[] next = new int[size];
        Arrays.fill(next, fill);
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }

    private static float[] growFloats(float[] a, int size) {
        float[] next = new float[size];
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }

    private static long[] growLongs(long[] a, int size) {
        long[] next = new long[size];
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }

    private static Object[] growObjects(Object[] a, int size) {
        Object[] next = new Object[size];
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }
}
