package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiDrawList;
import cn.li.mcmod.runtime.ui.UiOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaintKernelTest {
    private static final LayoutContext NO_BIND = new LayoutContext(null, null, null);

    private LayoutArena layoutAndPaint(NodeTable t, CmdBuf buf) {
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutKernel.measure(t, a, NO_BIND, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, NO_BIND, 0, 0, 0, 100, 100, -1);
        PaintKernel.paint(t, a, NO_BIND, buf, 0);
        return a;
    }

    @Test
    void pureLayoutContainerEmitsNothing() {
        NodeTableBuilder b = new NodeTableBuilder();
        b.root();
        CmdBuf buf = new CmdBuf(4);
        layoutAndPaint(b.build(), buf);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(0, dl.count());
    }

    @Test
    void rectNodeEmitsOneRectCommandAtItsArrangedRect() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.child(root, n -> {
            n.op = UiOp.RECT;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        CmdBuf buf = new CmdBuf(4);
        layoutAndPaint(b.build(), buf);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(1, dl.count());
        assertEquals(UiOp.RECT, dl.op()[0]);
        assertEquals(0f, dl.geom()[0]);
        assertEquals(20f, dl.geom()[2]);
        assertEquals(10f, dl.geom()[3]);
    }

    @Test
    void invisibleNodeAndItsWholeSubtreeEmitNothing() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int hidden = b.child(root, n -> n.op = UiOp.RECT);
        b.child(hidden, n -> n.op = UiOp.RECT);

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutContext ctx = new LayoutContext(
                (node, attr, item) -> attr == BindAttr.VISIBLE && node == hidden ? Boolean.FALSE : null,
                null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);
        CmdBuf buf = new CmdBuf(4);
        PaintKernel.paint(t, a, ctx, buf, 0);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(0, dl.count());
    }

    @Test
    void progressEmitsTrackThenFillScaledByRatio() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int p = b.child(root, n -> {
            n.op = UiOp.PROGRESS;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 40f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 8f;
        });

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutContext ctx = new LayoutContext(
                (node, attr, item) -> node == p && attr == BindAttr.VALUE ? 0.5 : null,
                null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);
        CmdBuf buf = new CmdBuf(4);
        PaintKernel.paint(t, a, ctx, buf, 0);
        UiDrawList dl = buf.finish(1L, new float[0], null);

        assertEquals(2, dl.count());
        assertEquals(UiOp.RECT, dl.op()[0]);
        assertEquals(40f, dl.geom()[2]); // track: full width
        assertEquals(UiOp.RECT, dl.op()[1]);
        assertEquals(20f, dl.geom()[6]); // fill: 40 * 0.5 (geom[1*4+2])
    }

    @Test
    void textEmitsWithResolvedFontSizeAsScalar() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.child(root, n -> {
            n.op = UiOp.TEXT;
            n.text = "hi";
            n.fontSize = 12f;
        });
        CmdBuf buf = new CmdBuf(4);
        layoutAndPaint(b.build(), buf);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(1, dl.count());
        assertEquals(UiOp.TEXT, dl.op()[0]);
        assertEquals("hi", dl.aux()[0]);
        assertEquals(12f, dl.scalar()[0]);
    }

    @Test
    void adjacentRectsWithNoResourceAndNoClipMergeIntoOneRun() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.child(root, n -> { n.op = UiOp.RECT; n.widthMode = SizeMode.FIXED; n.widthValue = 5f; n.heightMode = SizeMode.FIXED; n.heightValue = 5f; });
        b.child(root, n -> { n.op = UiOp.RECT; n.widthMode = SizeMode.FIXED; n.widthValue = 5f; n.heightMode = SizeMode.FIXED; n.heightValue = 5f; });
        b.child(root, n -> { n.op = UiOp.RECT; n.widthMode = SizeMode.FIXED; n.widthValue = 5f; n.heightMode = SizeMode.FIXED; n.heightValue = 5f; });
        CmdBuf buf = new CmdBuf(4);
        layoutAndPaint(b.build(), buf);
        UiDrawList dl = buf.finish(1L, new float[0], null);
        assertEquals(3, dl.count());
        assertEquals(1, dl.runCount());
    }
}
