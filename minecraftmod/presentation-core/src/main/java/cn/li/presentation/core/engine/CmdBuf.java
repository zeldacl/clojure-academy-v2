package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.UiResourceRef;
import cn.li.mcmod.runtime.ui.UiDrawList;

import java.util.Arrays;

/**
 * Accumulates one frame's worth of draw commands for one stage, then
 * {@link #finish} folds adjacent same-(op,res,clip) commands into runs and
 * wraps the result in a {@link UiDrawList}.
 *
 * One CmdBuf instance is owned by one mount and reused every frame — its
 * backing arrays are never defensively copied on {@link #finish}, they are
 * handed out live (oversized relative to {@code count}, which is exactly
 * why UiDrawList carries count separately from array length). This is safe
 * because the runtime's single-threaded, one-frame-in-flight contract
 * guarantees a UiDrawList is fully consumed by the backend before the next
 * frame's paint pass starts overwriting these same arrays; see
 * UiDrawList.generation and verifyPresentationNoRetainedPackets for the
 * guard against a backend defer that would violate this.
 *
 * Run merging is a single adjacent-scan, not a global sort: reordering
 * non-adjacent overlapping draws would be a correctness hazard for UI, and
 * adjacent-run merging already captures nearly all of the real win
 * (repeater item runs sharing one texture, consecutive glyph runs,
 * consecutive same-color quads).
 */
public final class CmdBuf {
    private int cap;
    public int n;

    private int[] op;
    private float[] geom;
    private int[] rgba;
    private float[] uv;
    private int[] res;
    private int[] clip;
    private float[] scalar;
    private Object[] aux;

    public CmdBuf(int initialCapacity) {
        grow(Math.max(1, initialCapacity));
    }

    public void reset() {
        n = 0;
    }

    /** Package access for {@link TransformGeom} — do not use from outside the engine. */
    float[] geomForTransform() {
        return geom;
    }

    /** Package access for {@link TransformGeom} — do not use from outside the engine. */
    int[] clipForTransform() {
        return clip;
    }

    public void ensure(int need) {
        if (need <= cap) return;
        int c = cap;
        while (c < need) c <<= 1;
        grow(c);
    }

    public int emit(int opcode, float x, float y, float w, float h, int rgbaColor, int resIdx, int clipIdx, float scalarValue, Object auxValue) {
        ensure(n + 1);
        int i = n++;
        op[i] = opcode;
        int g = i * 4;
        geom[g] = x;
        geom[g + 1] = y;
        geom[g + 2] = w;
        geom[g + 3] = h;
        rgba[i] = rgbaColor;
        // Default full-texture UV so backends can always read .uv without a
        // special-case for unset crops (growFloats zero-fills otherwise).
        uv[g] = 0f;
        uv[g + 1] = 0f;
        uv[g + 2] = 1f;
        uv[g + 3] = 1f;
        res[i] = resIdx;
        clip[i] = clipIdx;
        scalar[i] = scalarValue;
        aux[i] = auxValue;
        return i;
    }

    public void setUv(int index, float u0, float v0, float u1, float v1) {
        int b = index * 4;
        uv[b] = u0;
        uv[b + 1] = v0;
        uv[b + 2] = u1;
        uv[b + 3] = v1;
    }

    public UiDrawList finish(long generation, float[] clipRects, UiResourceRef[] resources) {
        int runs = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || op[i] != op[i - 1] || res[i] != res[i - 1] || clip[i] != clip[i - 1]) runs++;
        }

        int[] runStart = new int[runs];
        int[] runEnd = new int[runs];
        int[] runOp = new int[runs];
        int[] runRes = new int[runs];
        int[] runClip = new int[runs];
        int r = -1;
        for (int i = 0; i < n; i++) {
            if (i == 0 || op[i] != op[i - 1] || res[i] != res[i - 1] || clip[i] != clip[i - 1]) {
                r++;
                runStart[r] = i;
                runOp[r] = op[i];
                runRes[r] = res[i];
                runClip[r] = clip[i];
            }
            runEnd[r] = i + 1;
        }

        return new UiDrawList(generation, n, op, geom, rgba, uv, res, clip, scalar, aux,
                runs, runStart, runEnd, runOp, runRes, runClip, clipRects, resources);
    }

    private void grow(int c) {
        op = growInts(op, c);
        geom = growFloats(geom, c * 4);
        rgba = growInts(rgba, c);
        uv = growFloats(uv, c * 4);
        res = growInts(res, c, -1);
        clip = growInts(clip, c, -1);
        scalar = growFloats(scalar, c);
        aux = growObjects(aux, c);
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

    private static Object[] growObjects(Object[] a, int size) {
        Object[] next = new Object[size];
        if (a != null) System.arraycopy(a, 0, next, 0, a.length);
        return next;
    }
}
