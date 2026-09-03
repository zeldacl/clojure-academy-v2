package cn.li.presentation.core.engine;

/**
 * {@link NodeTable#flow} direction values. NONE means children are placed by
 * their own declared x/y (or by an anchor) rather than packed along an axis
 * — the absolute/stack primitives.
 */
public interface Direction {
    int NONE = 0;
    int ROW = 1;
    int COLUMN = 2;
}
