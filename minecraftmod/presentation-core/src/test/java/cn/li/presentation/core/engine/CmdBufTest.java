package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiDrawList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CmdBufTest {
    @Test
    void emptyBufferProducesZeroRuns() {
        CmdBuf buf = new CmdBuf(4);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(0, dl.count());
        assertEquals(0, dl.runCount());
    }

    @Test
    void singleCommandIsOneRun() {
        CmdBuf buf = new CmdBuf(4);
        buf.emit(0, 0, 0, 10, 10, 0xFFFFFFFF, -1, -1, 0f, null);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(1, dl.count());
        assertEquals(1, dl.runCount());
        assertArrayEquals(new int[]{0}, dl.runStart());
        assertArrayEquals(new int[]{1}, dl.runEnd());
    }

    @Test
    void allSameKeyMergesIntoOneRun() {
        CmdBuf buf = new CmdBuf(4);
        for (int i = 0; i < 5; i++) buf.emit(0, i, 0, 1, 1, 0xFF000000, 2, -1, 0f, null);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(5, dl.count());
        assertEquals(1, dl.runCount());
        assertEquals(0, dl.runStart()[0]);
        assertEquals(5, dl.runEnd()[0]);
    }

    @Test
    void allDifferentKeysProduceOneRunEach() {
        CmdBuf buf = new CmdBuf(4);
        buf.emit(0, 0, 0, 1, 1, 0, 0, -1, 0f, null);
        buf.emit(1, 0, 0, 1, 1, 0, 1, -1, 0f, null);
        buf.emit(2, 0, 0, 1, 1, 0, 2, -1, 0f, null);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(3, dl.count());
        assertEquals(3, dl.runCount());
    }

    @Test
    void alternatingKeysNeverMerge() {
        CmdBuf buf = new CmdBuf(4);
        buf.emit(0, 0, 0, 1, 1, 0, 0, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 1, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 0, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 1, -1, 0f, null);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(4, dl.count());
        assertEquals(4, dl.runCount());
    }

    @Test
    void clipChangeBreaksARunEvenWithSameOpAndResource() {
        CmdBuf buf = new CmdBuf(4);
        buf.emit(0, 0, 0, 1, 1, 0, 5, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 5, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 5, 3, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 5, 3, 0f, null);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(4, dl.count());
        assertEquals(2, dl.runCount());
        assertArrayEquals(new int[]{0, 2}, dl.runStart());
        assertArrayEquals(new int[]{2, 4}, dl.runEnd());
    }

    @Test
    void resetAllowsReuseOfTheSameBackingArrays() {
        CmdBuf buf = new CmdBuf(4);
        buf.emit(0, 0, 0, 1, 1, 0, 0, -1, 0f, null);
        buf.emit(0, 0, 0, 1, 1, 0, 0, -1, 0f, null);
        UiDrawList first = buf.finish(1L, new float[0], null);
        assertEquals(2, first.count());

        buf.reset();
        buf.emit(1, 5, 5, 2, 2, 0, 1, -1, 0f, "hello");
        UiDrawList second = buf.finish(2L, new float[0], null);
        assertEquals(1, second.count());
        assertEquals(1, second.op()[0]);
        assertEquals("hello", second.aux()[0]);
        // first's live view of the shared backing array now reflects the second frame's
        // write at index 0 -- this is the documented zero-copy contract, not a bug.
        assertEquals(1, first.op()[0]);
    }
}
