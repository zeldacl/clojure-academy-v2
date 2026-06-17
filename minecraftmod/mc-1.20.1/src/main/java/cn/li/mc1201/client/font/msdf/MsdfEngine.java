package cn.li.mc1201.client.font.msdf;

import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.stb.STBTruetype;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates multi-channel signed distance fields from STB glyph outlines.
 * RGB stores angle-partitioned edge distances; alpha stores true signed distance.
 */
public final class MsdfEngine {

    private static final int EDGE_COUNT = 3;

    private MsdfEngine() {
    }

    public static final class MsdfPixels {
        public final int width;
        public final int height;
        /** RGBA bytes, row-major, premultiplied distance in 0..255 */
        public final byte[] rgba;

        public MsdfPixels(final int width, final int height, final byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    public static MsdfPixels generate(
            final MsdfFontFace face,
            final int glyphIndex,
            final int pxRange) {
        return generate(face, glyphIndex, pxRange, pxRange);
    }

    public static MsdfPixels generate(
            final MsdfFontFace face,
            final int glyphIndex,
            final int sdfPxRange,
            final int outerPad) {
        final float scale = face.scale();
        final int[] box = face.getGlyphBox(glyphIndex);
        final int glyphW = box[2] - box[0];
        final int glyphH = box[3] - box[1];
        final int pad = Math.max(sdfPxRange, outerPad);
        final int width = Math.max(1, glyphW + 2 * pad);
        final int height = Math.max(1, glyphH + 2 * pad);

        final List<Segment> segments = buildSegments(face, glyphIndex, scale, pad, box[0], box[1]);
        final byte[] rgba = new byte[width * height * 4];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final float px = x + 0.5f;
                final float py = y + 0.5f;
                final float[] channelDist = new float[EDGE_COUNT];
                for (int c = 0; c < EDGE_COUNT; c++) {
                    channelDist[c] = Float.POSITIVE_INFINITY;
                }
                float trueDist = Float.POSITIVE_INFINITY;
                boolean inside = false;

                for (final Segment seg : segments) {
                    final float dist = seg.signedDistance(px, py);
                    final int channel = seg.channel;
                    if (Math.abs(dist) < Math.abs(channelDist[channel])) {
                        channelDist[channel] = dist;
                    }
                    if (Math.abs(dist) < Math.abs(trueDist)) {
                        trueDist = dist;
                    }
                }

                if (segments.isEmpty()) {
                    inside = false;
                    trueDist = sdfPxRange;
                } else {
                    inside = pointInsidePolygon(px, py, segments);
                    if (!inside && trueDist > 0) {
                        trueDist = -trueDist;
                    } else if (inside && trueDist < 0) {
                        trueDist = -trueDist;
                    }
                }

                final int idx = (y * width + x) * 4;
                for (int c = 0; c < EDGE_COUNT; c++) {
                    float d = channelDist[c];
                    if (Float.isInfinite(d)) {
                        d = inside ? sdfPxRange : -sdfPxRange;
                    }
                    rgba[idx + c] = distanceToByte(d, sdfPxRange);
                }
                rgba[idx + 3] = distanceToByte(trueDist, sdfPxRange);
            }
        }

        return new MsdfPixels(width, height, rgba);
    }

    private static byte distanceToByte(final float dist, final int pxRange) {
        final float normalized = 0.5f + dist / (2.0f * pxRange);
        final int v = Math.round(Math.max(0.0f, Math.min(1.0f, normalized)) * 255.0f);
        return (byte) v;
    }

    private static boolean pointInsidePolygon(final float px, final float py, final List<Segment> segments) {
        int crossings = 0;
        for (final Segment seg : segments) {
            if ((seg.y0 <= py && seg.y1 > py) || (seg.y1 <= py && seg.y0 > py)) {
                final float xIntersect = seg.x0 + (py - seg.y0) / (seg.y1 - seg.y0) * (seg.x1 - seg.x0);
                if (px < xIntersect) {
                    crossings++;
                }
            }
        }
        return (crossings & 1) == 1;
    }

