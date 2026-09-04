package cn.li.presentation.core.engine;

/**
 * Applies an affine panel-scale + tilt to a {@link CmdBuf}'s geometry in place.
 * Package-local so it can reach CmdBuf's backing arrays without widening the
 * public paint API. Clears clip indices — AABB scissors are wrong under tilt.
 */
public final class TransformGeom {
    private TransformGeom() {}

    public static void applyAffine(CmdBuf buf, float panelScale, float tiltDegrees,
                                   float contentX, float contentY,
                                   float contentW, float contentH) {
        if (buf == null || buf.n <= 0) {
            return;
        }
        float cx = contentX + contentW * 0.5f;
        float cy = contentY + contentH * 0.5f;
        double rad = Math.toRadians(tiltDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float scale = panelScale;
        float[] geom = buf.geomForTransform();
        int[] clip = buf.clipForTransform();
        int n = buf.n;
        for (int i = 0; i < n; i++) {
            int g = i * 4;
            float x = geom[g];
            float y = geom[g + 1];
            float w = geom[g + 2];
            float h = geom[g + 3];
            // Transform the four corners and take the AABB so fill/blit stay axis-aligned.
            float x0 = x, y0 = y;
            float x1 = x + w, y1 = y;
            float x2 = x + w, y2 = y + h;
            float x3 = x, y3 = y + h;
            float[] xs = new float[4];
            float[] ys = new float[4];
            float[] inX = {x0, x1, x2, x3};
            float[] inY = {y0, y1, y2, y3};
            for (int c = 0; c < 4; c++) {
                float dx = inX[c] - cx;
                float dy = inY[c] - cy;
                float rx = scale * (dx * cos + dy * (-sin));
                float ry = scale * (dx * sin + dy * cos);
                xs[c] = cx + rx;
                ys[c] = cy + ry;
            }
            float minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
            float minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
            float maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
            float maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
            geom[g] = minX;
            geom[g + 1] = minY;
            geom[g + 2] = maxX - minX;
            geom[g + 3] = maxY - minY;
            clip[i] = -1;
        }
    }
}
