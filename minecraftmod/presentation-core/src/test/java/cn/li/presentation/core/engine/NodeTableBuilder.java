package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.UiResourceRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only fluent builder for {@link NodeTable}. The real compiler (which
 * builds a NodeTable from a compiled .uic.edn artifact) is a separate,
 * later piece of work; this exists purely so LayoutKernel/PaintKernel/
 * HitKernel/MemoKernel tests can construct trees without hand-writing
 * fifteen parallel arrays per test.
 */
final class NodeTableBuilder {
    private final List<N> nodes = new ArrayList<>();

    /** Not a drawable opcode — the default for pure layout containers in tests. */
    static final int OP_NONE = -1;

    static final class N {
        int op = OP_NONE;
        int parent = -1;
        final List<Integer> children = new ArrayList<>();
        int flags;
        float marginL, marginT, marginR, marginB;
        float padL, padT, padR, padB;
        float minW, minH, maxW, maxH;
        int widthMode = SizeMode.AUTO;
        float widthValue;
        int heightMode = SizeMode.AUTO;
        float heightValue;
        float gap;
        float aspect;
        float declaredX, declaredY;
        int direction = Direction.NONE;
        int justify = Justify.START;
        int alignItems = Align.START;
        int alignSelf = Align.INHERIT;
        float fontSize = 8.0f;
        String text;
    }

    /** Root-only construction: use {@link #child} to add descendants and capture their indices. */
    int root() {
        nodes.add(new N());
        return 0;
    }

    int child(int parent, java.util.function.Consumer<N> config) {
        N n = new N();
        n.parent = parent;
        int idx = nodes.size();
        nodes.add(n);
        nodes.get(parent).children.add(idx);
        if (config != null) config.accept(n);
        return idx;
    }

    N node(int idx) {
        return nodes.get(idx);
    }

    NodeTable build() {
        int n = nodes.size();
        int[] op = new int[n];
        int[] parent = new int[n];
        int[] firstChild = new int[n];
        int[] nextSibling = new int[n];
        int[] childCount = new int[n];
        int[] flags = new int[n];
        float[] box = new float[n * 12];
        int[] widthMode = new int[n];
        float[] widthValue = new float[n];
        int[] heightMode = new int[n];
        float[] heightValue = new float[n];
        float[] gap = new float[n];
        float[] aspect = new float[n];
        float[] declaredX = new float[n];
        float[] declaredY = new float[n];
        int[] direction = new int[n];
        int[] justify = new int[n];
        int[] alignItems = new int[n];
        int[] alignSelf = new int[n];
        float[] fontSize = new float[n];
        int[] rgba = new int[n];
        int[] textIdx = new int[n];
        String[] strings = new String[n];

        for (int i = 0; i < n; i++) {
            N src = nodes.get(i);
            op[i] = src.op;
            parent[i] = src.parent;
            List<Integer> kids = src.children;
            childCount[i] = kids.size();
            firstChild[i] = kids.isEmpty() ? -1 : kids.get(0);
            for (int k = 0; k < kids.size(); k++) {
                int childIdx = kids.get(k);
                nextSibling[childIdx] = k + 1 < kids.size() ? kids.get(k + 1) : -1;
            }
            flags[i] = src.flags | (src.direction != Direction.NONE ? NodeFlags.HAS_DIRECTION : 0);
            int b = i * 12;
            box[b] = src.marginL;
            box[b + 1] = src.marginT;
            box[b + 2] = src.marginR;
            box[b + 3] = src.marginB;
            box[b + 4] = src.padL;
            box[b + 5] = src.padT;
            box[b + 6] = src.padR;
            box[b + 7] = src.padB;
            box[b + 8] = src.minW;
            box[b + 9] = src.minH;
            box[b + 10] = src.maxW;
            box[b + 11] = src.maxH;
            widthMode[i] = src.widthMode;
            widthValue[i] = src.widthValue;
            heightMode[i] = src.heightMode;
            heightValue[i] = src.heightValue;
            gap[i] = src.gap;
            aspect[i] = src.aspect;
            declaredX[i] = src.declaredX;
            declaredY[i] = src.declaredY;
            direction[i] = src.direction;
            justify[i] = src.justify;
            alignItems[i] = src.alignItems;
            alignSelf[i] = src.alignSelf;
            fontSize[i] = src.fontSize;
            rgba[i] = 0xFFFFFFFF;
            if (src.text != null) {
                strings[i] = src.text;
                textIdx[i] = i;
            } else {
                textIdx[i] = -1;
            }
        }

        int[] noIdx = new int[n];
        java.util.Arrays.fill(noIdx, -1);

        return new NodeTable(
                n, op, parent, firstChild, nextSibling, childCount, flags, box,
                widthMode, widthValue, heightMode, heightValue, gap, aspect, declaredX, declaredY,
                direction, justify, alignItems, alignSelf,
                noIdx.clone(), noIdx.clone(), noIdx.clone(), noIdx.clone(), noIdx.clone(), textIdx, fontSize, rgba,
                new long[n], 1, 0,
                new Object[0], strings, new UiResourceRef[0],
                new Object[0], new Object[0], new Object[n], new int[0]);
    }
}
