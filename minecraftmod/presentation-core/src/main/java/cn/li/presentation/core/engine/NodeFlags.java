package cn.li.presentation.core.engine;

/** Bit flags on {@link NodeTable#flags}. Compiler-assigned, read-only at runtime. */
public interface NodeFlags {
    int HAS_CLIP = 1;
    int IS_SCROLL = 1 << 1;
    int IS_COLLECTION = 1 << 2;
    int HIT_TESTABLE = 1 << 3;
    int HAS_VISIBLE_BIND = 1 << 4;
    int WRAP = 1 << 5;
    int FOCUSABLE = 1 << 6;
    int ANIMATED = 1 << 7;
    /** Hit-test may skip the whole subtree on miss without testing children individually. */
    int OPAQUE = 1 << 8;
    int SCROLLBAR = 1 << 9;
    /** Node declares an explicit flow direction (row/column groups); see {@link Direction}. */
    int HAS_DIRECTION = 1 << 10;
}

