package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiOp;
import cn.li.mcmod.runtime.ui.UiTextMetrics;

import java.util.List;

/**
 * Two-pass layout: measure (bottom-up intrinsic sizing) then arrange
 * (top-down rect placement), plus the collection-expansion pass that turns
 * a compiled {@link NodeTable} into arena instances before either pass
 * runs. This is the single place UI geometry is computed — paint and hit
 * test both read the {@link LayoutArena} this produces and never
 * re-derive a rect (see docs/06-gui/PRESENTATION_V3.md invariant #1).
 *
 * Box model: margin -&gt; border(visual only) -&gt; padding -&gt; content, as CSS.
 * {@code arena.rect} stores the border box (margin excluded); margin only
 * affects how much space a child consumes when its parent packs it
 * against siblings. Percent resolves against the parent's content box on
 * that axis; when the parent's own size on that axis is itself
 * {@code :auto}, a percent child measures to 0 and is not re-resolved
 * (documented limitation — see class-level notes in the refactor plan;
 * avoids a fixpoint solver at the cost of that one combination).
 *
 * Deferred in this pass (first correctness cut favors the row/column/
 * stack/absolute primitives that cover the overwhelming majority of real
 * .ui.edn content): {@code :grid} track layout, {@code :wrap} line
 * breaking, and anchor-based absolute placement. All three are additive —
 * nothing here needs to change shape to add them later.
 */
public final class LayoutKernel {
    public static final int EXACTLY = 0;
    public static final int AT_MOST = 1;
    public static final int UNSPECIFIED = 2;

    private LayoutKernel() {
    }

    // ============================== traversal ==============================

    /** First child instance of {@code inst}, or -1 if it has none. Pre-order numbering makes this O(1). */
    public static int firstChild(LayoutArena a, int inst) {
        int c = inst + 1;
        return c < a.subtreeEnd[inst] ? c : -1;
    }

    /** Next sibling of the child subtree starting at {@code childStart}, or -1 if it was the last. */
    public static int nextSibling(LayoutArena a, int childStart, int parentEnd) {
        int next = a.subtreeEnd[childStart];
        return next < parentEnd ? next : -1;
    }

    // ============================== expansion ==============================

    /**
     * Build the arena's instance tree from the compiled node tree, expanding
     * every {@code IS_COLLECTION} node's single template child once per
     * bound item. Must run before {@link #measure} / {@link #arrange}.
     *
     * @return the root instance index, or -1 if the table is empty
     */
    public static int expand(NodeTable t, LayoutArena a, BindResolver resolver) {
        a.n = 0;
        a.resetClips();
        if (t.n == 0) return -1;
        return expandNode(t, a, resolver, 0, null, -1);
    }

    private static int expandNode(NodeTable t, LayoutArena a, BindResolver resolver,
                                   int node, Object item, int parentInstance) {
        a.ensure(a.n + 1);
        int inst = a.n++;
        a.nodeOf[inst] = node;
        a.parentOf[inst] = parentInstance;
        a.itemOf[inst] = item;
        a.itemIndexOf[inst] = -1;
        a.clipOf[inst] = -1;
        a.visible[inst] = 1;

        if (t.has(node, NodeFlags.IS_COLLECTION)) {
            Object itemsRaw = resolver == null ? null : resolver.attribute(node, BindAttr.ITEMS, item);
            List<?> items = itemsRaw instanceof List<?> l ? l : List.of();
            int template = t.firstChild[node];
            if (template >= 0) {
                for (int idx = 0; idx < items.size(); idx++) {
                    int childInst = expandNode(t, a, resolver, template, items.get(idx), inst);
                    a.itemIndexOf[childInst] = idx;
                }
            }
        } else {
            for (int c = t.firstChild[node]; c >= 0; c = t.nextSibling[c]) {
                expandNode(t, a, resolver, c, item, inst);
            }
        }
        a.subtreeEnd[inst] = a.n;
        return inst;
    }

    // ============================== visibility ==============================

    public static boolean visible(NodeTable t, LayoutArena a, BindResolver resolver, int inst) {
        if (resolver == null) return true;
        return visibleValue(resolver.attribute(a.nodeOf[inst], BindAttr.VISIBLE, a.itemOf[inst]));
    }

