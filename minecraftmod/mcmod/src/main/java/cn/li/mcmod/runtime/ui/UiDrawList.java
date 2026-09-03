package cn.li.mcmod.runtime.ui;

import cn.li.mcmod.runtime.UiResourceRef;

/**
 * Neutral, batched UI draw command list for exactly one stage of one frame.
 * Struct-of-arrays layout so the backend never walks a per-node object
 * graph: every field is a flat array indexed by command index, and the
 * "run" arrays describe contiguous ranges of {@link #op}/{@link #res}/
 * {@link #clip} that a backend can draw with a single texture bind and a
 * single shader-color call (see cn.li.presentation.core.engine.CmdBuf,
 * which is the only producer of this record).
 *
 * generation identifies the frame this list was produced for. A backend
 * that defers submission (e.g. a streaming-buffer target) must check this
 * against the frame it is currently rendering — a stale UiDrawList must
 * never be drawn, since its geometry may reference a arena that has since
 * been overwritten (see verifyPresentationNoRetainedPackets).
 */
public record UiDrawList(
        long generation,
        int count,
        int[] op,
        float[] geom,
        int[] rgba,
        float[] uv,
        int[] res,
        int[] clip,
        float[] scalar,
        Object[] aux,
        int runCount,
        int[] runStart,
        int[] runEnd,
        int[] runOp,
        int[] runRes,
        int[] runClip,
        float[] clipRects,
        UiResourceRef[] resources) {

    private static final UiDrawList EMPTY = new UiDrawList(
            0L, 0,
            new int[0], new float[0], new int[0], new float[0],
            new int[0], new int[0], new float[0], new Object[0],
            0, new int[0], new int[0], new int[0], new int[0], new int[0],
            new float[0], new UiResourceRef[0]);

    public static UiDrawList empty(long generation) {
        return generation == 0L ? EMPTY : new UiDrawList(
                generation, 0,
                EMPTY.op, EMPTY.geom, EMPTY.rgba, EMPTY.uv,
                EMPTY.res, EMPTY.clip, EMPTY.scalar, EMPTY.aux,
                0, EMPTY.runStart, EMPTY.runEnd, EMPTY.runOp, EMPTY.runRes, EMPTY.runClip,
                EMPTY.clipRects, EMPTY.resources);
    }
}