    private static List<Segment> buildSegments(
            final MsdfFontFace face,
            final int glyphIndex,
            final float scale,
            final int pad,
            final int boxX0,
            final int boxY0) {
        final List<Segment> out = new ArrayList<>();
        final STBTTVertex.Buffer shape = face.getGlyphShape(glyphIndex);
        if (shape == null) {
            return out;
        }

        float cx = 0;
        float cy = 0;
        float sx = 0;
        float sy = 0;
        int contourStart = 0;

        for (int i = 0; i <= shape.limit(); i++) {
            final boolean end = i == shape.limit();
            if (!end) {
                final STBTTVertex v = shape.get(i);
                final int type = v.type();
                if (type == STBTruetype.STBTT_vmove) {
                    if (i > contourStart) {
                        addSegment(out, cx, cy, sx, sy, scale, pad, boxX0, boxY0);
                    }
                    cx = v.x();
                    cy = v.y();
                    sx = cx;
                    sy = cy;
                    contourStart = i;
                } else if (type == STBTruetype.STBTT_vline) {
                    addSegment(out, cx, cy, v.x(), v.y(), scale, pad, boxX0, boxY0);
                    cx = v.x();
                    cy = v.y();
                } else if (type == STBTruetype.STBTT_vcurve) {
                    flattenQuadratic(out, cx, cy, v.cx(), v.cy(), v.x(), v.y(), scale, pad, boxX0, boxY0);
                    cx = v.x();
                    cy = v.y();
                }
            }
            if (end || (i > contourStart && i + 1 < shape.limit() && shape.get(i + 1).type() == STBTruetype.STBTT_vmove)) {
                addSegment(out, cx, cy, sx, sy, scale, pad, boxX0, boxY0);
            }
        }
        shape.free();
        return out;
    }

    private static void flattenQuadratic(
            final List<Segment> out,
            final float x0, final float y0,
            final float cx, final float cy,
            final float x1, final float y1,
            final float scale, final int pad, final int boxX0, final int boxY1) {
        float prevX = x0;
        float prevY = y0;
        final int steps = 8;
        for (int s = 1; s <= steps; s++) {
            final float t = s / (float) steps;
            final float omt = 1.0f - t;
            final float px = omt * omt * x0 + 2 * omt * t * cx + t * t * x1;
            final float py = omt * omt * y0 + 2 * omt * t * cy + t * t * y1;
            addSegment(out, prevX, prevY, px, py, scale, pad, boxX0, boxY1);
            prevX = px;
            prevY = py;
        }
    }

    private static void addSegment(
            final List<Segment> out,
            final float x0, final float y0,
            final float x1, final float y1,
            final float scale, final int pad,
            final int boxX0, final int boxY0) {
        if (x0 == x1 && y0 == y1) {
            return;
        }
        final float fx0 = x0 * scale + pad;
        final float fy0 = -y0 * scale + pad - boxY0 * scale;
        final float fx1 = x1 * scale + pad;
        final float fy1 = -y1 * scale + pad - boxY0 * scale;
        final float dx = fx1 - fx0;
        final float dy = fy1 - fy0;
        final float angle = (float) Math.atan2(dy, dx);
        final int channel = edgeChannel(angle);
        out.add(new Segment(fx0, fy0, fx1, fy1, channel));
    }

    private static int edgeChannel(final float angle) {
        final float a = angle < 0 ? angle + (float) (2 * Math.PI) : angle;
        final float sector = a / ((float) (2 * Math.PI) / EDGE_COUNT);
        return Math.min(EDGE_COUNT - 1, (int) sector);
    }

    private static final class Segment {
        final float x0, y0, x1, y1;
        final int channel;

        Segment(final float x0, final float y0, final float x1, final float y1, final int channel) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.channel = channel;
        }

        float signedDistance(final float px, final float py) {
            final float dx = x1 - x0;
            final float dy = y1 - y0;
            final float lenSq = dx * dx + dy * dy;
            if (lenSq < 1e-6f) {
                final float d = (float) Math.hypot(px - x0, py - y0);
                return d;
            }
            float t = ((px - x0) * dx + (py - y0) * dy) / lenSq;
            t = Math.max(0, Math.min(1, t));
            final float closestX = x0 + t * dx;
            final float closestY = y0 + t * dy;
            return (float) Math.hypot(px - closestX, py - closestY);
        }
    }
}
