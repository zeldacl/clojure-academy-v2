package cn.li.presentation.core;

/** Dynamic host geometry supplied by a version boundary for each mounted view. */
public record HostGeometry(float originX, float originY, int viewportWidth,
                           int viewportHeight, float scale) {
    public HostGeometry {
        if (!Float.isFinite(originX) || !Float.isFinite(originY) ||
            !Float.isFinite(scale) || scale <= 0.0f ||
            viewportWidth < 0 || viewportHeight < 0) {
            throw new IllegalArgumentException("invalid host geometry");
        }
    }

    public static HostGeometry identity(int width, int height) {
        return new HostGeometry(0.0f, 0.0f, width, height, 1.0f);
    }
}