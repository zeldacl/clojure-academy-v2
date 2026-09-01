package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.UiResourceRef;

/**
 * Runtime form of one compiled .uic.edn artifact's node tree: struct-of-
 * arrays, indexed by a dense pre-order node index. Built once per view-id
 * (shared by every mount of that view) and immutable after construction —
 * every field here is read-only from the moment the constructor returns.
 *
 * This is the layer that replaces per-node persistent-map lookups
 * ((get-in node [:layout :direction]), a child-vector allocation per
 * traversal, etc.) with a single array read: no hashing, no boxing, no
 * per-frame allocation. See docs/06-gui/PRESENTATION_V3.md for the layout
 * algorithm this table feeds.
 */
public final class NodeTable {
    public final int n;

    // ── topology ──
    public final int[] op;
    public final int[] parent;
    public final int[] firstChild;
    public final int[] nextSibling;
    public final int[] childCount;
    public final int[] flags;

    // ── box model: 12 floats/node — margin l,t,r,b | padding l,t,r,b | min-w,min-h,max-w,max-h ──
    public final float[] box;

    // ── sizing ──
    public final int[] widthMode;
    public final float[] widthValue;
    public final int[] heightMode;
    public final float[] heightValue;
    public final float[] gap;
    public final float[] aspect;
    /** Declared offset for Direction.NONE children (absolute/stack authoring coordinates). */
    public final float[] declaredX;
    public final float[] declaredY;

    // ── flow ──
    public final int[] direction;
    public final int[] justify;
    public final int[] alignItems;
    public final int[] alignSelf;

    // ── indirection tables, -1 = none ──
    public final int[] style;
    public final int[] bind;
    public final int[] action;
    public final int[] res;
    public final int[] anim;
    public final int[] text;

    /** Static font size per node (default 8.0, matching the pre-rewrite fallback); a bound :font-size overrides this at paint/measure time. */
    public final float[] fontSize;

    // ── memoization: maskWords longs per node, subtree binding closure ──
    public final long[] depMask;
    public final int maskWords;
    public final int bindingCount;

    // ── side tables ──
    public final Object[] styleTable;
    public final String[] stringTable;
    public final UiResourceRef[] resources;
    /** Per-node binding path (a Clojure vector), indexed by {@link #bind}; control-plane only. */
    public final Object[] bindPaths;
    public final Object[] actionTable;
    public final Object[] nodeKeys;
    public final int[] focusOrder;

    public NodeTable(
            int n,
            int[] op, int[] parent, int[] firstChild, int[] nextSibling, int[] childCount, int[] flags,
            float[] box,
            int[] widthMode, float[] widthValue, int[] heightMode, float[] heightValue,
            float[] gap, float[] aspect, float[] declaredX, float[] declaredY,
            int[] direction, int[] justify, int[] alignItems, int[] alignSelf,
            int[] style, int[] bind, int[] action, int[] res, int[] anim, int[] text, float[] fontSize,
            long[] depMask, int maskWords, int bindingCount,
            Object[] styleTable, String[] stringTable, UiResourceRef[] resources,
            Object[] bindPaths, Object[] actionTable, Object[] nodeKeys, int[] focusOrder) {
        this.n = n;
        this.op = op;
        this.parent = parent;
        this.firstChild = firstChild;
        this.nextSibling = nextSibling;
        this.childCount = childCount;
        this.flags = flags;
        this.box = box;
        this.widthMode = widthMode;
        this.widthValue = widthValue;
        this.heightMode = heightMode;
        this.heightValue = heightValue;
        this.gap = gap;
        this.aspect = aspect;
        this.declaredX = declaredX;
        this.declaredY = declaredY;
        this.direction = direction;
        this.justify = justify;
        this.alignItems = alignItems;
        this.alignSelf = alignSelf;
        this.style = style;
        this.bind = bind;
        this.action = action;
        this.res = res;
        this.anim = anim;
        this.text = text;
        this.fontSize = fontSize;
        this.depMask = depMask;
        this.maskWords = maskWords;
        this.bindingCount = bindingCount;
        this.styleTable = styleTable;
        this.stringTable = stringTable;
        this.resources = resources;
        this.bindPaths = bindPaths;
        this.actionTable = actionTable;
        this.nodeKeys = nodeKeys;
        this.focusOrder = focusOrder;
    }

    public boolean has(int node, int flag) {
        return (flags[node] & flag) != 0;
    }

    public float marginL(int node) { return box[node * 12]; }
    public float marginT(int node) { return box[node * 12 + 1]; }
    public float marginR(int node) { return box[node * 12 + 2]; }
    public float marginB(int node) { return box[node * 12 + 3]; }
    public float padL(int node) { return box[node * 12 + 4]; }
    public float padT(int node) { return box[node * 12 + 5]; }
    public float padR(int node) { return box[node * 12 + 6]; }
    public float padB(int node) { return box[node * 12 + 7]; }
    public float minW(int node) { return box[node * 12 + 8]; }
    public float minH(int node) { return box[node * 12 + 9]; }
    public float maxW(int node) { return box[node * 12 + 10]; }
    public float maxH(int node) { return box[node * 12 + 11]; }

    public float padMain(int node, boolean row) {
        return row ? padL(node) + padR(node) : padT(node) + padB(node);
    }

    public float padCross(int node, boolean row) {
        return row ? padT(node) + padB(node) : padL(node) + padR(node);
    }
}
