package cn.li.mc262.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Vector2f;

/**
 * A GUI quad warped by a {@link GuiPerspectiveWarp} homography.
 *
 * <p>{@code BlitRenderState} and {@code ColoredRectangleRenderState} emit four
 * vertices through an affine {@code Matrix3x2}, which cannot foreshorten. This
 * emits a subdivided grid instead: every grid vertex is projected through the
 * full homography, so the quad's outline is exactly the true perspective
 * quadrilateral.</p>
 *
 * <p>Subdivision is what keeps the interior honest. The GUI projection is
 * orthographic and vertices carry no {@code w}, so the rasteriser interpolates
 * each cell affinely; splitting the quad until a cell spans only a few dozen
 * pixels drives that residual well below one pixel. One undivided quad would
 * give the outline right but visibly swim the texture across it.</p>
 *
 * <p>Comes in the two flavours the extracted pipeline uses: {@link #textured}
 * for {@code POSITION_TEX_COLOR} pipelines and {@link #colored} for
 * {@code POSITION_COLOR} ones, the latter carrying the same top-to-bottom
 * gradient that {@code fillGradient} expects.</p>
 */
public final class PerspectiveQuadRenderState implements GuiElementRenderState {

    /** Cells beyond this buy nothing visible and cost vertices. */
    private static final int MAX_SUBDIVISIONS = 12;
    /** Target on-screen size of one cell, in GUI-scaled pixels. */
    private static final float TARGET_CELL_PIXELS = 24.0F;

    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final ScreenRectangle scissorArea;
    private final ScreenRectangle bounds;
    private final float[] homography;
    private final float x0, y0, x1, y1;
    private final float u0, v0, u1, v1;
    private final boolean useUv;
    private final int colorTop, colorBottom;
    private final int cols, rows;

    private PerspectiveQuadRenderState(
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds,
            float[] homography,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1,
            boolean useUv,
            int colorTop, int colorBottom,
            int cols, int rows) {
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;
        this.scissorArea = scissorArea;
        this.bounds = bounds;
        this.homography = homography;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        this.useUv = useUv;
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        this.cols = cols;
        this.rows = rows;
    }

    /**
     * A warped textured quad, for {@code POSITION_TEX_COLOR} pipelines.
     *
     * @param warp the active homography, from {@link GuiPerspectiveWarp#active()}
     * @param pose the GUI pose in effect, folded in so local coords stay local
     * @return the render state, or {@code null} when the quad is degenerate or
     *         falls at/behind the eye
     */
    public static PerspectiveQuadRenderState textured(
            float[] warp,
            org.joml.Matrix3x2fc pose,
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1,
            int color,
            ScreenRectangle scissorArea) {
        return of(warp, pose, pipeline, textureSetup, x0, y0, x1, y1,
                u0, v0, u1, v1, true, color, color, scissorArea);
    }

    /**
     * A warped solid quad, for {@code POSITION_COLOR} pipelines.
     *
     * <p>{@code colorTop} and {@code colorBottom} reproduce
     * {@code fillGradient}'s vertical ramp; pass the same value twice for a flat
     * fill. The ramp is sampled per subdivided row, which matches the original
     * because vertex colours vary linearly down the unwarped quad too.</p>
     */
    public static PerspectiveQuadRenderState colored(
            float[] warp,
            org.joml.Matrix3x2fc pose,
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            float x0, float y0, float x1, float y1,
            int colorTop, int colorBottom,
            ScreenRectangle scissorArea) {
        return of(warp, pose, pipeline, textureSetup, x0, y0, x1, y1,
                0f, 0f, 0f, 0f, false, colorTop, colorBottom, scissorArea);
    }

