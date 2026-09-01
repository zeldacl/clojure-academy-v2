package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiDrawList;
import cn.li.mcmod.runtime.ui.UiOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeSpecTest {
    @Test
    void quadKindEmitsARectOffsetByTheNodesOwnRect() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int composite = b.child(root, n -> {
            n.op = UiOp.COMPOSITE;
            n.declaredX = 10f;
            n.declaredY = 20f;
        });

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        CompositeSpec spec = new CompositeSpec(CompositeSpec.QUAD, 2f, 3f, 6f, 6f, 0xFFAABBCC, null, 0f, -1);
        BindResolver resolver = (node, attr, item) -> node == composite && attr == BindAttr.COMPOSITE ? spec : null;
        LayoutContext ctx = new LayoutContext(resolver, null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);

        CmdBuf buf = new CmdBuf(4);
        PaintKernel.paint(t, a, ctx, buf, 0);
        UiDrawList dl = buf.finish(1L, new float[0], null);

        assertEquals(1, dl.count());
        assertEquals(UiOp.RECT, dl.op()[0]);
        assertEquals(12f, dl.geom()[0]); // composite's own x (10) + spec offset (2)
        assertEquals(23f, dl.geom()[1]); // composite's own y (20) + spec offset (3)
        assertEquals(0xFFAABBCC, dl.rgba()[0]);
    }

    @Test
    void conditionKindClampsToFourteenPixels() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int composite = b.child(root, n -> n.op = UiOp.COMPOSITE);

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        CompositeSpec spec = new CompositeSpec(CompositeSpec.CONDITION, 0f, 0f, 40f, 40f, 0xFFFFFFFF, null, 0f, 3);
        BindResolver resolver = (node, attr, item) -> node == composite && attr == BindAttr.COMPOSITE ? spec : null;
        LayoutContext ctx = new LayoutContext(resolver, null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);

        CmdBuf buf = new CmdBuf(4);
        PaintKernel.paint(t, a, ctx, buf, 0);
        UiDrawList dl = buf.finish(1L, new float[0], null);

        assertEquals(UiOp.IMAGE, dl.op()[0]);
        assertEquals(14f, dl.geom()[2]);
        assertEquals(14f, dl.geom()[3]);
        assertEquals(3, dl.res()[0]);
    }

    @Test
    void unresolvedCompositeEmitsNothing() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.child(root, n -> n.op = UiOp.COMPOSITE);
        CmdBuf buf = new CmdBuf(4);
        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutContext ctx = new LayoutContext(null, null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);
        PaintKernel.paint(t, a, ctx, buf, 0);
        assertEquals(0, buf.finish(1L, new float[0], null).count());
    }
}