    static boolean visibleValue(Object v) {
        if (v == null) return true;
        if (Boolean.FALSE.equals(v)) return false;
        if (v instanceof CharSequence s) return !s.toString().isBlank();
        return true;
    }

    // ================================ measure ================================

    public static void measure(NodeTable t, LayoutArena a, LayoutContext ctx,
                                int inst, float availW, float availH, int modeW, int modeH) {
        int node = a.nodeOf[inst];
        Object item = a.itemOf[inst];
        boolean hasDir = t.has(node, NodeFlags.HAS_DIRECTION);
        int dir = hasDir ? t.direction[node] : Direction.NONE;

        float boundW = Bindings.fixedOverride(t, ctx.resolver(), node, item, true);
        float boundH = Bindings.fixedOverride(t, ctx.resolver(), node, item, false);
        int wMode = boundW >= 0f ? SizeMode.FIXED : t.widthMode[node];
        int hMode = boundH >= 0f ? SizeMode.FIXED : t.heightMode[node];
        float wVal = boundW >= 0f ? boundW : t.widthValue[node];
        float hVal = boundH >= 0f ? boundH : t.heightValue[node];

        float measuredW;
        float measuredH;
        if (t.op[node] == UiOp.TEXT) {
            float[] r = measureText(t, ctx, node, item, availW, availH, modeW, modeH, wMode, wVal, hMode, hVal);
            measuredW = r[0];
            measuredH = r[1];
        } else if (dir == Direction.ROW || dir == Direction.COLUMN) {
            float[] r = measureLinear(t, a, ctx, inst, availW, availH, modeW, modeH, dir == Direction.ROW,
                    wMode, wVal, hMode, hVal);
            measuredW = r[0];
            measuredH = r[1];
        } else {
            float[] r = measureFree(t, a, ctx, inst, availW, availH, modeW, modeH, wMode, wVal, hMode, hVal);
            measuredW = r[0];
            measuredH = r[1];
        }

        float aspect = t.aspect[node];
        if (aspect > 0f) {
            boolean wAuto = wMode == SizeMode.AUTO;
            boolean hAuto = hMode == SizeMode.AUTO;
            if (wAuto && !hAuto) measuredW = measuredH * aspect;
            else if (hAuto && !wAuto) measuredH = measuredW / aspect;
        }

        measuredW = clamp(measuredW, t.minW(node), t.maxW(node));
        measuredH = clamp(measuredH, t.minH(node), t.maxH(node));
        a.setMeas(inst, measuredW, measuredH);
    }

    private static float clamp(float v, float min, float max) {
        float r = v;
        if (min > 0f) r = Math.max(r, min);
        if (max > 0f) r = Math.min(r, max);
        return r;
    }

    private static float weightOf(NodeTable t, int node, boolean row) {
        int mode = row ? t.widthMode[node] : t.heightMode[node];
        return mode == SizeMode.WEIGHT ? (row ? t.widthValue[node] : t.heightValue[node]) : 0f;
    }

    private static float outerMain(NodeTable t, LayoutArena a, int inst, boolean row) {
        int node = a.nodeOf[inst];
        float m = row ? a.measW(inst) : a.measH(inst);
        float margin = row ? (t.marginL(node) + t.marginR(node)) : (t.marginT(node) + t.marginB(node));
        return m + margin;
    }

    private static float outerCross(NodeTable t, LayoutArena a, int inst, boolean row) {
        int node = a.nodeOf[inst];
        float m = row ? a.measH(inst) : a.measW(inst);
        float margin = row ? (t.marginT(node) + t.marginB(node)) : (t.marginL(node) + t.marginR(node));
        return m + margin;
    }

