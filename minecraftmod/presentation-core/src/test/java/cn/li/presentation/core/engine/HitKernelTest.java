package cn.li.presentation.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HitKernelTest {
    private static final LayoutContext NO_BIND = new LayoutContext(null, null, null);

    private LayoutArena layout(NodeTable t) {
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutKernel.measure(t, a, NO_BIND, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, NO_BIND, 0, 0, 0, 100, 100, -1);
        return a;
    }

    @Test
    void topmostOverlappingSiblingWins() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int back = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FILL;
        });
        int front = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FILL;
        });
        NodeTable t = b.build();
        LayoutArena a = layout(t);
        HitKernel.Hit hit = HitKernel.topmostAt(t, a, null, 0, 10, 10);
        assertEquals(front, hit.instance());
    }

    @Test
    void nonInteractiveContainerIsSkippedButChildrenStillHit() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root(); // container itself: no HIT_TESTABLE
        int button = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        NodeTable t = b.build();
        LayoutArena a = layout(t);
        HitKernel.Hit hit = HitKernel.topmostAt(t, a, null, 0, 5, 5);
        assertEquals(button, hit.instance());
    }

    @Test
    void pointOutsideEveryInteractiveRectMisses() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        NodeTable t = b.build();
        LayoutArena a = layout(t);
        assertNull(HitKernel.topmostAt(t, a, null, 0, 50, 50));
    }

    @Test
    void clipRejectsAPointOutsideTheClipEvenIfInsideTheNodesOwnRect() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).flags |= NodeFlags.HAS_CLIP;
        b.node(root).widthMode = SizeMode.FIXED;
        b.node(root).widthValue = 20f;
        b.node(root).heightMode = SizeMode.FIXED;
        b.node(root).heightValue = 20f;
        int overflowing = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.declaredX = 0f;
            n.declaredY = 0f;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 50f; // overflows the clipping parent's 20x20 box
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 50f;
        });
        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        // Root is fixed-size 20x20; arrange it at that size directly rather than
        // at the shared helper's 100x100 viewport, or the EXACTLY-constraint
        // "parent dictates size" rule would make the root fill the viewport and
        // this test would no longer exercise a clip smaller than the point.
        LayoutKernel.measure(t, a, NO_BIND, 0, 20, 20, LayoutKernel.AT_MOST, LayoutKernel.AT_MOST);
        LayoutKernel.arrange(t, a, NO_BIND, 0, 0, 0, 20, 20, -1);
        // (30, 30) is inside the child's own 50x50 rect but outside the parent's 20x20 clip.
        assertNull(HitKernel.topmostAt(t, a, null, 0, 30, 30));
        // (5, 5) is inside both.
        assertEquals(overflowing, HitKernel.topmostAt(t, a, null, 0, 5, 5).instance());
    }

    @Test
    void invisibleSubtreeIsNeverHit() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int hidden = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
        });
        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutContext ctx = new LayoutContext(
                (node, attr, item) -> attr == BindAttr.VISIBLE && node == hidden ? Boolean.FALSE : null,
                null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);
        assertNull(HitKernel.topmostAt(t, a, ctx.resolver(), 0, 5, 5));
    }

    @Test
    void hitReportsTheNearestEnclosingScrollAncestor() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).flags |= NodeFlags.IS_SCROLL;
        b.node(root).widthMode = SizeMode.FIXED;
        b.node(root).widthValue = 50f;
        b.node(root).heightMode = SizeMode.FIXED;
        b.node(root).heightValue = 50f;
        int row = b.child(root, n -> {
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
        });
        int label = b.child(row, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
        });
        NodeTable t = b.build();
        LayoutArena a = layout(t);
        HitKernel.Hit hit = HitKernel.topmostAt(t, a, null, 0, 5, 5);
        assertEquals(label, hit.instance());
        assertEquals(0, hit.enclosingScroll()); // root instance is 0
    }

    @Test
    void itemAndItemIndexAreCarriedFromTheEnclosingCollection() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).flags |= NodeFlags.IS_COLLECTION;
        b.node(root).direction = Direction.COLUMN;
        int template = b.child(root, n -> {
            n.flags = NodeFlags.HIT_TESTABLE;
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        BindResolver resolver = (node, attr, item) ->
                attr == BindAttr.ITEMS && node == root ? java.util.List.of("row-a", "row-b", "row-c") : null;
        LayoutKernel.expand(t, a, resolver);
        LayoutContext ctx = new LayoutContext(resolver, null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);

        HitKernel.Hit hit = HitKernel.topmostAt(t, a, resolver, 0, 5, 15); // second row (y in [10,20))
        assertEquals("row-b", hit.item());
        assertEquals(1, hit.itemIndex());
    }
}
