package cn.li.presentation.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MemoKernelTest {
    /** Minimal NodeTable with a hand-set depMask, bypassing NodeTableBuilder (which zeroes it). */
    private static NodeTable tableWithMask(int n, int node, long mask) {
        NodeTableBuilder b = new NodeTableBuilder();
        b.root();
        for (int i = 1; i < n; i++) b.child(0, x -> {
        });
        NodeTable base = b.build();
        long[] depMask = new long[base.n];
        depMask[node] = mask;
        return new NodeTable(base.n, base.op, base.parent, base.firstChild, base.nextSibling, base.childCount,
                base.flags, base.box, base.widthMode, base.widthValue, base.heightMode, base.heightValue,
                base.gap, base.aspect, base.declaredX, base.declaredY, base.direction, base.justify,
                base.alignItems, base.alignSelf, base.style, base.bind, base.action, base.res, base.anim,
                base.text, base.fontSize, base.rgba, depMask, 1, 8,
                base.styleTable, base.stringTable, base.resources, base.bindPaths, base.actionTable,
                base.nodeKeys, base.focusOrder);
    }

    @Test
    void identicalRevsProduceIdenticalStamp() {
        NodeTable t = tableWithMask(2, 0, 0b101L); // depends on bindings 0 and 2
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{"a", "b", "c"});
        long s1 = MemoKernel.subtreeStamp(m, t, 0, 0L);
        long s2 = MemoKernel.subtreeStamp(m, t, 0, 0L);
        assertEquals(s1, s2);
    }

    @Test
    void changingAWatchedBindingChangesTheStamp() {
        NodeTable t = tableWithMask(2, 0, 0b101L); // watches bindings 0 and 2
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{"a", "b", "c"});
        long before = MemoKernel.subtreeStamp(m, t, 0, 0L);
        m.refreshRevs(new Object[]{"a-changed", "b", "c"});
        long after = MemoKernel.subtreeStamp(m, t, 0, 0L);
        assertNotEquals(before, after);
    }

    @Test
    void changingAnUnwatchedBindingLeavesTheStampUnchanged() {
        NodeTable t = tableWithMask(2, 0, 0b101L); // watches bindings 0 and 2, NOT 1
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{"a", "b", "c"});
        long before = MemoKernel.subtreeStamp(m, t, 0, 0L);
        m.refreshRevs(new Object[]{"a", "b-changed", "c"});
        long after = MemoKernel.subtreeStamp(m, t, 0, 0L);
        assertEquals(before, after);
    }

    @Test
    void rebuildingAnEqualValueDoesNotBumpTheRevision() {
        NodeTable t = tableWithMask(2, 0, 0b1L);
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{java.util.List.of("x", "y")});
        long before = MemoKernel.subtreeStamp(m, t, 0, 0L);
        // Fresh object, same content -- must be treated as unchanged (equals fallback).
        m.refreshRevs(new Object[]{new java.util.ArrayList<>(java.util.List.of("x", "y"))});
        long after = MemoKernel.subtreeStamp(m, t, 0, 0L);
        assertEquals(before, after);
    }

    @Test
    void geometryInvalidationChangesEveryStamp() {
        NodeTable t = tableWithMask(2, 0, 0L); // watches nothing
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{"a"});
        long before = MemoKernel.subtreeStamp(m, t, 0, 0L);
        m.invalidateGeometry();
        long after = MemoKernel.subtreeStamp(m, t, 0, 0L);
        assertNotEquals(before, after);
    }

    @Test
    void differentScrollRevProducesDifferentStampForTheSameNode() {
        NodeTable t = tableWithMask(2, 0, 0L);
        MemoState m = new MemoState(8, 1);
        m.refreshRevs(new Object[]{"a"});
        long atOffsetA = MemoKernel.subtreeStamp(m, t, 0, 10L);
        long atOffsetB = MemoKernel.subtreeStamp(m, t, 0, 20L);
        assertNotEquals(atOffsetA, atOffsetB);
    }
}