    private static PerspectiveQuadRenderState of(
            float[] warp,
            org.joml.Matrix3x2fc pose,
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1,
            boolean useUv,
            int colorTop, int colorBottom,
            ScreenRectangle scissorArea) {
        if (warp == null || pipeline == null || textureSetup == null) {
            return null;
        }
        // GuiGraphicsExtractor.fill accepts either corner order and normalises
        // internally, so callers do too. Swap the paired attributes along with
        // the coordinates rather than dropping the quad.
        if (x0 > x1) {
            float tmpX = x0; x0 = x1; x1 = tmpX;
            float tmpU = u0; u0 = u1; u1 = tmpU;
        }
        if (y0 > y1) {
            float tmpY = y0; y0 = y1; y1 = tmpY;
            float tmpV = v0; v0 = v1; v1 = tmpV;
            int tmpC = colorTop; colorTop = colorBottom; colorBottom = tmpC;
        }
        if (x0 == x1 || y0 == y1) {
            return null;
        }
        float[] h = GuiPerspectiveWarp.compose(warp, pose);

        // A homography maps the rectangle to a quadrilateral with these four
        // corners, so their AABB is the element's true screen bounds. Those
        // bounds drive GuiRenderState's automatic layering, so they must be real.
        Vector2f corner = new Vector2f();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[] xs = {x0, x1, x0, x1};
        float[] ys = {y0, y0, y1, y1};
        for (int i = 0; i < 4; i++) {
            if (!GuiPerspectiveWarp.project(h, xs[i], ys[i], corner)) {
                return null;
            }
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
        }

        int left = (int) Math.floor(minX);
        int top = (int) Math.floor(minY);
        int right = (int) Math.ceil(maxX);
        int bottom = (int) Math.ceil(maxY);
        ScreenRectangle projected = new ScreenRectangle(left, top, right - left, bottom - top);
        ScreenRectangle bounds = scissorArea != null ? scissorArea.intersection(projected) : projected;

        int cols = subdivisions(maxX - minX);
        int rows = subdivisions(maxY - minY);
        return new PerspectiveQuadRenderState(
                pipeline, textureSetup, scissorArea, bounds, h,
                x0, y0, x1, y1, u0, v0, u1, v1, useUv, colorTop, colorBottom, cols, rows);
    }

    private static int subdivisions(float projectedSize) {
        int n = Math.round(projectedSize / TARGET_CELL_PIXELS);
        return Math.max(1, Math.min(MAX_SUBDIVISIONS, n));
    }

    /** Per-channel ARGB blend, matching how the rasteriser ramps vertex colours. */
    private static int lerpColor(int from, int to, float t) {
        if (from == to) {
            return from;
        }
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >> shift) & 0xFF;
            int b = (to >> shift) & 0xFF;
            out |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        float stepX = (this.x1 - this.x0) / this.cols;
        float stepY = (this.y1 - this.y0) / this.rows;
        float stepU = (this.u1 - this.u0) / this.cols;
        float stepV = (this.v1 - this.v0) / this.rows;
        Vector2f scratch = new Vector2f();
        for (int row = 0; row < this.rows; row++) {
            float cellY0 = this.y0 + stepY * row;
            float cellY1 = row == this.rows - 1 ? this.y1 : cellY0 + stepY;
            float cellV0 = this.v0 + stepV * row;
            float cellV1 = row == this.rows - 1 ? this.v1 : cellV0 + stepV;
            int cellC0 = lerpColor(this.colorTop, this.colorBottom, (float) row / this.rows);
            int cellC1 = lerpColor(this.colorTop, this.colorBottom, (float) (row + 1) / this.rows);
            for (int col = 0; col < this.cols; col++) {
                float cellX0 = this.x0 + stepX * col;
                float cellX1 = col == this.cols - 1 ? this.x1 : cellX0 + stepX;
                float cellU0 = this.u0 + stepU * col;
                float cellU1 = col == this.cols - 1 ? this.u1 : cellU0 + stepU;
                // Same winding as BlitRenderState / ColoredRectangleRenderState:
                // TL, BL, BR, TR.
                emit(vertexConsumer, scratch, cellX0, cellY0, cellU0, cellV0, cellC0);
                emit(vertexConsumer, scratch, cellX0, cellY1, cellU0, cellV1, cellC1);
                emit(vertexConsumer, scratch, cellX1, cellY1, cellU1, cellV1, cellC1);
                emit(vertexConsumer, scratch, cellX1, cellY0, cellU1, cellV0, cellC0);
            }
        }
    }

    private void emit(VertexConsumer vertexConsumer, Vector2f scratch,
                      float x, float y, float u, float v, int color) {
        // `of` already proved all four corners project, and the grid stays inside
        // them, so this cannot fail for a convex-mapped rectangle.
        GuiPerspectiveWarp.project(this.homography, x, y, scratch);
        VertexConsumer vertex = vertexConsumer.addVertex(scratch.x, scratch.y, 0.0F);
        if (this.useUv) {
            vertex.setUv(u, v);
        }
        vertex.setColor(color);
    }

    @Override
    public RenderPipeline pipeline() {
        return this.pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return this.textureSetup;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return this.scissorArea;
    }

    @Override
    public ScreenRectangle bounds() {
        return this.bounds;
    }
}
