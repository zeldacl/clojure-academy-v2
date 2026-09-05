package cn.li.presentation.core.engine;

import cn.li.mcmod.runtime.ui.UiOp;

/**
 * Single forward linear scan over an already-arranged {@link LayoutArena},
 * emitting draw commands into a {@link CmdBuf}. Never recomputes geometry —
 * every rect/clip it reads was written by {@link LayoutKernel#arrange}.
 *
 * An invisible instance contributes zero commands for itself AND its whole
 * subtree (matches the pre-rewrite semantics: :visible false hides
 * descendants too, unlike CSS visibility:hidden). Because instances are
 * pre-order with {@link LayoutArena#subtreeEnd}, skipping a hidden subtree
 * is one array read, not a recursive descent.
 *
 * {@code :button}/{@code :text-input}/{@code :slot-anchor}/{@code
 * :scrollbar} have no dedicated opcode here — they are compiler sugar that
 * lowers to a small composite of RECT/NINE/TEXT children (a button is a
 * background plus a label), so this kernel only ever needs to know the 8
 * {@link UiOp} draw primitives plus "not drawable" (pure layout container).
 */
public final class PaintKernel {
    private PaintKernel() {
    }

    public static void paint(NodeTable t, LayoutArena a, LayoutContext ctx, CmdBuf out, int root) {
        if (root < 0) return;
        BindResolver resolver = ctx.resolver();
        int end = a.subtreeEnd[root];
        int i = root;
        while (i < end) {
            if (!LayoutKernel.visible(t, a, resolver, i)) {
                i = a.subtreeEnd[i];
                continue;
            }
            emitNode(t, a, ctx, out, i);
            i++;
        }
    }

    private static void emitNode(NodeTable t, LayoutArena a, LayoutContext ctx, CmdBuf out, int inst) {
        int node = a.nodeOf[inst];
        int op = t.op[node];
        if (op < 0) return;

        BindResolver resolver = ctx.resolver();
        Object item = a.itemOf[inst];
        float x = a.x(inst);
        float y = a.y(inst);
        float w = a.w(inst);
        float h = a.h(inst);
        int clip = a.clipOf[inst];
        int rgba = Bindings.rgba(t, resolver, node, item);

        switch (op) {
            case UiOp.RECT, UiOp.GRADIENT ->
                    out.emit(UiOp.RECT, x, y, w, h, rgba, -1, clip, 0f, null);

            case UiOp.IMAGE ->
                    out.emit(UiOp.IMAGE, x, y, w, h, rgba, Bindings.resourceIndex(t, resolver, node, item), clip, 0f, null);

            case UiOp.NINE -> {
                // padL holds BlendQuad destination margin (default 4). UV is a
                // fixed 3×3 split in the backend — tex size is not needed here.
                float margin = t.padL(node);
                if (margin <= 0f) margin = 4f;
                out.emit(UiOp.NINE, x, y, w, h, rgba,
                        Bindings.resourceIndex(t, resolver, node, item),
                        clip, margin, null);
            }

            case UiOp.TEXT -> {
                String text = Bindings.text(t, resolver, node, item);
                float fontSize = Bindings.fontSize(t, resolver, node, item);
                out.emit(UiOp.TEXT, x, y, w, h, rgba, -1, clip, fontSize, text);
            }

            case UiOp.PROGRESS -> {
                float ratio = Bindings.ratio(t, resolver, node, item);
                out.emit(UiOp.RECT, x, y, w, h, TRACK_RGBA, -1, clip, 0f, null);
                out.emit(UiOp.RECT, x, y, w * ratio, h, rgba, -1, clip, 0f, null);
            }

            case UiOp.ITEM -> {
                Object value = resolver != null ? resolver.attribute(node, BindAttr.VALUE, item) : null;
                out.emit(UiOp.ITEM, x, y, w, h, rgba, -1, clip, 0f, value);
            }

            case UiOp.MODEL -> {
                Object value = resolver != null ? resolver.attribute(node, BindAttr.VALUE, item) : null;
                out.emit(UiOp.MODEL, x, y, w, h, rgba, -1, clip, 0f, value);
            }

            case UiOp.COMPOSITE -> emitComposite(resolver, node, item, x, y, clip, out);

            default -> {
            }
        }
    }

    /** Unthemed progress-track background; real color tokens land with the .ui.edn declarative rewrite. */
    private static final int TRACK_RGBA = 0x55202020;

    /**
     * x/y/w/h in a CompositeSpec are offsets within the composite node's
     * own arranged rect (matching the pre-rewrite :composite primitive's
     * local-coordinate authoring convention) - baseX/baseY are that rect's
     * arranged position.
     */
    private static void emitComposite(BindResolver resolver, int node, Object item,
                                       float baseX, float baseY, int clip, CmdBuf out) {
        if (resolver == null) return;
        Object specObj = resolver.attribute(node, BindAttr.COMPOSITE, item);
        if (!(specObj instanceof CompositeSpec spec)) return;
        float ix = baseX + spec.x();
        float iy = baseY + spec.y();
        switch (spec.kind()) {
            case CompositeSpec.QUAD ->
                    out.emit(UiOp.RECT, ix, iy, spec.w(), spec.h(), spec.rgba(), -1, clip, 0f, null);
            case CompositeSpec.IMAGE -> {
                int idx = out.emit(UiOp.IMAGE, ix, iy, spec.w(), spec.h(), spec.rgba(), spec.resIndex(), clip, 0f, null);
                out.setUv(idx, spec.u0(), spec.v0(), spec.u1(), spec.v1());
            }
            case CompositeSpec.TEXT ->
                    out.emit(UiOp.TEXT, ix, iy, spec.w(), spec.h(), spec.rgba(), -1, clip, spec.fontSize(), spec.text());
            case CompositeSpec.CONDITION -> {
                int idx = out.emit(UiOp.IMAGE, ix, iy, Math.min(14f, spec.w()), Math.min(14f, spec.h()),
                        spec.rgba(), spec.resIndex(), clip, 0f, null);
                out.setUv(idx, spec.u0(), spec.v0(), spec.u1(), spec.v1());
            }
            case CompositeSpec.MODEL ->
                    out.emit(UiOp.MODEL, ix, iy, spec.w(), spec.h(), spec.rgba(), -1, clip, 0f, spec.text());
            default -> {
            }
        }
    }
}
