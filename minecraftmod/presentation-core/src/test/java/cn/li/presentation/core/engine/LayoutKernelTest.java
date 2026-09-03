package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layout conformance table: {node spec, viewport} -> expected rect. This is
 * the highest-value asset the UI engine rewrite produces (see the refactor
 * plan §13) — it is the only thing standing between "the algorithm looks
 * right" and "the algorithm is right".
 */
class LayoutKernelTest {
    private static final LayoutContext NO_BIND = new LayoutContext(null, null, null);
    private static final float EPS = 0.01f;

    private LayoutArena layout(NodeTable t, float viewportW, float viewportH) {
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, NO_BIND.resolver());
        int root = 0;
        LayoutKernel.measure(t, a, NO_BIND, root, viewportW, viewportH, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, NO_BIND, root, 0f, 0f, viewportW, viewportH, -1);
        return a;
    }

    private void assertRect(LayoutArena a, int inst, float x, float y, float w, float h) {
        assertEquals(x, a.x(inst), EPS, "x");
        assertEquals(y, a.y(inst), EPS, "y");
        assertEquals(w, a.w(inst), EPS, "w");
        assertEquals(h, a.h(inst), EPS, "h");
    }

    // ── fixed / auto / fill sizing ──

    @Test
    void fixedSizeChildIsExactlyItsDeclaredSize() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 30f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
        });
        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, c, 0, 0, 30, 20);
    }

    @Test
    void fillChildTakesTheFullMainAxis() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FILL;
        });
        LayoutArena a = layout(b.build(), 100, 40);
        assertRect(a, c, 0, 0, 100, 40);
    }

    @Test
    void percentResolvesAgainstParentContentBox() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).padL = 10f;
        b.node(root).padR = 10f;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.PERCENT;
            n.widthValue = 0.5f;
            n.heightMode = SizeMode.FILL;
        });
        // content box width = 100 - 10 - 10 = 80; 50% = 40
        LayoutArena a = layout(b.build(), 100, 40);
        assertRect(a, c, 10, 0, 40, 40);
    }

    // ── weight distribution ──

    @Test
    void weightedChildrenShareRemainingSpaceProportionally() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        int fixed = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FILL;
        });
        int w1 = b.child(root, n -> {
            n.widthMode = SizeMode.WEIGHT;
            n.widthValue = 1f;
            n.heightMode = SizeMode.FILL;
        });
        int w2 = b.child(root, n -> {
            n.widthMode = SizeMode.WEIGHT;
            n.widthValue = 3f;
            n.heightMode = SizeMode.FILL;
        });
        // free = 100 - 20 = 80; w1 gets 20 (1/4), w2 gets 60 (3/4)
        LayoutArena a = layout(b.build(), 100, 10);
        assertRect(a, fixed, 0, 0, 20, 10);
        assertRect(a, w1, 20, 0, 20, 10);
        assertRect(a, w2, 40, 0, 60, 10);
    }

    // ── gap ──

    @Test
    void gapAddsSpaceBetweenSiblingsOnly() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).gap = 5f;
        int c1 = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        int c2 = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        int c3 = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        LayoutArena a = layout(b.build(), 100, 10);
        assertRect(a, c1, 0, 0, 10, 10);
        assertRect(a, c2, 15, 0, 10, 10);
        assertRect(a, c3, 30, 0, 10, 10);
    }

    // ── justify ──

    @Test
    void justifyCenterCentersTheUsedMainExtent() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).justify = Justify.CENTER;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 20f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        LayoutArena a = layout(b.build(), 100, 10);
        assertRect(a, c, 40, 0, 20, 10);
    }

    @Test
    void justifySpaceBetweenPutsAllFreeSpaceBetweenChildren() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).justify = Justify.SPACE_BETWEEN;
        int c1 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 10f; n.heightMode = SizeMode.FIXED; n.heightValue = 10f; });
        int c2 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 10f; n.heightMode = SizeMode.FIXED; n.heightValue = 10f; });
        int c3 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 10f; n.heightMode = SizeMode.FIXED; n.heightValue = 10f; });
        // free = 100 - 30 = 70, split into 2 gaps of 35
        LayoutArena a = layout(b.build(), 100, 10);
        assertRect(a, c1, 0, 0, 10, 10);
        assertRect(a, c2, 45, 0, 10, 10);
        assertRect(a, c3, 90, 0, 10, 10);
    }

    // ── align ──

    @Test
    void alignItemsStretchFillsTheCrossAxis() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).alignItems = Align.STRETCH;
        int c = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 10f; });
        LayoutArena a = layout(b.build(), 100, 40);
        assertRect(a, c, 0, 0, 10, 40);
    }

    @Test
    void alignSelfOverridesParentAlignItems() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).alignItems = Align.START;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
            n.alignSelf = Align.CENTER;
        });
        LayoutArena a = layout(b.build(), 100, 40);
        assertRect(a, c, 0, 15, 10, 10);
    }

    // ── margin / padding ──

    @Test
    void marginIsExcludedFromTheChildsOwnRectButConsumesSpaceInParent() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        int c1 = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
            n.marginR = 5f;
        });
        int c2 = b.child(root, n -> {
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 10f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 10f;
        });
        LayoutArena a = layout(b.build(), 100, 10);
        assertRect(a, c1, 0, 0, 10, 10);
        assertRect(a, c2, 15, 0, 10, 10);
    }

    @Test
    void paddingShrinksTheContentBoxOfferedsToChildren() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.COLUMN;
        b.node(root).padL = 4f;
        b.node(root).padT = 6f;
        b.node(root).padR = 4f;
        b.node(root).padB = 6f;
        int c = b.child(root, n -> { n.widthMode = SizeMode.FILL; n.heightMode = SizeMode.FILL; });
        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, c, 4, 6, 92, 88);
    }

    // ── min / max clamping ──

    @Test
    void maxWidthClampsAPercentChild() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        int c = b.child(root, n -> {
            n.widthMode = SizeMode.PERCENT;
            n.widthValue = 1.0f;
            n.maxW = 30f;
        });
        LayoutArena a = layout(b.build(), 100, 10);
        assertEquals(30f, a.w(c), EPS);
    }

    @Test
    void minHeightClampsAFixedChild() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int c = b.child(root, n -> {
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 5f;
            n.minH = 12f;
        });
        LayoutArena a = layout(b.build(), 100, 100);
        assertEquals(12f, a.h(c), EPS);
    }

    // ── aspect ──

    @Test
    void aspectDerivesAutoWidthFromFixedHeight() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int c = b.child(root, n -> {
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
            n.aspect = 2.0f; // w/h
        });
        LayoutArena a = layout(b.build(), 100, 100);
        assertEquals(40f, a.w(c), EPS);
        assertEquals(20f, a.h(c), EPS);
    }

    // ── absolute / stack (Direction.NONE) ──

    @Test
    void absoluteChildrenAreOffsetFromContentOrigin() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).padL = 5f;
        b.node(root).padT = 5f;
        int c = b.child(root, n -> {
            n.declaredX = 10f;
            n.declaredY = 20f;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 8f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 8f;
        });
        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, c, 15, 25, 8, 8);
    }

    @Test
    void boundYOverridesTheStaticDeclaredOffset() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int c = b.child(root, n -> {
            n.declaredX = 10f;
            n.declaredY = 10f;
            n.widthMode = SizeMode.FIXED;
            n.widthValue = 5f;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 5f;
        });
        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        BindResolver resolver = (node, attr, item) -> node == c && attr == BindAttr.Y ? -3.5 : null;
        LayoutContext ctx = new LayoutContext(resolver, null, null);
        LayoutKernel.measure(t, a, ctx, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 100, -1);
        // x stays at the static declared 10; y comes from the (negative) bound override.
        assertRect(a, c, 10, -3.5f, 5, 5);
    }

    @Test
    void stackChildrenOverlayAtTheSamePositionByDefault() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int c1 = b.child(root, n -> { n.widthMode = SizeMode.FILL; n.heightMode = SizeMode.FILL; });
        int c2 = b.child(root, n -> { n.widthMode = SizeMode.FILL; n.heightMode = SizeMode.FILL; });
        LayoutArena a = layout(b.build(), 50, 30);
        assertRect(a, c1, 0, 0, 50, 30);
        assertRect(a, c2, 0, 0, 50, 30);
    }

    // ── wrap ──

    @Test
    void wrapBreaksToANewLineWhenMainAxisExceedsAvailable() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).flags = NodeFlags.WRAP;
        int c0 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        int c1 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        int c2 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, c0, 0, 0, 40, 20);
        assertRect(a, c1, 40, 0, 40, 20);
        assertRect(a, c2, 0, 20, 40, 20);
    }

    @Test
    void wrapContainerAutoCrossSizeSumsLineHeightsPlusGap() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).flags = NodeFlags.WRAP;
        b.node(root).gap = 5f;
        b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 30f; });
        b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 40f; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, NO_BIND.resolver());
        // AUTO height on the root itself, measured under AT_MOST so the auto
        // path (sum of line heights) is exercised rather than EXACTLY's
        // "just fill whatever was given" shortcut.
        LayoutKernel.measure(t, a, NO_BIND, 0, 100, 100, LayoutKernel.EXACTLY, LayoutKernel.AT_MOST);
        assertEquals(30f + 5f + 20f, a.measH(0), EPS);
    }

    @Test
    void wrapWithASingleLineBehavesLikeNonWrapRow() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.ROW;
        b.node(root).flags = NodeFlags.WRAP;
        int c0 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 20f; n.heightMode = SizeMode.FIXED; n.heightValue = 10f; });
        int c1 = b.child(root, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 20f; n.heightMode = SizeMode.FIXED; n.heightValue = 10f; });
        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, c0, 0, 0, 20, 10);
        assertRect(a, c1, 20, 0, 20, 10);
    }

    // ── nesting ──

    @Test
    void nestedRowInColumnComputesBothAxesIndependently() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.COLUMN;
        int header = b.child(root, n -> {
            n.direction = Direction.NONE;
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.FIXED;
            n.heightValue = 20f;
        });
        int body = b.child(root, n -> {
            n.direction = Direction.ROW;
            n.widthMode = SizeMode.FILL;
            n.widthMode = SizeMode.FILL;
            n.heightMode = SizeMode.WEIGHT;
            n.heightValue = 1f;
        });
        int left = b.child(body, n -> { n.widthMode = SizeMode.FIXED; n.widthValue = 30f; n.heightMode = SizeMode.FILL; });
        int right = b.child(body, n -> { n.widthMode = SizeMode.WEIGHT; n.widthValue = 1f; n.heightMode = SizeMode.FILL; });

        LayoutArena a = layout(b.build(), 100, 100);
        assertRect(a, header, 0, 0, 100, 20);
        assertRect(a, body, 0, 20, 100, 80);
        assertRect(a, left, 0, 20, 30, 80);
        assertRect(a, right, 30, 20, 70, 80);
    }

    // ── text intrinsic sizing (headless default metrics) ──

    @Test
    void textNodeMeasuresIntrinsicWidthFromDefaultMetrics() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        int c = b.child(root, n -> {
            n.op = UiOp.TEXT;
            n.text = "abcde"; // 5 chars
            n.fontSize = 10f;
            // widthMode/heightMode default AUTO
        });
        LayoutArena a = layout(b.build(), 100, 100);
        // default advance = 0.6 * len * fontSize = 0.6 * 5 * 10 = 30
        assertEquals(30f, a.w(c), EPS);
        // default line height = 1.25 * fontSize = 12.5
        assertEquals(12.5f, a.h(c), EPS);
    }

    // ── clip / scroll ──

    @Test
    void scrollOffsetShiftsChildrenAlongTheMainAxis() {
        NodeTableBuilder b = new NodeTableBuilder();
        int root = b.root();
        b.node(root).direction = Direction.COLUMN;
        b.node(root).flags |= NodeFlags.IS_SCROLL | NodeFlags.HAS_CLIP;
        b.node(root).widthMode = SizeMode.FILL;
        b.node(root).heightMode = SizeMode.FIXED;
        b.node(root).heightValue = 20f;
        int item0 = b.child(root, n -> { n.widthMode = SizeMode.FILL; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });
        int item1 = b.child(root, n -> { n.widthMode = SizeMode.FILL; n.heightMode = SizeMode.FIXED; n.heightValue = 20f; });

        NodeTable t = b.build();
        LayoutArena a = new LayoutArena(8);
        LayoutKernel.expand(t, a, null);
        LayoutContext ctx = new LayoutContext(null, null, new float[]{0f}); // node 0 (root) offset 0 initially
        LayoutKernel.measure(t, a, ctx, 0, 100, 20, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, ctx, 0, 0, 0, 100, 20, -1);
        assertRect(a, item0, 0, 0, 100, 20);
        assertRect(a, item1, 0, 20, 100, 20);

        LayoutContext scrolled = new LayoutContext(null, null, new float[]{15f});
        LayoutKernel.measure(t, a, scrolled, 0, 100, 20, LayoutKernel.EXACTLY, LayoutKernel.EXACTLY);
        LayoutKernel.arrange(t, a, scrolled, 0, 0, 0, 100, 20, -1);
        assertRect(a, item0, 0, -15, 100, 20);
        assertRect(a, item1, 0, 5, 100, 20);
    }
}