    /**
     * Resolve this node's own final size along one axis.
     *
     * @param incomingConstraintMode the constraint the PARENT imposed on this node for this axis
     * @param avail                  the space the parent gave this node for this axis
     * @param autoValue              the intrinsic (content-derived) size for this axis
     */
    private static float resolveOwnAxis(int mode, float value, int incomingConstraintMode, float avail, float autoValue) {
        if (incomingConstraintMode == EXACTLY) return avail;
        if (incomingConstraintMode == UNSPECIFIED
                && (mode == SizeMode.PERCENT || mode == SizeMode.FILL || mode == SizeMode.WEIGHT)) {
            return autoValue;
        }
        return switch (mode) {
            case SizeMode.FIXED -> value;
            case SizeMode.PERCENT -> avail * value;
            case SizeMode.FILL, SizeMode.WEIGHT -> avail;
            default -> incomingConstraintMode == AT_MOST ? Math.min(autoValue, avail) : autoValue;
        };
    }

    /**
     * A text node has no children, so its intrinsic size comes from
     * {@link UiTextMetrics} rather than from summing a child list. When no
     * metrics implementation is installed (headless tests, JMH), a
     * deterministic fixed-pitch estimate is used instead so presentation-core
     * stays testable without a Minecraft font backend.
     */
    private static float[] measureText(NodeTable t, LayoutContext ctx, int node, Object item,
                                        float availW, float availH, int modeW, int modeH,
                                        int wMode, float wVal, int hMode, float hVal) {
        String text = Bindings.text(t, ctx.resolver(), node, item);
        float fontSize = Bindings.fontSize(t, ctx.resolver(), node, item);
        UiTextMetrics metrics = ctx.metrics();
        float advance = metrics != null ? metrics.advance(0, text, fontSize) : defaultAdvance(text, fontSize);
        float lineHeight = metrics != null ? metrics.lineHeight(0, fontSize) : fontSize * 1.25f;

        float measuredW = resolveOwnAxis(wMode, wVal, modeW, availW, advance);
        float measuredH = resolveOwnAxis(hMode, hVal, modeH, availH, lineHeight);
        return new float[]{measuredW, measuredH};
    }

    private static float defaultAdvance(String text, float fontSize) {
        return text == null ? 0f : 0.6f * text.length() * fontSize;
    }

    private static float[] measureLinear(NodeTable t, LayoutArena a, LayoutContext ctx, int inst,
                                          float availW, float availH, int modeW, int modeH, boolean row,
                                          int wMode, float wVal, int hMode, float hVal) {
        int node = a.nodeOf[inst];
        float contentAvailW = Math.max(0f, availW - t.padL(node) - t.padR(node));
        float contentAvailH = Math.max(0f, availH - t.padT(node) - t.padB(node));
        float mainAvail = row ? contentAvailW : contentAvailH;
        float gap = t.gap[node];
        int end = a.subtreeEnd[inst];

        int k = 0;
        float fixedMain = 0f;
        float maxCross = 0f;
        float totalWeight = 0f;

        for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
            k++;
            int cNode = a.nodeOf[c];
            float wt = weightOf(t, cNode, row);
            if (wt > 0f) {
                totalWeight += wt;
                continue;
            }
            float remain = Math.max(0f, mainAvail - fixedMain);
            if (row) measure(t, a, ctx, c, remain, contentAvailH, AT_MOST, AT_MOST);
            else measure(t, a, ctx, c, contentAvailW, remain, AT_MOST, AT_MOST);
            fixedMain += outerMain(t, a, c, row);
            maxCross = Math.max(maxCross, outerCross(t, a, c, row));
        }

        float free = Math.max(0f, mainAvail - fixedMain - gap * Math.max(0, k - 1));

