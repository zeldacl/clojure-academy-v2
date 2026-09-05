package cn.li.presentation.core.engine;

/**
 * Fully-coerced paint parameters for one {@code UiOp.COMPOSITE} item,
 * produced by the control plane's {@link BindResolver} (which alone knows
 * how to read an item's :kind and per-kind fields out of Clojure-shaped
 * data) and consumed by {@link PaintKernel} using only plain field access —
 * the engine never touches a raw item map itself, keeping composite
 * interpretation on the Clojure side of the isolation boundary like every
 * other bound value.
 *
 * x/y/w/h are offsets relative to the composite node's own arranged rect,
 * matching the pre-rewrite :composite primitive's local-coordinate
 * authoring convention. u0/v0/u1/v1 select a texture sub-rect (full
 * texture by default) for sprite-sheet animation.
 */
public record CompositeSpec(int kind, float x, float y, float w, float h,
                             int rgba, String text, float fontSize, int resIndex,
                             float u0, float v0, float u1, float v1) {
    public static final int QUAD = 0;
    public static final int IMAGE = 1;
    public static final int TEXT = 2;
    public static final int CONDITION = 3;
    public static final int MODEL = 4;

    /** Full-texture UV defaults for call sites that do not crop the sprite. */
    public CompositeSpec(int kind, float x, float y, float w, float h,
                         int rgba, String text, float fontSize, int resIndex) {
        this(kind, x, y, w, h, rgba, text, fontSize, resIndex, 0f, 0f, 1f, 1f);
    }
}