        if (totalWeight > 0f) {
            for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
                int cNode = a.nodeOf[c];
                float wt = weightOf(t, cNode, row);
                if (wt <= 0f) continue;
                float share = free * wt / totalWeight;
                if (row) measure(t, a, ctx, c, share, contentAvailH, EXACTLY, AT_MOST);
                else measure(t, a, ctx, c, contentAvailW, share, AT_MOST, EXACTLY);
                maxCross = Math.max(maxCross, outerCross(t, a, c, row));
            }
        }

        float sumMain = fixedMain + (totalWeight > 0f ? free : 0f) + gap * Math.max(0, k - 1);
        float padMain = row ? t.padL(node) + t.padR(node) : t.padT(node) + t.padB(node);
        float padCross = row ? t.padT(node) + t.padB(node) : t.padL(node) + t.padR(node);

        float measuredMain = resolveOwnAxis(row ? wMode : hMode, row ? wVal : hVal,
                row ? modeW : modeH, row ? availW : availH, sumMain + padMain);
        float measuredCross = resolveOwnAxis(row ? hMode : wMode, row ? hVal : wVal,
                row ? modeH : modeW, row ? availH : availW, maxCross + padCross);

        return row ? new float[]{measuredMain, measuredCross} : new float[]{measuredCross, measuredMain};
    }

    private static float[] measureFree(NodeTable t, LayoutArena a, LayoutContext ctx, int inst,
                                        float availW, float availH, int modeW, int modeH,
                                        int wMode, float wVal, int hMode, float hVal) {
        int node = a.nodeOf[inst];
        float contentAvailW = Math.max(0f, availW - t.padL(node) - t.padR(node));
        float contentAvailH = Math.max(0f, availH - t.padT(node) - t.padB(node));

        float maxW = 0f;
        float maxH = 0f;
        int end = a.subtreeEnd[inst];
        for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
            measure(t, a, ctx, c, contentAvailW, contentAvailH, AT_MOST, AT_MOST);
            int cNode = a.nodeOf[c];
            maxW = Math.max(maxW, a.measW(c) + t.marginL(cNode) + t.marginR(cNode) + Math.max(0f, t.declaredX[cNode]));
            maxH = Math.max(maxH, a.measH(c) + t.marginT(cNode) + t.marginB(cNode) + Math.max(0f, t.declaredY[cNode]));
        }

        float measuredW = resolveOwnAxis(wMode, wVal, modeW, availW, maxW + t.padL(node) + t.padR(node));
        float measuredH = resolveOwnAxis(hMode, hVal, modeH, availH, maxH + t.padT(node) + t.padB(node));
        return new float[]{measuredW, measuredH};
    }

    // ================================ arrange ================================

    public static void arrange(NodeTable t, LayoutArena a, LayoutContext ctx,
                                int inst, float x, float y, float w, float h, int clipIdx) {
        int node = a.nodeOf[inst];
        a.setRect(inst, x, y, w, h);
        a.clipOf[inst] = clipIdx;

        int myClip = clipIdx;
        if (t.has(node, NodeFlags.HAS_CLIP)) {
            float cx0 = x + t.padL(node);
            float cy0 = y + t.padT(node);
            float cw0 = Math.max(0f, w - t.padL(node) - t.padR(node));
            float ch0 = Math.max(0f, h - t.padT(node) - t.padB(node));
            float[] isect = clipIdx >= 0 ? intersect(a, clipIdx, cx0, cy0, cw0, ch0) : new float[]{cx0, cy0, cw0, ch0};
            myClip = a.pushClip(isect[0], isect[1], isect[2], isect[3]);
        }

        boolean hasDir = t.has(node, NodeFlags.HAS_DIRECTION);
        int dir = hasDir ? t.direction[node] : Direction.NONE;

        float contentX = x + t.padL(node);
        float contentY = y + t.padT(node);
        float contentW = Math.max(0f, w - t.padL(node) - t.padR(node));
        float contentH = Math.max(0f, h - t.padT(node) - t.padB(node));

        if (dir == Direction.ROW || dir == Direction.COLUMN) {
            arrangeLinear(t, a, ctx, inst, contentX, contentY, contentW, contentH, dir == Direction.ROW, myClip);
        } else {
            arrangeFree(t, a, ctx, inst, contentX, contentY, contentW, contentH, myClip);
        }
    }

    private static float[] intersect(LayoutArena a, int clipIdx, float x, float y, float w, float h) {
        int b = clipIdx * 4;
        float px = a.clipRects[b];
        float py = a.clipRects[b + 1];
        float pw = a.clipRects[b + 2];
        float ph = a.clipRects[b + 3];
        float x0 = Math.max(px, x);
        float y0 = Math.max(py, y);
        float x1 = Math.min(px + pw, x + w);
        float y1 = Math.min(py + ph, y + h);
        return new float[]{x0, y0, Math.max(0f, x1 - x0), Math.max(0f, y1 - y0)};
    }

    private static void arrangeLinear(NodeTable t, LayoutArena a, LayoutContext ctx, int inst,
                                       float cx, float cy, float cw, float ch, boolean row, int clipIdx) {
        int node = a.nodeOf[inst];
        float gap = t.gap[node];
        int end = a.subtreeEnd[inst];

        int k = 0;
        float sumMain = 0f;
        float totalWeight = 0f;
        for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
            k++;
            int cNode = a.nodeOf[c];
            float wt = weightOf(t, cNode, row);
            if (wt > 0f) {
                totalWeight += wt;
            } else {
                sumMain += outerMain(t, a, c, row);
            }
        }

        float mainAvail = row ? cw : ch;
        float free = Math.max(0f, mainAvail - sumMain - gap * Math.max(0, k - 1));
        float weightedMain = totalWeight > 0f ? free : 0f;
        float usedMain = sumMain + weightedMain + gap * Math.max(0, k - 1);

        float cursor;
        float extraGap = 0f;
        switch (t.justify[node]) {
            case Justify.CENTER -> cursor = (mainAvail - usedMain) / 2f;
            case Justify.END -> cursor = mainAvail - usedMain;
            case Justify.SPACE_BETWEEN -> {
                cursor = 0f;
                extraGap = k > 1 ? (mainAvail - usedMain) / (k - 1) : 0f;
            }
            case Justify.SPACE_AROUND -> {
                extraGap = k > 0 ? (mainAvail - usedMain) / k : 0f;
                cursor = extraGap / 2f;
            }
            default -> cursor = 0f;
        }

        cursor -= t.has(node, NodeFlags.IS_SCROLL) ? ctx.scrollOffset(node) : 0f;

        boolean first = true;
        for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
            int cNode = a.nodeOf[c];
            if (!first) cursor += gap + extraGap;
            first = false;

            float wt = weightOf(t, cNode, row);
            float mainSize = wt > 0f ? (totalWeight > 0f ? weightedMain * wt / totalWeight : 0f)
                    : (row ? a.measW(c) : a.measH(c));
            float marginMainStart = row ? t.marginL(cNode) : t.marginT(cNode);
            float marginMainEnd = row ? t.marginR(cNode) : t.marginB(cNode);
            float marginCrossStart = row ? t.marginT(cNode) : t.marginL(cNode);
            float marginCrossEnd = row ? t.marginB(cNode) : t.marginR(cNode);
            float crossSize = row ? a.measH(c) : a.measW(c);
            float crossAvail = row ? ch : cw;

            cursor += marginMainStart;

            int align = t.alignSelf[cNode] != Align.INHERIT ? t.alignSelf[cNode] : t.alignItems[node];
            float crossPos;
            float finalCross = crossSize;
            switch (align) {
                case Align.CENTER -> crossPos = (crossAvail - crossSize) / 2f;
                case Align.END -> crossPos = crossAvail - crossSize - marginCrossEnd;
                case Align.STRETCH -> {
                    crossPos = marginCrossStart;
                    finalCross = Math.max(0f, crossAvail - marginCrossStart - marginCrossEnd);
                }
                default -> crossPos = marginCrossStart;
            }

            float childX = row ? cx + cursor : cx + crossPos;
            float childY = row ? cy + crossPos : cy + cursor;
            float childW = row ? mainSize : finalCross;
            float childH = row ? finalCross : mainSize;

            arrange(t, a, ctx, c, childX, childY, childW, childH, clipIdx);

            cursor += mainSize + marginMainEnd;
        }
    }

    private static void arrangeFree(NodeTable t, LayoutArena a, LayoutContext ctx, int inst,
                                     float cx, float cy, float cw, float ch, int clipIdx) {
        int end = a.subtreeEnd[inst];
        for (int c = firstChild(a, inst); c >= 0; c = nextSibling(a, c, end)) {
            int cNode = a.nodeOf[c];
            float childX = cx + t.declaredX[cNode];
            float childY = cy + t.declaredY[cNode];
            float childW = a.measW(c);
            float childH = a.measH(c);
            arrange(t, a, ctx, c, childX, childY, childW, childH, clipIdx);
        }
    }
}
